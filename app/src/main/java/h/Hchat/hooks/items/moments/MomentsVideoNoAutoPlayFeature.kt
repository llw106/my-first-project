package h.Hchat.hooks.items.moments

import de.robv.android.xposed.XC_MethodHook
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.ConfigStore
import h.Hchat.utils.HLog
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * 朋友圈视频禁止自动播放：WiFi 和移动网络下都必须点一下才播。
 *
 * 8.0.80 上朋友圈视频有两条各自独立的自动播放判定，逻辑完全一样：
 * - Improve 时间线：ImproveAutoPlayManager 的 autoPlay 懒加载值
 * - 折叠详情页 / 旧列表：SnsAutoPlayUtil.checkAutoPlay(networkMgr)
 * 判定内容：2G/3G 返回 false，WiFi 直接返回 true，其它网络读微信
 * 「移动网络下视频自动播放」开关。所以微信自带开关只挡得住数据网络，WiFi 挡不住。
 *
 * 两份实现里都有日志 "wifi autoplay true"（8.0.80 全 dex 只有这两处），
 * 用它定位后把返回值改成 false，两条链路一起变成「点了才播」。
 * 视频卡片保持封面、不进入播放态，和微信在移动数据下的原生表现一致。
 */
class MomentsVideoNoAutoPlayFeature : BaseFeature() {
    override fun featureId(): String = ID

    override fun name(): String = "朋友圈视频手动播放"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(MomentsVideoNoAutoPlaySettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        configStore = context.configStore()
        installHooks(context)
    }

    companion object {
        const val ID = "moments_video_no_autoplay"

        /** 设置页开关用的 key（写在全局配置表里），默认 true = 拦截自动播放。 */
        const val KEY_ENABLED = "moments_video_no_autoplay_enabled"
        private const val TAG = "[Hchat:NoAutoPlay]"

        /** 两条判定实现共有的日志串，8.0.80 全 dex 只有 2 处命中。 */
        private const val ANCHOR = "wifi autoplay true"
        private const val MAX_HIT_LOG = 5

        @Volatile
        private var configStore: ConfigStore? = null

        private val hookedKeys = Collections.synchronizedSet(HashSet<String>())
        private val hitCount = AtomicInteger()

        /** 每次命中都重新读配置，设置页关掉开关后立刻恢复自动播放。 */
        private fun enabledNow(): Boolean = try {
            configStore?.getGlobalBoolean(KEY_ENABLED, true) ?: true
        } catch (_: Throwable) {
            true
        }

        private fun installHooks(context: FeatureContext) {
            val startedAt = System.currentTimeMillis()
            try {
                val finder = FindMethod()
                val matcher = MethodMatcher()
                matcher.usingStrings(listOf(ANCHOR))
                finder.matcher(matcher)
                val candidates = context.dexKitBridge().findMethod(finder)
                if (candidates.isEmpty()) {
                    HLog.e("$TAG 未找到自动播放判定方法，锚点=$ANCHOR")
                    return
                }
                var installed = 0
                for (data in candidates) {
                    val method = try {
                        data.getMethodInstance(context.hostClassLoader())
                    } catch (e: Throwable) {
                        HLog.e("$TAG 取方法实例失败: ${e.message}", e)
                        continue
                    }
                    val key = method.declaringClass.name + "#" + method.name
                    if (!hookedKeys.add(key)) continue
                    try {
                        HookRegistry.get().hook(method, object : XC_MethodHook() {
                            override fun beforeHookedMethod(param: MethodHookParam) {
                                if (!enabledNow()) return
                                // 原始返回值是 boolean 或装箱 Boolean，统一给 FALSE。
                                param.result = java.lang.Boolean.FALSE
                                val count = hitCount.incrementAndGet()
                                if (count <= MAX_HIT_LOG) {
                                    HLog.e("$TAG 已拦截自动播放判定 #$count: $key")
                                }
                            }
                        })
                        installed++
                        HLog.e("$TAG Hook 已安装: $key -> ${method.returnType?.name}")
                    } catch (e: Throwable) {
                        HLog.e("$TAG Hook 失败: $key ${e.message}", e)
                    }
                }
                HLog.e("$TAG 扫描完成 ${System.currentTimeMillis() - startedAt}ms，候选=${candidates.size}，安装=$installed")
            } catch (e: Throwable) {
                HLog.e("$TAG 定位失败: ${e.message}", e)
            }
        }
    }
}
