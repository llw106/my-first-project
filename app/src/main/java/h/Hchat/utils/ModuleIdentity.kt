package h.Hchat.utils

import android.content.Context
import h.Hchat.BuildConfig
import java.io.File

/**
 * Hchat 自己的「这一次安装」指纹。
 *
 * 用途：微信包体没变、但 Hchat 换了新包时，上一版扫出来的 DexKit 定位缓存
 * 必须作废（finder 逻辑可能变了），适配弹窗也要重新弹一次。
 *
 * 为什么不用 `createPackageContext("h.Hchat", ...)`：
 * LSPosed 会把模块包从宿主进程的 PackageManager 视图里隐藏，那条路在微信
 * 进程里直接抛 NameNotFoundException —— 之前日志里 `hchat=` 一直是空、
 * 而且重装 Hchat 不重新弹窗，都是这个原因。
 *
 * 所以这里按「越接近真实安装时间越优先」的顺序探测，拿到哪个算哪个：
 *   1. getPackageInfo(模块包).lastUpdateTime —— 每次安装都变；
 *   2. getApplicationInfo(模块包).sourceDir 的大小/mtime —— 装一次换一个目录；
 *   3. 模块类加载器里的 AndroidManifest.xml 资源 URL → 模块 APK 的大小/mtime；
 *   4. 编译期写死的 BUILD_TIME —— 每次构建都不同，保证「新包一定重新提示」。
 *
 * 结果每个进程只算一次并缓存，日志里会打出命中的来源，方便排查。
 */
object ModuleIdentity {

    private const val TAG = "[Hchat:ModuleId]"

    const val MODULE_PACKAGE = "h.Hchat"

    @Volatile
    private var cached: String? = null

    fun tag(context: Context): String {
        cached?.let { return it }
        val value = compute(context)
        cached = value
        return value
    }

    private fun compute(context: Context): String {
        val parts = ArrayList<String>(3)

        // 4. 兜底：每次构建都变，所以新包一定和上一版的 key 不同。
        parts += "${BuildConfig.VERSION_NAME}@${BuildConfig.BUILD_TIME}"

        // 1 + 2. PackageManager（模块包被隐藏时会失败，失败就走下面两条）。
        runCatching {
            val pm = context.packageManager
            val info = pm.getPackageInfo(MODULE_PACKAGE, 0)
            val apk = runCatching { pm.getApplicationInfo(MODULE_PACKAGE, 0).sourceDir }.getOrNull()
            val file = apk?.let {
                val f = File(it)
                "/${f.length()}/${f.lastModified()}"
            }.orEmpty()
            parts += "pm=${info.lastUpdateTime}$file"
        }.onFailure {
            HLog.i("$TAG PackageManager 查不到模块包（${it.javaClass.simpleName}），改用其他来源")
        }

        // 3. 绕开 PackageManager，直接从模块自己的类加载器反查 APK 路径。
        moduleApkFromClassLoader()?.let { path ->
            runCatching {
                val f = File(path)
                parts += "cl=${f.length()}/${f.lastModified()}"
            }
        }

        val tag = parts.joinToString("|")
        HLog.once("moduleIdentity", "$TAG 本次安装指纹=$tag")
        return tag
    }

    /** 模块类加载器里的 AndroidManifest.xml → 模块 APK 路径；拿不到就是 null。 */
    private fun moduleApkFromClassLoader(): String? = runCatching {
        val url = ModuleIdentity::class.java.classLoader
            ?.getResource("AndroidManifest.xml")
            ?.toString()
            ?: return null
        val path = url.removePrefix("jar:").removePrefix("file:").substringBefore("!")
        // 路径里必须带模块目录名，否则说明拿到的是宿主 APK，丢弃。
        if (!path.contains(MODULE_PACKAGE)) return null
        path
    }.getOrNull()
}
