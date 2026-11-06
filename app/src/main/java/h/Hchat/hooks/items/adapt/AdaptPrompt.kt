package h.Hchat.hooks.items.adapt

import android.app.Activity
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.dexkit.DexFinder
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.utils.HLog
import org.luckypray.dexkit.DexKitBridge

private const val TAG = "[Hchat:Adapt]"

/**
 * 安装后适配提醒。
 *
 * 微信每次安装/更新/降级，APK 修改时间都会变，`AdaptState` 会因此认定
 * 「这个包体还没适配过」，于是在微信冷启动后弹一张卡片，让用户点
 * 「立即适配」——点了才去跑 DexKit 扫描（`AdaptScan`，共 43 项）。
 *
 * 这样做的两个理由：
 *  1. DexKit 扫描要 3~10 秒，放在启动流程里会让微信白屏，必须用户主动触发；
 *  2. 用户能明确知道「这次装完有没有适配成功」，而不是功能悄悄失效。
 */
class AdaptPromptFeature : BaseFeature() {

    override fun featureId(): String = ID

    override fun name(): String = "安装后适配提醒"

    override fun onFeatureInstall(context: FeatureContext) {
        runCatching { AdaptPrompt.install(context) }
            .onFailure { HLog.e("$TAG 安装失败: ${it.message}", it) }
    }

    companion object {
        const val ID = "adapt_prompt"
    }
}

private object AdaptPrompt {

    private var promptedThisProcess = false

    /** 已经在「等窗口可见 → 弹窗」的流程里，防止多次 onResume 叠加重试链。 */
    @Volatile
    private var attempting = false

