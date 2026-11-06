package h.Hchat.hooks.items.moments

import android.content.Context
import android.content.SharedPreferences
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector
import java.io.File
import java.util.HashSet

class RemoveMomentsAdsFeature : BaseFeature() {
    override fun featureId(): String = ID

    override fun name(): String = "去除朋友圈广告"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(RemoveMomentsAdsSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        install(context.hostContext(), context.hostClassLoader())
    }

    companion object {
        const val ID = "remove_moments_ads"
        private const val TAG = "[Hchat:MomentsAds]"
        private const val AD_INFO_CLASS = "com.tencent.mm.plugin.sns.storage.ADInfo"
        private val installedLoaders = HashSet<String>()
        private val hitCount = java.util.concurrent.atomic.AtomicInteger()
        private val stampLock = Any()
        private val FAST_KV_SUFFIXES = arrayOf(".kva", ".kvb", ".kvc")

        @Volatile
        private var lastStamp = Long.MIN_VALUE

        @JvmStatic
        fun isEnabled(context: Context?): Boolean {
            if (context == null) return false
            val app = context.applicationContext ?: context
            return prefsFor(app).getBoolean(
                RemoveMomentsAdsSettings.KEY_ENABLE,
                RemoveMomentsAdsSettings.DEFAULT_ENABLE
            )
        }

        /**
         * 开关配置是 FastKV（mmap）存储，设置页写入后当前进程的缓存实例读不到新值。
         * 这里按配置文件的时间戳判断是否变化，变了就重开，保证"关掉开关立刻停止拦截"。
         */
        private fun prefsFor(context: Context): SharedPreferences {
            val dir = HchatStorage.storageDir(context)
            val name = RemoveMomentsAdsSettings.PREFS_NAME
            // 落盘名是去特征后的 cfg_xxx，不能拿业务名直接拼文件名。
            val disk = HchatStorage.diskFileName(name)
            val stamp = FAST_KV_SUFFIXES.maxOfOrNull { suffix ->
                File(dir, disk + suffix).lastModified()
            } ?: 0L
            synchronized(stampLock) {
                if (stamp != lastStamp) {
                    lastStamp = stamp
                    return HchatStorage.reopen(context, name)
                }
                return HchatStorage.preferences(context, name)
            }
        }

        @JvmStatic
        fun install(context: Context?, classLoader: ClassLoader?) {
            if (context == null || classLoader == null) return
            val key = "${classLoader.javaClass.name}@${System.identityHashCode(classLoader)}"
            synchronized(installedLoaders) {
                if (installedLoaders.contains(key)) return
                val clazz = KavaReflector.loadClass(AD_INFO_CLASS, classLoader)
                if (clazz == null) {
                    HLog.e("$TAG 未找到朋友圈广告信息类: $AD_INFO_CLASS")
                    return
                }
                val constructor = KavaReflector.findConstructor(clazz, String::class.java)
                if (constructor == null) {
                    HLog.e("$TAG 未找到朋友圈广告信息构造方法: $AD_INFO_CLASS(String)")
                    return
                }
                runCatching {
                    HookRegistry.get().hook(constructor, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!isEnabled(context)) return
                            param.result = null
                            val count = hitCount.incrementAndGet()
                            if (count <= 5) {
                                HLog.e("$TAG 已拦截朋友圈广告信息 #$count")
                            }
                        }
                    })
                    installedLoaders.add(key)
                    HLog.e("$TAG 去广告 Hook 已安装: $AD_INFO_CLASS(String)")
                }.onFailure {
                    HLog.e("$TAG Hook 朋友圈广告信息失败: ${it.message}", it)
                }
            }
        }
    }
}
