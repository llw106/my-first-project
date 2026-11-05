package h.Hchat.hooks.items.customnickname

import android.os.SystemClock
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import h.Hchat.hooks.api.core.WeChatApis
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.TextViewRewriteHub
import h.Hchat.utils.HLog

/**
 * 只改显示层：把界面上出现的「原显示名 / 原昵称」换成自定义昵称。
 *
 * 文本改写统一走 TextViewRewriteHub（全模块共用一个 setText hook），
 * 无配置时在 toString() 之前就返回，不产生额外对象。
 */
class CustomNicknameRuntime(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) : TextViewRewriteHub.Rewriter {

    private var installed = false

    /** 整段可替换的键：显示名 / 昵称 / 备注 */
    @Volatile
    private var exactMap: Map<String, String> = emptyMap()

    /** 只在「昵称：xxx」这类带标签文本里替换的短词 */
    @Volatile
    private var tokenList: List<Pair<String, String>> = emptyList()

    /** 归一化后的整段映射，兼容全角括号与多余空格 */
    @Volatile
    private var normMap: Map<String, String> = emptyMap()

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
        clear()
        builtAt = 0L
    }

    override fun rewrite(param: XC_MethodHook.MethodHookParam) {
        refreshIfNeeded()
        if (exactMap.isEmpty() && tokenList.isEmpty()) return

        val source = param.args.getOrNull(0) as? CharSequence ?: return
        val raw = source.toString()
        if (raw.isBlank()) return

        exactMap[raw]?.let { custom ->
            param.args[0] = custom
            return
        }

        normMap[normalize(raw)]?.let { custom ->
            param.args[0] = custom
            return
        }

        if (raw.contains(LABEL)) {
            for ((token, custom) in tokenList) {
                if (token.isEmpty() || token == custom) continue
                if (!raw.contains(token)) continue
                param.args[0] = raw.replace(token, custom)
                return
            }
            return
        }

        for ((name, custom) in exactMap) {
            if (name.length < MIN_NAME_LENGTH) continue
            if (raw.length <= name.length) continue
            if (raw.length > name.length + MAX_EXTRA) continue
            if (!raw.contains(name)) continue
            param.args[0] = raw.replace(name, custom)
            return
        }
    }

    private fun refreshIfNeeded() {
        val now = SystemClock.elapsedRealtime()
        if (now - builtAt < REFRESH_INTERVAL_MS) return
        builtAt = now
        runCatching { build() }
            .onFailure { logger("重建昵称映射失败", it) }
    }

    private fun build() {
        val host = context.hostContext()
        if (host == null) {
            clear()
            return
        }
        if (!CustomNicknameSettings.enabled(host) || !anyScopeEnabled(host)) {
            clear()
            return
        }
        val contacts = WeChatApis.contacts()
        if (contacts == null) {
            clear()
            return
        }
        val exact = LinkedHashMap<String, String>()
        val tokens = ArrayList<Pair<String, String>>()
        CustomNicknameSettings.configured(host).forEach { (wxid, custom) ->
            val contact = runCatching { contacts.getContact(wxid) }.getOrNull()
            val display = runCatching { contacts.getDisplayName(wxid) }
                .getOrNull()
                ?.trim()
                .orEmpty()
            val nickname = contact?.nickname?.trim().orEmpty()
            val remark = contact?.remarkName?.trim().orEmpty()
            if (display.isNotEmpty() && display != custom) exact[display] = custom
            if (nickname.isNotEmpty() && nickname != custom) {
                exact[nickname] = custom
                tokens += nickname to custom
            }
            if (remark.isNotEmpty() && remark != custom) {
                exact[remark] = custom
                tokens += remark to custom
            }
        }
        val norm = LinkedHashMap<String, String>()
        exact.forEach { (key, value) ->
            val normalized = normalize(key)
            if (normalized.isNotEmpty()) norm[normalized] = value
        }
        exactMap = exact
        tokenList = tokens
        normMap = norm
    }

    private fun clear() {
        exactMap = emptyMap()
        tokenList = emptyList()
        normMap = emptyMap()
    }

    private fun anyScopeEnabled(host: android.content.Context): Boolean {
        return listOf(
            NicknameSurface.CONVERSATION,
            NicknameSurface.CHAT,
            NicknameSurface.GROUP_MEMBER
        ).any { CustomNicknameSettings.scopeEnabled(host, it) }
    }

    private fun normalize(text: String): String =
        text.replace(" ", "").replace("\u3000", "").replace("（", "(").replace("）", ")").trim()

    private fun log(message: String, throwable: Throwable?) {
        if (throwable == null) {
            XposedBridge.log("$TAG $message")
        } else {
            HLog.e("$TAG $message", throwable)
        }
    }

    private companion object {
        private const val TAG = "[Hchat:CustomNickname]"
        private const val LABEL = "昵称"
        private const val REFRESH_INTERVAL_MS = 2000L
        private const val MAX_EXTRA = 12
        private const val MIN_NAME_LENGTH = 4
    }
}