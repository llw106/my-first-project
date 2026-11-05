package h.Hchat.hooks.items.customnickname

import android.text.SpannableStringBuilder
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.utils.HLog
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

    fun install() {
        if (installed) return
        installed = true
        installConversationTitle()
    }

    fun refresh() {
        // 标题在下一次列表刷新时重新生成，这里不需要额外触发。
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
        val method = adapterClass.declaredMethods.firstOrNull { candidate ->
            candidate.name == "G" &&
                candidate.parameterTypes.size == 4 &&
                candidate.returnType == CharSequence::class.java &&
                candidate.parameterTypes[1] == Int::class.javaPrimitiveType &&
                candidate.parameterTypes[3] == Boolean::class.javaPrimitiveType
        }
        if (method == null) {
            log("未找到会话标题生成方法，adapter=${adapterClass.name}", null)
            return
        }
        hook(method, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                runCatching { rewriteTitle(param) }
                    .onFailure { logger("替换会话标题失败", it) }
            }
        })
        log("会话标题 hook 已安装: ${adapterClass.name}#${method.name}", null)
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
        val current = runCatching { WeChatApis.users()?.displayName(username) }
            .getOrNull()
            ?.trim()
            .orEmpty()
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

    private fun usernameOf(conversation: Any): String? {
        val method = conversation.javaClass.methods.firstOrNull {
            it.name == "h1" && it.parameterCount == 0 && it.returnType == String::class.java
        } ?: return null
        return runCatching { method.invoke(conversation) as? String }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
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
    }
}