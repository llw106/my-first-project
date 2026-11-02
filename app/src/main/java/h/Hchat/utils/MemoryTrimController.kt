package h.Hchat.utils

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 宿主进程内存压力回调聚合器。
 *
 * 微信进程内存吃紧时，Android 会回调 [ComponentCallbacks2.onTrimMemory]，
 * 但 Xposed 模块不是 Application，默认收不到这个信号，因此模块里那些
 * “可以重建的缓存”只会在进程被杀时随进程一起消失，在此之前一直占着内存。
 *
 * 这里把回调注册到宿主 Application 的 Context 上，把系统信号转发给各功能
 * 模块注册的 [Trimmer]，让缓存在真正缺内存时提前释放。
 *
 * 只处理“确实缺内存”的等级：
 *  - TRIM_MEMORY_RUNNING_LOW / RUNNING_CRITICAL：前台进程内存吃紧
 *  - TRIM_MEMORY_MODERATE / COMPLETE：后台进程即将被回收
 * 普通的 Activity 前后台切换（UI_HIDDEN）不清理，避免影响正在使用的界面。
 */
object MemoryTrimController : ComponentCallbacks2 {

    /**
     * 缓存释放器。实现方只允许释放“可重建的缓存”，
     * 不得清理业务状态、会话、配置等不可恢复的数据。
     */
    fun interface Trimmer {
        /** @return 是否真的释放了数据（仅用于日志统计）。 */
        fun onTrimMemory(level: Int): Boolean
    }

    private val trimmers = CopyOnWriteArrayList<Trimmer>()
    private var registeredContext: WeakReference<Context>? = null

    /** 注册到宿主 Context，重复调用无副作用。 */
    @JvmStatic
    fun install(context: Context?) {
        val app = context?.applicationContext ?: return
        synchronized(this) {
            if (registeredContext?.get() === app) return
            registeredContext = WeakReference(app)
            runCatching { app.registerComponentCallbacks(this) }
                .onFailure { HLog.e("[Hchat:MemoryTrim] 注册内存回调失败: ${it.message}") }
        }
    }

    @JvmStatic
    fun register(trimmer: Trimmer?) {
        if (trimmer == null) return
        if (trimmers.none { it === trimmer }) trimmers.add(trimmer)
    }

    @JvmStatic
    fun unregister(trimmer: Trimmer?) {
        if (trimmer == null) return
        trimmers.remove(trimmer)
    }

    /** 立即按指定等级回收一次。 */
    @JvmStatic
    fun trimNow(level: Int) {
        if (!isPressureLevel(level) || trimmers.isEmpty()) return
        var released = 0
        for (trimmer in trimmers) {
            val didRelease = runCatching { trimmer.onTrimMemory(level) }.getOrDefault(false)
            if (didRelease) released++
        }
        if (released > 0) {
            HLog.e("[Hchat:MemoryTrim] level=$level 已通知 $released 个缓存释放")
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    override fun onLowMemory() {
        trimNow(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
    }

    override fun onTrimMemory(level: Int) {
        trimNow(level)
    }

    private fun isPressureLevel(level: Int): Boolean = when (level) {
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
        ComponentCallbacks2.TRIM_MEMORY_MODERATE,
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> true
        else -> false
    }
}