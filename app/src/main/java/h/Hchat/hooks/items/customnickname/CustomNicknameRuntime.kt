package h.Hchat.hooks.items.customnickname

import android.os.SystemClock
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.utils.HLog
import java.lang.reflect.Method

/**
 * 只改显示层：把界面上出现的「原显示名」换成自定义昵称。
 *
 * 不依赖任何微信内部类名（会话列表 adapter 在不同版本/不同 ClassLoader 下会变），
 * 而是挂在 android.widget.TextView#setText 上做文本替换：
 * 微信把标题设置给 TextView 时，文本必然经过这里。
 */
class CustomNicknameRuntime(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {

    private val unhooks = ArrayList<XC_MethodHook.Unhook>()
    private var installed = false

    @Volatile
    private var nameMap: Map<String, String> = emptyMap()

    @Volatile
    private var builtAt = 0L

    fun install() {
        if (installed) return
        installed = true
        installTextViewHook()
    }

    /** 设置页保存后调用，让映射立即重建。 */
    fun refresh() {
        builtAt = 0L
    }

    fun destroy() {
        unhooks.forEach { runCatching { it.unhook() } }
        unhooks.clear()
        installed = false
        nameMap = emptyMap()
        builtAt = 0L
    }

    private fun installTextViewHook() {
        val setText = runCatching {
            TextView::class.java.getDeclaredMethod(
                "setText",
                CharSequence::class.java,
                TextView.BufferType::class.java
            )
        }.getOrNull()
        if (setText == null) {
            log("未找到 TextView#setText", null)
            return
        }
        hook(setText, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                runCatching { rewrite(param) }
                    .onFailure { logger("替换自定义昵称失败", it) }
            }
        })
        log("TextView#setText hook 已安装", null)
    }

    private fun rewrite(param: XC_MethodHook.MethodHookParam) {
        val source = param.args.getOrNull(0) as? CharSequence ?: return
        val raw = source.toString().trim()
        if (raw.isEmpty()) return
        val map = currentMap()
        if (map.isEmpty()) return
        map[raw]?.let { custom ->
            param.args[0] = custom
            return
        }
        if (raw.length > MAX_EXTRA) return
        for ((name, custom) in map) {
            if (raw.length <= name.length) continue
            if (raw.length > name.length + MAX_EXTRA) continue
            if (!raw.contains(name)) continue
            param.args[0] = raw.replace(name, custom)
            return
        }
    }

    private fun currentMap(): Map<String, String> {
        val now = SystemClock.elapsedRealtime()
        if (now - builtAt < REFRESH_INTERVAL_MS) return nameMap
        builtAt = now
        val host = context.hostContext() ?: return nameMap
        if (!CustomNicknameSettings.enabled(host) || !anyScopeEnabled(host)) {
            nameMap = emptyMap()
            return nameMap
        }
        val contacts = WeChatApis.contacts()
        if (contacts == null) {
            nameMap = emptyMap()
            return nameMap
        }
        val map = LinkedHashMap<String, String>()
        CustomNicknameSettings.configured(host).forEach { (wxid, custom) ->
            val name = runCatching { contacts.getDisplayName(wxid) }
                .getOrNull()
                ?.trim()
                .orEmpty()
            if (name.isNotEmpty() && name != custom) {
                map[name] = custom
            }
        }
        nameMap = map
        if (diagCount < DIAG_LIMIT) {
            diagCount++
            log("昵称映射 ${map.size} 条: ${map.entries.joinToString { "${it.key}→${it.value}" }.take(300)}", null)
        }
        return nameMap
    }

    private fun anyScopeEnabled(host: android.content.Context): Boolean {
        return listOf(
            NicknameSurface.CONVERSATION,
            NicknameSurface.CHAT,
            NicknameSurface.GROUP_MEMBER
        ).any { CustomNicknameSettings.scopeEnabled(host, it) }
    }

    private var diagCount = 0

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
        private const val REFRESH_INTERVAL_MS = 2000L
        private const val MAX_EXTRA = 12
        private const val DIAG_LIMIT = 3
    }
}