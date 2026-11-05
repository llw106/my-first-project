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
 * 只改显示层：把界面上出现的「原显示名 / 原昵称」换成自定义昵称。
 *
 * 不依赖任何微信内部类名，挂在 android.widget.TextView#setText 上做文本替换。
 * 支持三种形态：
 *  1) 整段等于显示名（会话列表标题）：杨善邦 (某某年) → 明年
 *  2) 带标签文本（资料页）：昵称：某某年 → 昵称：明年
 *  3) 显示名嵌在更长文本里（会话摘要等）
 */
class CustomNicknameRuntime(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {

    private val unhooks = ArrayList<XC_MethodHook.Unhook>()
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
        clear()
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
        val raw = source.toString()
        if (raw.isBlank()) return
        currentMap()
        val exact = exactMap
        val tokens = tokenList
        if (exact.isEmpty() && tokens.isEmpty()) return

        exact[raw]?.let { custom ->
            param.args[0] = custom
            return
        }

        normMap[normalize(raw)]?.let { custom ->
            param.args[0] = custom
            return
        }

        if (raw.contains(LABEL)) {
            for ((token, custom) in tokens) {
                if (token.isEmpty() || token == custom) continue
                if (!raw.contains(token)) continue
                param.args[0] = raw.replace(token, custom)
                return
            }
            return
        }

        for ((name, custom) in exact) {
            if (name.length < MIN_NAME_LENGTH) continue
            if (raw.length <= name.length) continue
            if (raw.length > name.length + MAX_EXTRA) continue
            if (!raw.contains(name)) continue
            param.args[0] = raw.replace(name, custom)
            return
        }
    }

    private fun currentMap(): Map<String, String> {
        val now = SystemClock.elapsedRealtime()
        if (now - builtAt < REFRESH_INTERVAL_MS) return exactMap
        builtAt = now
        val host = context.hostContext()
        if (host == null) {
            clear()
            return exactMap
        }
        if (!CustomNicknameSettings.enabled(host) || !anyScopeEnabled(host)) {
            clear()
            return exactMap
        }
        val contacts = WeChatApis.contacts()
        if (contacts == null) {
            clear()
            return exactMap
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
        return exactMap
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
        private const val LABEL = "昵称"
        private const val REFRESH_INTERVAL_MS = 2000L
        private const val MAX_EXTRA = 12
        private const val MIN_NAME_LENGTH = 4
    }
}