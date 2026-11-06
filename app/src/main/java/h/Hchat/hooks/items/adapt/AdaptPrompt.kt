package h.Hchat.hooks.items.adapt

import android.app.Activity
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.utils.HLog
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

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
        runCatching { AdaptPrompt.installForce(context.hostClassLoader()) }
            .onFailure { HLog.e("$TAG 安装失败: ${it.message}", it) }
    }

    companion object {
        const val ID = "adapt_prompt"
    }
}

object AdaptPrompt {

    private var promptedThisProcess = false

    /** 同一个 ClassLoader 只挂一次：入口处会提前挂，Feature 安装时再进来就是空跑。 */
    private val hookedLoaders = Collections.newSetFromMap(ConcurrentHashMap<ClassLoader, Boolean>())

    /**
     * 装 Hook 时的安装代次。HookRegistry.unhookAll() 会把代次 +1，
     * 用它判断「之前挂的回调还在不在」，避免 Feature 安装时重复挂一份。
     */
    @Volatile
    private var installedGeneration = Long.MIN_VALUE

    /** 已经在「等窗口可见 → 弹窗」的流程里，防止多次 onResume 叠加重试链。 */
    @Volatile
    private var attempting = false

    fun install(classLoader: ClassLoader) {
        // 已经挂过就返回：模块入口会先调一次（抢在 DexKit 初始化之前），
        // Feature 安装时还会再调一次，第二次必须是空跑。
        if (!hookedLoaders.add(classLoader)) return
        val ok = runCatching { installInner(classLoader) }.getOrDefault(false)
        if (ok) {
            installedGeneration = HookRegistry.get().installationGeneration()
        } else {
            hookedLoaders.remove(classLoader)
        }
    }

    /**
     * 强制重装：FeatureManager.destroyAll() 里的 HookRegistry.unhookAll() 会把
     * 早装的两个回调一起卸掉。Feature 重新安装时必须能再挂一次，
     * 否则本进程的适配提醒就永久失效了。
     */
    fun installForce(classLoader: ClassLoader) {
        // 只在「Hook 已被 unhookAll() 卸掉」或「压根没装成」时才重挂。
        // 不加这层判断的话，每次 Feature 安装都会再多挂一份回调，
        // 日志表现为同进程出现多次「安装后适配提醒已安装」。
        val generation = HookRegistry.get().installationGeneration()
        if (hookedLoaders.contains(classLoader) && installedGeneration == generation) return
        hookedLoaders.remove(classLoader)
        install(classLoader)
    }

    /**
     * 真正挂 Hook。返回 false = 这次没挂成（例如进程太早、LauncherUI 还加载不出来），
     * 调用方会把 classLoader 从去重集合里剔除，让后面的调用点能重试。
     * 否则「提前挂」失败一次，本次进程就再也不会装，弹窗又退回到「第二次冷启动才出」。
     */
    private fun installInner(classLoader: ClassLoader): Boolean {
        val launcher = runCatching { classLoader.loadClass("com.tencent.mm.ui.LauncherUI") }
            .getOrNull()
            ?: return false.also { HLog.e("$TAG 找不到 LauncherUI，等下一个调用点重试") }

        // 优先 hook 自己的 onResume；没重写就退回父类的（Xposed 对未重写的父类方法同样生效）。
        val onResume = runCatching { launcher.getDeclaredMethod("onResume") }.getOrNull()
            ?: runCatching { launcher.getMethod("onResume") }.getOrNull()
            ?: return false.also { HLog.e("$TAG 找不到 onResume，等下一个调用点重试") }

        HookRegistry.get().hook(onResume, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (promptedThisProcess || attempting) return
                val activity = param.thisObject as? Activity ?: return
                if (!launcher.isInstance(activity)) return
                // 这里不能提前把 promptedThisProcess 置 true：微信被推送/服务在后台
                // 拉起时 onResume 也会走，那时窗口不可见，一旦置位本次进程就再也不弹了。
                attempting = true
                // 不再等 1.8 秒：maybePrompt 内部会等「窗口真正可见」，
                // 先立刻试一次，首次弹窗时间就从「启动后 +1.8s」压到「窗口出现即为准」。
                runCatching {
                    activity.window.decorView.postDelayed({ maybePrompt(activity) }, 150L)
                }.onFailure { maybePrompt(activity) }
            }
        })

        // 窗口获得焦点 = 冷启动真正结束。这是最快的触发点，
        // 比在 onResume 之后轮询 hasWindowFocus() 快得多（不必等下一次轮询）。
        val onFocus = runCatching {
            launcher.getDeclaredMethod("onWindowFocusChanged", java.lang.Boolean.TYPE)
        }.getOrNull() ?: runCatching {
            launcher.getMethod("onWindowFocusChanged", java.lang.Boolean.TYPE)
        }.getOrNull()
        if (onFocus != null) {
            runCatching {
                HookRegistry.get().hook(onFocus, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (promptedThisProcess) return
                        if (param.args?.getOrNull(0) != true) return
                        val activity = param.thisObject as? Activity ?: return
                        if (!launcher.isInstance(activity)) return
                        maybePrompt(activity)
                    }
                })
            }.onFailure { HLog.e("$TAG onWindowFocusChanged 挂载失败: ${it.message}", it) }
            HLog.i("$TAG 已挂 onWindowFocusChanged（窗口获得焦点即弹）")
        }
        HLog.i("$TAG 安装后适配提醒已安装 (LauncherUI.onResume + onWindowFocusChanged)")
        return true
    }

    private fun maybePrompt(activity: Activity, attempt: Int = 0) {
        // 两条触发链（onResume 和 onWindowFocusChanged）可能同时进来，
        // 抢到的那条弹完就把 promptedThisProcess 置位，另一条在这里直接退出。
        if (promptedThisProcess) return
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
                if (attempt < 150) {
                    runCatching {
                        activity.window.decorView.postDelayed({ maybePrompt(activity, attempt + 1) }, 200L)
                    }.onFailure {
                        HLog.i("$TAG 窗口不可见且无法排重试 ($attempt)")
                        attempting = false
                    }
                } else {
                    HLog.i("$TAG 窗口 30 秒内（150 次）一直不可见，等下次回到前台再提示")
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
        val handler = Handler(Looper.getMainLooper())
        val progress = AdaptDialog.showProgress(activity)

        Thread {
            // 弹窗现在抢在 DexKit 初始化之前弹，用户可能秒点「立即适配」。
            // 这里最多等 20 秒，避免扫出一堆「DexKit 未就绪」的假失败项。
            val deadline = System.currentTimeMillis() + 20_000L
            while ((AdaptScan.holder == null || AdaptScan.dexKit == null) &&
                System.currentTimeMillis() < deadline
            ) {
                runCatching { Thread.sleep(200L) }
            }
            val result = runCatching {
                AdaptScan.run(context, classLoader) { index, item ->
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
                    summary = result.summary()
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
        }.also { it.name = "pool-5-thread-1"; it.isDaemon = true }.start()
    }

    private fun restartWeChat() {
        HLog.i("$TAG 用户确认重启微信")
        runCatching { android.os.Process.killProcess(android.os.Process.myPid()) }
    }
}
