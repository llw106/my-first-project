package h.Hchat.hooks.items.profilewxid

import android.os.SystemClock
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.TextViewRewriteHub
import h.Hchat.utils.HLog

/**
 * 资料页微信号的显示层替换。
 *
 * 复用 TextViewRewriteHub 的 setText 分发，不额外装 hook；
 * 没有配置时在 toString() 之前返回，零分配。
 */
class ProfileWxidRuntime(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) : TextViewRewriteHub.Rewriter {

    private var installed = false

    /** 真实微信号 → 自定义显示值 */
    @Volatile
    private var aliasMap: Map<String, String> = emptyMap()

    @Volatile
    private var builtAt = 0L

    fun install() {
        if (installed) return
        installed = true
        val active = TextViewRewriteHub.register(this)
        log(if (active) "文本改写已注册" else "非界面进程，跳过文本改写", null)
    }

    /** 设置页保存后调用，让映射立即重建。 */
    fun refresh() {
        builtAt = 0L
    }

    fun destroy() {
        TextViewRewriteHub.unregister(this)
        installed = false
        aliasMap = emptyMap()
        builtAt = 0L
    }

    override fun rewrite(param: XC_MethodHook.MethodHookParam) {
        refreshIfNeeded()
        val map = aliasMap
        if (map.isEmpty()) return

        val source = param.args.getOrNull(0) as? CharSequence ?: return
        val raw = source.toString()
        if (raw.isBlank()) return

        // 情况一：整段是「微信号：xxx」/「微信号:xxx」
        if (raw.contains(LABEL)) {
            for ((alias, custom) in map) {
                if (alias.isEmpty() || alias == custom) continue
                if (!raw.contains(alias)) continue
                param.args[0] = raw.replace(alias, custom)
                return
            }
            return
        }

        // 情况二：整段就是微信号本身（标签与值分成两个 TextView）
        if (raw.length < MIN_ALIAS_LENGTH) return
        map[raw]?.let { custom ->
            param.args[0] = custom
        }
    }

    private fun refreshIfNeeded() {
        val now = SystemClock.elapsedRealtime()
        if (now - builtAt < REFRESH_INTERVAL_MS) return
        builtAt = now
        runCatching { build() }
            .onFailure { logger("重建微信号映射失败", it) }
    }

    private fun build() {
        val host = context.hostContext()
        if (host == null || !ProfileWxidSettings.enabled(host)) {
            aliasMap = emptyMap()
            return
        }
        val contacts = WeChatApis.contacts()
        if (contacts == null) {
            aliasMap = emptyMap()
            return
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
    }
}