    fun install(context: FeatureContext) {
        currentHolder = context.dexBridgeHolder()
        currentDexFinder = context.dexFinder()
        currentDexKit = context.dexKitBridge()
        AdaptState.moduleTag = AdaptState.hchatTag(context.moduleContext())
        val classLoader = context.hostClassLoader()
        val launcher = runCatching { classLoader.loadClass("com.tencent.mm.ui.LauncherUI") }
            .getOrNull()
            ?: return HLog.e("$TAG 找不到 LauncherUI，适配提醒未安装")

        // 优先 hook 自己的 onResume；没重写就退回父类的（Xposed 对未重写的父类方法同样生效）。
        val onResume = runCatching { launcher.getDeclaredMethod("onResume") }.getOrNull()
            ?: runCatching { launcher.getMethod("onResume") }.getOrNull()
            ?: return HLog.e("$TAG 找不到 onResume，适配提醒未安装")

        HookRegistry.get().hook(onResume, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (promptedThisProcess || attempting) return
                val activity = param.thisObject as? Activity ?: return
                if (!launcher.isInstance(activity)) return
                // 这里不能提前把 promptedThisProcess 置 true：微信被推送/服务在后台
                // 拉起时 onResume 也会走，那时窗口不可见，一旦置位本次进程就再也不弹了。
                attempting = true
                // 等首屏稳定再弹，避免和微信自己的启动动画抢焦点。
                runCatching {
                    activity.window.decorView.postDelayed({ maybePrompt(activity) }, 1800L)
                }.onFailure { maybePrompt(activity) }
            }
        })
        HLog.i("$TAG 安装后适配提醒已安装 (LauncherUI.onResume)")
    }

    private fun maybePrompt(activity: Activity, attempt: Int = 0) {
        if (activity.isFinishing) {
            attempting = false
            return
        }
        runCatching {
            val app = activity.applicationContext
            val classLoader = activity.classLoader
            if (AdaptState.isAdapted(app, classLoader)) {
                HLog.i("$TAG 当前包体已适配，不提示")
                attempting = false
                return
            }

            // 微信被系统/推送在后台拉起时，onResume 同样会执行，但窗口不可见，
            // 这时候弹窗用户是看不到的。等窗口真正获得焦点再弹，最多等 30 秒。
            val visible = activity.hasWindowFocus() &&
                runCatching { activity.window.decorView.isShown }.getOrDefault(false)
            if (!visible) {
                if (attempt < 30) {
                    runCatching {
                        activity.window.decorView.postDelayed({ maybePrompt(activity, attempt + 1) }, 1000L)
                    }.onFailure {
                        HLog.i("$TAG 窗口不可见且无法排重试 ($attempt)")
                        attempting = false
                    }
                } else {
                    HLog.i("$TAG 窗口 30 秒内一直不可见，等下次回到前台再提示")
                    attempting = false
                }
                return
            }

            HLog.i("$TAG 弹窗 (attempt=$attempt, key=${AdaptState.runtimeKey(app, classLoader)})")
            promptedThisProcess = true
            attempting = false
            AdaptDialog.showPrompt(
                activity = activity,
                info = AdaptState.describe(activity),
                onStart = { startAdapt(activity) },
                onLater = { HLog.i("$TAG 用户选择了稍后") }
            )
        }.onFailure {
            attempting = false
            HLog.e("$TAG 弹窗异常: ${it.message}", it)
        }
    }

    /** 点「立即适配」后跑全量扫描（43 项）。扫描在后台线程，进度回主线程刷卡片。 */
    private fun startAdapt(activity: Activity) {
        val context = activity.applicationContext
        val classLoader = activity.classLoader
        val holder = currentHolder
        val dexKit = currentDexKit
        val fallback = currentDexFinder
        val handler = Handler(Looper.getMainLooper())
        val progress = AdaptDialog.showProgress(activity)

        Thread {
            val result = runCatching {
                AdaptScan.run(context, classLoader, holder, dexKit, fallback) { index, item ->
                    handler.post { progress?.update(index, item) }
                }
            }.onFailure { HLog.e("$TAG 扫描异常: ${it.message}", it) }.getOrNull()

            handler.post {
                progress?.dismiss()
                if (result == null) {
                    AdaptDialog.showResult(
                        activity = activity,
                        title = "适配失败",
                        message = "扫描过程中出错了。\n可以到「设置 → 基础配置 → 扫描适配」重试一次。",
                        primaryLabel = "知道了",
                        onPrimary = {}
                    )
                    return@post
                }

                // 不管有没有失败项都标记成「已适配」：否则每重启一次微信就弹一次窗。
                AdaptState.markAdapted(
                    context = context,
                    classLoader = classLoader,
                    versionName = AdaptState.versionName(context),
                    summary = result.summary(),
                    failed = result.failed
                )
                val message = if (result.ok) {
                    "共 ${AdaptScan.TOTAL} 项全部定位成功。\n${result.myTab}\n\n重启微信后生效。"
                } else {
                    "${result.summary()}\n\n未命中的项不影响其它功能，之后可以在「设置 → 基础配置 → 扫描适配」重新扫描。\n\n重启微信后生效。"
                }
                AdaptDialog.showResult(
                    activity = activity,
                    title = if (result.ok) "适配完成" else "适配完成（部分项未命中）",
                    message = message,
                    primaryLabel = "重启微信",
                    onPrimary = { restartWeChat() },
                    secondaryLabel = "稍后"
                )
            }
        }.also { it.name = "Hchat-AdaptScan"; it.isDaemon = true }.start()
    }

    private fun restartWeChat() {
        HLog.i("$TAG 用户确认重启微信")
        runCatching { android.os.Process.killProcess(android.os.Process.myPid()) }
    }

    /** 由 Feature 安装时注入，供扫描线程取 DexKit。 */
    @Volatile
    var currentHolder: h.Hchat.dexkit.DexBridgeHolder? = null

    /** 没有 DexKitBridge 时退回用它（可能是全局单例，会命中缓存）。 */
    @Volatile
    var currentDexFinder: DexFinder? = null

    @Volatile
    var currentDexKit: DexKitBridge? = null
}