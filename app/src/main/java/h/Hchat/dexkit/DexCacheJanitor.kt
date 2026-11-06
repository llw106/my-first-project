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
 * 为什么不主动清 `Hchat_dex_cache`：
 * 那是 DexFinder 的总缓存，体积大、覆盖面广。它内部有 runtimeKey 校验，
 * 包体一变自然失效；主动清空会让 20 多个功能在启动后各自重新扫一遍，
 * 反而制造卡顿。所以留给它自己按需重建。
 */
object DexCacheJanitor {

    private const val TAG = "[Hchat:CacheJanitor]"

    private const val STATE_PREFS = "Hchat_cache_janitor"
    private const val KEY_RUNTIME = "runtime.key"

    /** 精确名单：体积小、每次包体变化都必然失效的定位缓存。 */
    private val EXACT = listOf(
        "Hchat_settings_dex_cache",
        "Hchat_my_tab_dex_cache",
        "Hchat_extra_class_cache",
        "Hchat_extra_method_cache"
    )

    /** 通用规则：各功能自己的方法定位缓存。 */
    private const val METHOD_CACHE_SUFFIX = "_method_cache"

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
     */
    fun install(context: Context, classLoader: ClassLoader) {
        runCatching {
            val runtime = WeChatVersionApi.buildCacheKey(context, classLoader)
            if (runtime.isEmpty()) return

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
            HLog.i("$TAG 微信包体变化，已清理 $cleared 个定位缓存（上次=${last.ifEmpty { "无" }}）")
        }.onFailure { HLog.e("$TAG 清理失败: ${it.message}", it) }
    }

    /** 扫 shared_prefs 目录，按白名单挑出定位缓存（只读目录，不猜名字）。 */
    private fun locatorCacheNames(context: Context): List<String> {
        val dir = runCatching {
            java.io.File(context.applicationInfo.dataDir, "shared_prefs")
        }.getOrNull() ?: return emptyList()
        val files = dir.listFiles() ?: return emptyList()
        return files.asSequence()
            .filter { it.isFile && it.name.endsWith(".xml") }
            .map { it.name.removeSuffix(".xml") }
            .filter { isLocatorCache(it) }
            .toList()
    }

    private fun isLocatorCache(name: String): Boolean {
        if (!name.startsWith(PREFIX)) return false
        if (BUSINESS.contains(name)) return false
        if (EXACT.contains(name)) return true
        return name.endsWith(METHOD_CACHE_SUFFIX)
    }
}