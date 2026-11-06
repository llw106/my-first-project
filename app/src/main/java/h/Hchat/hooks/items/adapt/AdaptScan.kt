package h.Hchat.hooks.items.adapt

import android.content.Context
import h.Hchat.dexkit.DexBridgeHolder
import h.Hchat.dexkit.DexFinder
import h.Hchat.dexkit.SettingsDexFinder
import h.Hchat.hooks.items.mytabswitch.MyTabDexFinder
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import org.luckypray.dexkit.DexKitBridge

/**
 * 43 项适配扫描的统一入口。
 *
 * 两处用同一套逻辑：
 *  · 安装后自动弹出的「Hchat 需要适配」
 *  · 设置 → 基础配置 → 扫描适配（手动重扫）
 *
 * 组成：1 项我页条目 + 41 项微信 API（DexFinder.resolveAll）+ 1 项设置页框架。
 */
object AdaptScan {

    private const val TAG = "[Hchat:AdaptScan]"

    const val TOTAL = 43

    /**
     * 模块初始化时注入的 DexKit 运行时。
     * 设置页的手动扫描用它，这样即使「安装后适配提醒」这个 Feature 被关掉，扫描页也还能用。
     */
    @Volatile
    var holder: DexBridgeHolder? = null
        private set

    @Volatile
    var dexKit: DexKitBridge? = null
        private set

    @Volatile
    var finder: DexFinder? = null
        private set

    fun attach(holder: DexBridgeHolder?, dexKit: DexKitBridge?, finder: DexFinder?) {
        this.holder = holder
        this.dexKit = dexKit
        this.finder = finder
        HLog.i("$TAG 运行时已注入: dexKit=${dexKit != null} holder=${holder != null}")
    }

    /**
     * 扫描结果缓存。开扫前必须清空——
     * `DexFinder.resolveAll()` 第一件事是 `if (loadCache()) { resolvedAll = true; return; }`，
     * 缓存还在就会秒退，41 项一项都不跑（上一版就是这么假扫的）。
     */
    private val SCAN_CACHE_PREFS = listOf(
        "Hchat_dex_cache",
        "Hchat_settings_dex_cache",
        "Hchat_my_tab_dex_cache"
    )

    data class Result(
        val failed: List<String> = emptyList(),
        val myTab: String = "",
        val elapsedMs: Long = 0L
    ) {
        val ok: Boolean get() = failed.isEmpty()

        fun summary(): String = when {
            ok -> "共 $TOTAL 项全部定位成功"
            failed.size == 1 -> "共 $TOTAL 项，1 项未命中：${failed[0]}"
            else -> "共 $TOTAL 项，${failed.size} 项未命中：${failed.joinToString("、")}"
        }
    }

    /** 清掉上一次的扫描结果（只清定位缓存，业务数据不动）。 */
    fun resetCache(context: Context) {
        for (name in SCAN_CACHE_PREFS) {
            runCatching { HchatStorage.preferences(context, name).edit().clear().commit() }
                .onFailure { HLog.e("$TAG 清扫描缓存失败($name): ${it.message}", it) }
        }
        HLog.i("$TAG 已清空扫描缓存，等待全量重扫")
    }

    /**
     * 真正扫描。**必须在后台线程调用**（实测 3~10 秒）。
     *
     * onProgress 会从后台线程回调，调用方自己 post 到主线程。
     */
    fun run(
        context: Context,
        classLoader: ClassLoader,
        holder: DexBridgeHolder?,
        dexKit: DexKitBridge?,
        fallbackFinder: DexFinder?,
        onProgress: (index: Int, item: String) -> Unit
    ): Result {
        val started = System.currentTimeMillis()
        val failed = ArrayList<String>()

        // 先清缓存，再 new 一个干净的 DexFinder：保证 41 项真的跑，而不是命中缓存秒退。
        // 不复用全局单例，是因为它可能在本进程早先已经 loadCache 过，字段里是旧值。
        resetCache(context)
        val scanner = runCatching {
            if (dexKit != null) DexFinder(dexKit, classLoader, context) else fallbackFinder
        }.getOrNull()

        onProgress(1, "我页条目")
        val located = runCatching { holder?.let { MyTabDexFinder.locate(it, classLoader) } }
            .onFailure { HLog.e("$TAG 我页条目扫描异常: ${it.message}", it) }
            .getOrNull()
        if (located == null) {
            failed.add("我页条目")
        } else {
            MyTabDexFinder.save(context, located, AdaptState.runtimeKey(context, classLoader))
        }

        if (scanner != null) {
            scanner.setAdaptProgressListener { item, index, _ -> onProgress(index + 1, item) }
            runCatching { scanner.resolveAll() }
                .onFailure {
                    HLog.e("$TAG 微信 API 扫描异常: ${it.message}", it)
                    failed.add("微信 API")
                }
            scanner.setAdaptProgressListener(null)
        } else {
            failed.add("微信 API（DexKit 未就绪）")
        }

        onProgress(TOTAL, "设置页框架")
        if (dexKit != null) {
            runCatching { SettingsDexFinder(dexKit, classLoader, context).resolveAll(false) }
                .onFailure {
                    HLog.e("$TAG 设置页框架扫描异常: ${it.message}", it)
                    failed.add("设置页框架")
                }
        } else {
            failed.add("设置页框架（DexKit 未就绪）")
        }

        val elapsed = System.currentTimeMillis() - started
        HLog.i("$TAG 扫描完成 ${elapsed}ms，失败项=[${failed.joinToString(",")}]")
        return Result(failed, located?.toText().orEmpty(), elapsed)
    }
}