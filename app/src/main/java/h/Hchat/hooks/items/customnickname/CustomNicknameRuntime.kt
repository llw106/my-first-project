package h.Hchat.hooks.items.customnickname

import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.utils.HLog
import java.lang.ref.WeakReference
import java.lang.reflect.Method

/**
 * 只改显示层：把会话列表标题里的原显示名替换成自定义昵称。
 * 不修改微信任何持久化数据，也不动列表结构。
 */
class CustomNicknameRuntime(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {

    private val unhooks = ArrayList<XC_MethodHook.Unhook>()
    private var installed = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var adapterRef: WeakReference<Any>? = null
    private var diagCount = 0

    fun install() {
        if (installed) return
        installed = true
        installConversationTitle()
    }

    /** 设置页保存后立刻刷新会话列表，让新标题立即出现。 */
    fun refresh() {
        val adapter = adapterRef?.get() ?: return
        mainHandler.post {
            runCatching {
                adapter.javaClass.methods
                    .firstOrNull { it.name == "notifyDataSetChanged" && it.parameterCount == 0 }
                    ?.invoke(adapter)
            }.onFailure { logger("刷新会话列表失败", it) }
        }
    }

    fun destroy() {
        unhooks.forEach { runCatching { it.unhook() } }
        unhooks.clear()
        installed = false
    }

    private fun installConversationTitle() {
        val loader = context.hostClassLoader()
        val adapterClass = findAdapterClass(loader)
        if (adapterClass == null) {
            log("未找到会话列表 adapter 类", null)
            return
        }
        log("adapter=${adapterClass.name} 方法=${describe(adapterClass)}", null)

        val titleMethod = adapterClass.declaredMethods.firstOrNull { candidate ->
            candidate.name == "G" &&
                candidate.parameterTypes.size == 4 &&
                candidate.returnType == CharSequence::class.java &&
                candidate.parameterTypes[1] == Int::class.javaPrimitiveType
        }
        if (titleMethod == null) {
            log("未找到会话标题生成方法 G，adapter=${adapterClass.name}", null)
        } else {
            hook(titleMethod, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    adapterRef = WeakReference(param.thisObject)
                    if (diagCount < DIAG_LIMIT) {
                        diagCount++
                        val conversation = param.args.getOrNull(0)
                        log(
                            "G命中 conv=${conversation?.javaClass?.name} un=${conversation?.let { usernameOf(it) }}" +
                                " title=${(param.result as? CharSequence)?.take(40)}",
                            null
                        )
                    }
                    runCatching { rewriteTitle(param) }
                        .onFailure { logger("替换会话标题失败", it) }
                }
            })
            log("会话标题 hook 已安装: ${adapterClass.name}#${titleMethod.name}", null)
        }
        installItemHooks(adapterClass)
    }

    /** 兜底方案：直接改列表 item 里显示名字的那个 TextView。 */
    private fun installItemHooks(adapterClass: Class<*>) {
        val getView = adapterClass.declaredMethods.firstOrNull { candidate ->
            candidate.name == "getView" &&
                candidate.parameterTypes.size == 3 &&
                View::class.java.isAssignableFrom(candidate.returnType)
        }
        if (getView != null) {
            hook(getView, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching { applyToItemView(param) }
                        .onFailure { logger("改写列表项标题失败", it) }
                }
            })
            log("列表 getView hook 已安装", null)
        }
        val bind = adapterClass.declaredMethods.firstOrNull { candidate ->
            candidate.name == "onBindViewHolder" && candidate.parameterTypes.size == 2
        }
        if (bind != null) {
            hook(bind, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching { applyToItemView(param) }
                        .onFailure { logger("改写列表项标题失败", it) }
                }
            })
            log("列表 onBindViewHolder hook 已安装", null)
        }
    }

    private fun applyToItemView(param: XC_MethodHook.MethodHookParam) {
        val adapter = param.thisObject ?: return
        adapterRef = WeakReference(adapter)
        val args = param.args ?: return
        val position = args.firstOrNull { it is Int } as? Int ?: return
        val item = itemAt(adapter, position) ?: return
        val username = usernameOf(item) ?: return
        val custom = CustomNicknameSettings.nickname(context.hostContext(), username) ?: return
        if (!CustomNicknameSettings.scopeEnabled(context.hostContext(), NicknameSurface.CONVERSATION)) return
        val root = resolveItemView(param, args) as? ViewGroup ?: return
        val texts = ArrayList<TextView>()
        collectTextViews(root, texts)
        val current = displayNameOf(username)
        val target = texts.firstOrNull { it.text?.toString()?.trim() == current }
            ?: texts.firstOrNull { text ->
                current.isNotEmpty() && text.text?.toString()?.contains(current) == true
            }
            ?: return
        if (target.text?.toString() == custom) return
        target.text = custom
        if (diagCount < DIAG_LIMIT) {
            diagCount++
            log("列表项改写 un=$username custom=$custom", null)
        }
    }

    private fun resolveItemView(param: XC_MethodHook.MethodHookParam, args: Array<Any?>): View? {
        (param.result as? View)?.let { return it }
        val holder = args.getOrNull(0) ?: return null
        runCatching {
            holder.javaClass.getMethod("getItemView").invoke(holder) as? View
        }.getOrNull()?.let { return it }
        return runCatching {
            holder.javaClass.getField("itemView").get(holder) as? View
        }.getOrNull()
    }

    private fun itemAt(adapter: Any, position: Int): Any? {
        val method = adapter.javaClass.methods.firstOrNull { candidate ->
            candidate.name == "getItem" &&
                candidate.parameterCount == 1 &&
                candidate.parameterTypes[0] == Int::class.javaPrimitiveType
        } ?: return null
        return runCatching { method.invoke(adapter, position) }.getOrNull()
    }

    private fun findAdapterClass(loader: ClassLoader): Class<*>? {
        val candidates = listOf(
            "com.tencent.mm.ui.conversation.t2",
            "com.tencent.mm.ui.conversation.ConversationAdapter",
            "com.tencent.mm.ui.conversation.MvvmConversationAdapter"
        )
        for (name in candidates) {
            val loaded = runCatching { loader.loadClass(name) }.getOrNull() ?: continue
            if (loaded.declaredMethods.any { it.name == "G" && it.parameterTypes.size == 4 }) {
                return loaded
            }
        }
        return null
    }

    private fun rewriteTitle(param: XC_MethodHook.MethodHookParam) {
        if (!CustomNicknameSettings.scopeEnabled(context.hostContext(), NicknameSurface.CONVERSATION)) {
            return
        }
        val conversation = param.args.getOrNull(0) ?: return
        val username = usernameOf(conversation) ?: return
        val custom = CustomNicknameSettings.nickname(context.hostContext(), username) ?: return
        val original = param.result as? CharSequence ?: return
        if (original.isEmpty()) return
        val current = displayNameOf(username)
        if (current.isEmpty()) {
            param.result = custom
            return
        }
        val builder = SpannableStringBuilder(original)
        var index = builder.indexOf(current)
        if (index < 0) {
            param.result = custom
            return
        }
        while (index >= 0) {
            builder.replace(index, index + current.length, custom)
            index = builder.indexOf(current, index + custom.length)
        }
        param.result = builder
    }

    private fun displayNameOf(username: String): String =
        runCatching { WeChatApis.users()?.displayName(username) }
            .getOrNull()
            ?.trim()
            .orEmpty()

    private fun usernameOf(conversation: Any): String? {
        val classOfItem = conversation.javaClass
        val getters = listOf("h1", "getUsername", "getUserName", "getTalker", "d")
        for (name in getters) {
            val method = classOfItem.methods.firstOrNull {
                it.name == name && it.parameterCount == 0 && it.returnType == String::class.java
            } ?: continue
            val value = runCatching { method.invoke(conversation) as? String }
                .getOrNull()
                ?.trim()
                .orEmpty()
            if (value.isNotEmpty()) return value
        }
        val fieldNames = listOf("field_username", "username", "field_talker", "talker")
        var current: Class<*>? = classOfItem
        while (current != null) {
            val owner = current
            for (name in fieldNames) {
                val field = runCatching { owner.getDeclaredField(name) }.getOrNull() ?: continue
                field.isAccessible = true
                val value = runCatching { field.get(conversation) as? String }
                    .getOrNull()
                    ?.trim()
                    .orEmpty()
                if (value.isNotEmpty()) return value
            }
            current = owner.superclass
        }
        return null
    }

    private fun collectTextViews(root: ViewGroup, out: MutableList<TextView>) {
        for (index in 0 until root.childCount) {
            when (val child = root.getChildAt(index)) {
                is TextView -> if (child.visibility == View.VISIBLE) out += child
                is ViewGroup -> collectTextViews(child, out)
            }
        }
    }

    private fun describe(adapterClass: Class<*>): String {
        val text = adapterClass.declaredMethods.joinToString("; ") { method ->
            "${method.name}(${method.parameterTypes.joinToString(",") { it.simpleName }})"
        }
        return text.take(1200)
    }

    private fun hook(member: Method, callback: XC_MethodHook) {
        runCatching { HookRegistry.get().hook(member, callback) }
            .onSuccess { unhooks += it }
            .onFailure { logger("安装 hook 失败: ${member.name}", it) }
    }

    private fun log(message: String, throwable: Throwable?) {
        if (throwable == null) {
            XposedBridge.log("$TAG $message")
        } else {
            HLog.e("$TAG $message", throwable)
        }
    }

    private companion object {
        private const val TAG = "[Hchat:CustomNickname]"
        private const val DIAG_LIMIT = 6
    }
}