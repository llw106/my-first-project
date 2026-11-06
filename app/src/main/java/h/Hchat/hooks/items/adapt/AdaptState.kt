package h.Hchat.hooks.items.adapt

import android.content.Context
import h.Hchat.hooks.api.runtime.WeChatVersionApi
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.ModuleIdentity

/**
 * 「装完就得重新适配」的状态记录。
 *
 * 判定 key = 微信包体指纹（WeChatVersionApi.buildCacheKey）+ Hchat 自己的安装指纹
 * （ModuleIdentity.tag）。两个信号任一变化，上一版的适配记录就作废：
 *   · 微信装/升级/降级 → 包体指纹变
 *   · Hchat 换包（哪怕版本号没动） → 模块安装指纹变
 */
object AdaptState {

    const val PREFS = "Hchat_adapt_state"

    private const val KEY_ADAPTED = "adapted.key"
    private const val KEY_ADAPTED_AT = "adapted.at"
    private const val KEY_VERSION = "adapted.version"
    private const val KEY_SUMMARY = "adapted.summary"

    fun prefs(context: Context) = HchatStorage.preferences(context, PREFS)

    fun runtimeKey(context: Context, classLoader: ClassLoader): String {
        val base = runCatching { WeChatVersionApi.buildCacheKey(context, classLoader) }.getOrDefault("")
        if (base.isEmpty()) return base
        return "$base|hchat=${ModuleIdentity.tag(context)}"
    }

    fun adaptedKey(context: Context): String =
        prefs(context).getString(KEY_ADAPTED, "").orEmpty()

    /** 当前这个微信包体是否已经点过「适配」。 */
    fun isAdapted(context: Context, classLoader: ClassLoader): Boolean {
        val runtime = runtimeKey(context, classLoader)
        if (runtime.isEmpty()) return true
        return runtime == adaptedKey(context)
    }

    fun markAdapted(
        context: Context,
        classLoader: ClassLoader,
        versionName: String,
        summary: String = ""
    ) {
        val runtime = runtimeKey(context, classLoader)
        if (runtime.isEmpty()) return
        prefs(context).edit()
            .putString(KEY_ADAPTED, runtime)
            .putString(KEY_VERSION, versionName)
            .putString(KEY_SUMMARY, summary)
            .putLong(KEY_ADAPTED_AT, System.currentTimeMillis())
            .apply()
    }

    /** 上一次扫描的时间（毫秒），没扫过是 0。 */
    fun adaptedAt(context: Context): Long =
        prefs(context).getLong(KEY_ADAPTED_AT, 0L)

    /** 上一次扫描的结果摘要，例如「共 43 项，1 项未命中：我页条目」。 */
    fun adaptedSummary(context: Context): String =
        prefs(context).getString(KEY_SUMMARY, "").orEmpty()

    /** 微信 APK 的修改时间，用来在弹窗里显示「哪一次安装」。 */
    fun installedAt(context: Context): Long = runCatching {
        java.io.File(context.packageManager.getApplicationInfo(context.packageName, 0).sourceDir).lastModified()
    }.getOrDefault(0L)

    /** 「版本 8.0.80（3220）\n安装于 09-30 12:31」这样的一行描述，弹窗和设置页共用。 */
    fun describe(context: Context): String {
        val info = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val versionName = info?.versionName ?: "?"
        val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
            runCatching { info?.longVersionCode }.getOrNull() ?: 0L
        } else {
            @Suppress("DEPRECATION")
            runCatching { info?.versionCode?.toLong() }.getOrNull() ?: 0L
        }
        val installedText = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
            .format(java.util.Date(installedAt(context)))
        return "版本 $versionName（$versionCode）\n安装于 $installedText"
    }

    /** 微信版本名，适配状态里一起存下来，方便排查「哪一版适配过」。 */
    fun versionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty()
}
