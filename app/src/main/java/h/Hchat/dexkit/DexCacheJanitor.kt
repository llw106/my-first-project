package h.Hchat.dexkit

import android.content.Context
import h.Hchat.hooks.api.runtime.WeChatVersionApi
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog

/**
 * 微信包体变化后清理「定位缓存」。
 *
 * 背景：模块里有 50 多个 Hchat_* 的 SharedPreferences，里面混了两类东西——
 *
 *   · 定位缓存：DexKit 扫出来的类名/方法描述符（微信一换版本就全部作废）
 *   · 业务数据：假转发记录、朋友圈缓存帖、群成员缓存等（用户数据，绝不能清）
 *
 * 每个 finder 读取时都会比对 runtimeKey，不匹配就当没有，所以旧缓存不会
 * 被误用；但它会一直占着磁盘，也会让一些「部分完成」标记继续留在一个已经
 * 不存在的包体上。这里在模块初始化最开头统一清一次，用白名单，绝不动业务数据。
 *
 * 注意：Hchat 的配置不是 Android SharedPreferences，而是 FastKV（文件落在
 * `dataDir/Hchat/name.kva`）。必须走 HchatStorage 拿实例；扫
 * `shared_prefs` 目录里的 xml 是扫不到的——之前扫错了目录，日志永远是「已清理 0 个」。
 *
 * 为什么连 `Hchat_dex_cache`（DexFinder 总缓存）也清：
 * `resolveAll()` 第一件事就是 `if (loadCache()) { resolvedAll = true; return; }`，
 * 缓存还在的话「安装后全盘扫描」会直接秒退，等于没扫。清完紧接着由用户点
 * 「立即适配」做一次完整重扫并写回，比让 20 多个功能各自补扫更省。
 */
object DexCacheJanitor {

    private const val TAG = "[Hchat:CacheJanitor]"

    private const val STATE_PREFS = "Hchat_cache_janitor"
    private const val KEY_RUNTIME = "runtime.key"

    /** FastKV 的落盘后缀，一个配置可能同时有多个。 */
    private val SUFFIXES = listOf(".kva", ".kvb", ".kvc")

    /** 精确名单：体积小、每次包体变化都必然失效的定位缓存。 */
    private val EXACT = listOf(
        "Hchat_dex_cache",
        "Hchat_settings_dex_cache",
        "Hchat_my_tab_dex_cache",
        "Hchat_extra_class_cache",
        "Hchat_extra_method_cache"
    )

    /** 通用规则：各功能自己的方法定位缓存。 */
    private const val METHOD_CACHE_SUFFIX = "_method_cache"

    /** 通用规则：按类名定位的缓存（如 Hchat_transparent_avatar_class_cache）。 */
    private const val CLASS_CACHE_SUFFIX = "_class_cache"

    /** 明确不碰的业务数据（即使名字撞上白名单也不清）。 */
    private val BUSINESS = listOf(
        "Hchat_sns_cached_post_storage_cache",
        "Hchat_moments_fake_forward_cache",
        "Hchat_conversation_group_home_cache",
        "Hchat_fake_mini_program_host_version_member_cache",
        "Hchat_wechat_tablet_cache",
        "Hchat_sns_live_photo_upload_cache",
        "Hchat_sns_post_observer_cache",
        "Hchat_sns_fake_interaction_cache"
    )

    private const val PREFIX = "Hchat_"

    /**
     * 在模块初始化最前面调用。包体没变时是空操作（一次字符串比较）。
     *
     * 判定用「微信包体指纹 + Hchat 自身版本/安装时间」，所以下面两种情况都会触发清理：
     *   · 微信被重装/升级/降级
     *   · Hchat 自己出了新包
     */
    fun install(context: Context, classLoader: ClassLoader, moduleContext: Context? = null) {
        runCatching {
            val wechatKey = WeChatVersionApi.buildCacheKey(context, classLoader)
            if (wechatKey.isEmpty()) return

            val hchatTag = moduleTag(moduleContext)
            val runtime = "$wechatKey|hchat=$hchatTag"

            val state = HchatStorage.preferences(context, STATE_PREFS)
            val last = state.getString(KEY_RUNTIME, "").orEmpty()
            if (last == runtime) return

            val names = locatorCacheNames(context)
            var cleared = 0
            for (name in names) {
                val ok = runCatching {
                    HchatStorage.preferences(context, name).edit().clear().commit()
                }.getOrDefault(false)
                if (ok) cleared++
            }
            state.edit().putString(KEY_RUNTIME, runtime).apply()
            HLog.i("$TAG 包体或模块版本变化，已清理 $cleared 个定位缓存（hchat=$hchatTag）")
        }.onFailure { HLog.e("$TAG 清理失败: ${it.message}", it) }
    }

    /** 模块自身的「版本名/安装时间」，用来感知 Hchat 自己换了包。 */
    private fun moduleTag(moduleContext: Context?): String {
        if (moduleContext == null) return ""
        return runCatching {
            val info = moduleContext.packageManager.getPackageInfo(moduleContext.packageName, 0)
            val installed = info.applicationInfo?.let { java.io.File(it.sourceDir).lastModified() } ?: 0L
            "${info.versionName.orEmpty()}/$installed"
        }.getOrDefault("")
    }

    /** 扫 FastKV 目录（dataDir/Hchat），按白名单挑出定位缓存；不猜名字，只认落盘文件。 */
    private fun locatorCacheNames(context: Context): List<String> {
        val dir = runCatching { HchatStorage.storageDir(context) }.getOrNull() ?: return emptyList()
        val files = dir.listFiles() ?: return emptyList()
        return files.asSequence()
            .filter { it.isFile }
            .mapNotNull { file ->
                val suffix = SUFFIXES.firstOrNull { file.name.endsWith(it) } ?: return@mapNotNull null
                file.name.removeSuffix(suffix)
            }
            .filter { isLocatorCache(it) }
            .distinct()
            .toList()
    }

    private fun isLocatorCache(name: String): Boolean {
        if (!name.startsWith(PREFIX)) return false
        if (BUSINESS.contains(name)) return false
        if (EXACT.contains(name)) return true
        return name.endsWith(METHOD_CACHE_SUFFIX) || name.endsWith(CLASS_CACHE_SUFFIX)
    }
}