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
 *
 * 支持三种形态：
 *  1) 整段等于显示名（会话列表标题）   杨善邦 (某某年) → 明年
 *  2) 整段等于昵称 / 备注
 *  3) 带标签文本（资料页）             昵称:某某年 → 昵称:明年
 */
class CustomNicknameRuntime(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {

    private val unhooks = ArrayList<XC_MethodHook.Unhook>()
    private var installed = false

    /** 可整段替换的键：显示名 / 昵称 / 备注 */
    @Volatile
    private var exactMap: Map<String, String> = emptyMap()

    /** 只允许在「昵称：xxx」这类带标签文本里替换的短词（昵称、备注） */
    @Volatile
    private var tokenList: List<Pair<String, String>> = emptyList()

    /** 归一化（去空格、括号统一）后的整段映射，兼容全角/半角差异 */
    @Volatile
    private var normMap: Map<String, String> = emptyMap()

    @Volatile
    private var builtAt = 0L

    private var diagCount = 0
    private var probeCount = 0
    private var hitCount = 0

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
        exactMap = emptyMap()
        tokenList = emptyList()
        normMap = emptyMap()
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

        // 1) 整段替换
        exact[raw]?.let { custom ->
            apply(param, custom, raw)
            return
        }

        // 1.5) 归一化后整段相等（兼容全角括号、多的空格）
        normMap[normalize(raw)]?.let { custom ->
            apply(param, custom, raw)
            return
        }

        // 2) 带标签文本：昵称:xxx / 昵称：xxx / 微信号旁那种
        if (raw.contains(LABEL)) {
            for ((token, custom) in tokens) {
                if (token.isEmpty() || token == custom) continue
                if (!raw.contains(token)) continue
                apply(param, raw.replace(token, custom), raw)
                return
            }
            probe("带标签未命中", raw)
            return
        }

        // 3) 显示名嵌在更长的文本里（例如 "杨善邦 (某某年):你好"）
        for ((name, custom) in exact) {
            if (name.length < MIN_NAME_LENGTH) continue
            if (raw.length <= name.length) continue
            if (raw.length > name.length + MAX_EXTRA) continue
            if (!raw.contains(name)) continue
            apply(param, raw.replace(name, custom), raw)
            return
        }
        if (raw.length <= PROBE_MAX_LENGTH) probe("短文本未命中", raw)
    }

    private fun apply(param: XC_MethodHook.MethodHookParam, value: String, raw: String) {
        if (value == raw) return
        param.args[0] = value
        if (hitCount < HIT_LIMIT) {
            hitCount++
            log("改写: ${raw.take(30)} → ${value.take(30)}", null)
        }
    }

    private fun probe(kind: String, raw: String) {
        if (probeCount >= PROBE_LIMIT) return
        probeCount++
        log("探针[$kind]: ${raw.take(60)}", null)
    }

    private fun currentMap(): Map<String, String> {
        val now = SystemClock.elapsedRealtime()
        if (now - builtAt < REFRESH_INTERVAL_MS) return exactMap
        builtAt = now
        val host = context.hostContext()
        if (host == null) {
            exactMap = emptyMap()
            tokenList = emptyList()
            normMap = emptyMap()
            return exactMap
        }
        if (!CustomNicknameSettings.enabled(host) || !anyScopeEnabled(host)) {
            exactMap = emptyMap()
            tokenList = emptyList()
            normMap = emptyMap()
            if (diagCount < DIAG_LIMIT) {
                diagCount++
                log("未启用自定义昵称或作用范围全关，跳过", null)
            }
            return exactMap
        }
        val contacts = WeChatApis.contacts()
        if (contacts == null) {
            exactMap = emptyMap()
            tokenList = emptyList()
            normMap = emptyMap()
            if (diagCount < DIAG_LIMIT) {
                diagCount++
                log("联系人 API 不可用", null)
            }
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
            if (diagCount < DIAG_LIMIT) {
                diagCount++
                log(
                    "解析 wxid=$wxid 显示名=${display.ifEmpty { "<空>" }} 昵称=${nickname.ifEmpty { "<空>" }} 备注=${remark.ifEmpty { "<空>" }} 自定义=$custom",
                    null
                )
            }
        }
        exactMap = exact
        tokenList = tokens
        val norm = LinkedHashMap<String, String>()
        exact.forEach { (k, v) ->
            val n = normalize(k)
            if (n.isNotEmpty()) norm[n] = v
        }
        normMap = norm
        if (diagCount < DIAG_LIMIT) {
            diagCount++
            log(
                "昵称映射 ${exact.size} 条: ${exact.entries.joinToString { "${it.key}→${it.value}" }.take(300)}",
                null
            )
        }
        return exactMap
    }

    private fun normalize(text: String): String =
        text.replace(" ", "").replace("　", "").replace("（", "(").replace("）", ")").trim()

    private fun anyScopeEnabled(host: android.content.Context): Boolean {
        return listOf(
            NicknameSurface.CONVERSATION,
            NicknameSurface.CHAT,
            NicknameSurface.GROUP_MEMBER
        ).any { CustomNicknameSettings.scopeEnabled(host, it) }
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
        private const val LABEL = "昵称"
        private const val REFRESH_INTERVAL_MS = 2000L
        private const val MAX_EXTRA = 12
        private const val MIN_NAME_LENGTH = 4
        private const val PROBE_MAX_LENGTH = 20
        private const val PROBE_LIMIT = 8
        private const val HIT_LIMIT = 8
        private const val DIAG_LIMIT = 6
    }
}
