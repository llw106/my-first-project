package h.Hchat.hooks.items.adapt

import android.content.Context
import h.Hchat.hooks.api.runtime.WeChatVersionApi
import h.Hchat.preferences.HchatStorage

/**
 * 「微信包体变了就要重新适配」的状态记录。
 *
 * 判定用的是 WeChatVersionApi.buildCacheKey，它由
 *   包名|版本名|版本号|clientVersion|tinkerId|patchId|APK 修改时间|ClassLoader 指纹
 * 拼成。其中「APK 修改时间」在每次安装/更新/降级后都会变，所以
 * 「微信每装一次就当成新版本要重新适配」这件事是天然满足的。
 */
object AdaptState {

    const val PREFS = "Hchat_adapt_state"

    private const val KEY_ADAPTED = "adapted.key"
    private const val KEY_ADAPTED_AT = "adapted.at"
    private const val KEY_VERSION = "adapted.version"

    /** 由 AdaptPrompt 安装时注入的「Hchat 自身身份」，让适配状态随模块换包一起失效。 */
    @Volatile
    var moduleTag: String = ""

    fun prefs(context: Context) = HchatStorage.preferences(context, PREFS)

    /** Hchat 自己的「版本名/安装时间」，换包即变。 */
    fun hchatTag(moduleContext: Context?): String {
        if (moduleContext == null) return ""
        return runCatching {
            val info = moduleContext.packageManager.getPackageInfo(moduleContext.packageName, 0)
            val installed = info.applicationInfo?.let { java.io.File(it.sourceDir).lastModified() } ?: 0L
            "${info.versionName.orEmpty()}/$installed"
        }.getOrDefault("")
    }

    fun runtimeKey(context: Context, classLoader: ClassLoader): String {
        val base = runCatching { WeChatVersionApi.buildCacheKey(context, classLoader) }.getOrDefault("")
        if (base.isEmpty()) return base
        return "$base|hchat=$moduleTag"
    }

    fun adaptedKey(context: Context): String =
        prefs(context).getString(KEY_ADAPTED, "").orEmpty()

    /** 当前这个微信包体是否已经点过「适配」。 */
    fun isAdapted(context: Context, classLoader: ClassLoader): Boolean {
        val runtime = runtimeKey(context, classLoader)
        if (runtime.isEmpty()) return true
        return runtime == adaptedKey(context)
    }

    fun markAdapted(context: Context, classLoader: ClassLoader, versionName: String) {
        val runtime = runtimeKey(context, classLoader)
        if (runtime.isEmpty()) return
        prefs(context).edit()
            .putString(KEY_ADAPTED, runtime)
            .putString(KEY_VERSION, versionName)
            .putLong(KEY_ADAPTED_AT, System.currentTimeMillis())
            .apply()
    }

    fun adaptedVersion(context: Context): String =
        prefs(context).getString(KEY_VERSION, "").orEmpty()

    /** 微信 APK 的修改时间，用来在弹窗里显示「哪一次安装」。 */
    fun installedAt(context: Context): Long = runCatching {
        java.io.File(context.packageManager.getApplicationInfo(context.packageName, 0).sourceDir).lastModified()
    }.getOrDefault(0L)
}
