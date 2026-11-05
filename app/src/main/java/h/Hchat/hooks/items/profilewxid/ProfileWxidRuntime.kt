package h.Hchat.hooks.items.profilewxid

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
 * 资料页微信号的显示层替换。
 *
 * 不再依赖 ContactInfoUI 这种会被换名/换 ClassLoader 的界面类，
 * 而是挂在 TextView#setText 上：当文本是已配置好友的真实微信号（或「微信号：xxx」）时换成自定义值。
 */
class ProfileWxidRuntime(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {

    private val unhooks = ArrayList<XC_MethodHook.Unhook>()
    private var installed = false

    @Volatile
    private var aliasMap: Map<String, String> = emptyMap()

    @Volatile
    private var builtAt = 0L

    private var diagCount = 0

    fun install() {
        if (installed) return
        installed = true
        installTextViewHook()
    }

    fun refresh() {
        builtAt = 0L
    }

    fun destroy() {
        unhooks.forEach { runCatching { it.unhook() } }
        unhooks.clear()
        installed = false
        aliasMap = emptyMap()
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
                    .onFailure { logger("替换微信号失败", it) }
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

        // 情况一：整段就是「微信号：xxx」/「微信号:xxx」/「微信号 xxx」
        val labelIndex = raw.indexOf(LABEL)
        if (labelIndex >= 0) {
            for ((alias, custom) in map) {
                if (raw.contains(alias)) {
                    param.args[0] = raw.replace(alias, custom)
                    if (diagCount < DIAG_LIMIT) {
                        diagCount++
                        log("替换带标签文本 ${raw.take(40)} → ${custom}", null)
                    }
                    return
                }
            }
            return
        }

        // 情况二：整段就是微信号本身（资料页把标签和值分成两个 TextView）
        if (raw.length < MIN_ALIAS_LENGTH) return
        map[raw]?.let { custom ->
            param.args[0] = custom
            if (diagCount < DIAG_LIMIT) {
                diagCount++
                log("替换纯微信号 $raw → $custom", null)
            }
        }
    }

    private fun currentMap(): Map<String, String> {
        val now = SystemClock.elapsedRealtime()
        if (now - builtAt < REFRESH_INTERVAL_MS) return aliasMap
        builtAt = now
        val host = context.hostContext() ?: return aliasMap
        if (!ProfileWxidSettings.enabled(host)) {
            aliasMap = emptyMap()
            return aliasMap
        }
        val contacts = WeChatApis.contacts()
        if (contacts == null) {
            aliasMap = emptyMap()
            return aliasMap
        }
        val map = LinkedHashMap<String, String>()
        ProfileWxidSettings.configured(host).forEach { (wxid, custom) ->
            val alias = runCatching { contacts.getContact(wxid)?.customWxId }
                .getOrNull()
                ?.trim()
                .orEmpty()
            if (alias.isNotEmpty() && alias != custom) {
                map[alias] = custom
            }
        }
        aliasMap = map
        if (diagCount < DIAG_LIMIT) {
            diagCount++
            log("微信号映射 ${map.size} 条: ${map.entries.joinToString { "${it.key}→${it.value}" }.take(300)}", null)
        }
        return aliasMap
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
        private const val TAG = "[Hchat:ProfileWxid]"
        private const val LABEL = "微信号"
        private const val REFRESH_INTERVAL_MS = 2000L
        private const val MIN_ALIAS_LENGTH = 4
        private const val DIAG_LIMIT = 3
    }
}