package h.Hchat.hooks.items.adapt

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.dexkit.DexFinder
import h.Hchat.dexkit.SettingsDexFinder
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.hooks.items.mytabswitch.MyTabDexFinder
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import org.luckypray.dexkit.DexKitBridge

private const val TAG = "[Hchat:Adapt]"

/**
 * 安装后适配提醒。
 *
 * 微信每次安装/更新/降级，APK 修改时间都会变，`AdaptState` 会因此认定
 * "这个包体还没适配过"，于是在微信冷启动后弹一个对话框，让用户点
 * 「立即适配」——点了才去跑 DexKit 扫描，扫完写缓存、提示重启微信。
 *
 * 这样做的两个理由：
 *  1. DexKit 扫描要 1~3 秒，放在启动流程里会让微信白屏，必须用户主动触发；
 *  2. 用户能明确知道"这次装完有没有适配成功"，而不是功能悄悄失效。
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

    /** 扫描结果缓存：点「立即适配」前清掉，否则 DexFinder 会命中缓存直接 return。 */
    private val SCAN_CACHE_PREFS = listOf(
        "Hchat_dex_cache",
        "Hchat_settings_dex_cache",
        "Hchat_my_tab_dex_cache"
    )

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
                // 注意：这里不能提前把 promptedThisProcess 置 true。微信被推送/服务在后台
                // 拉起时 onResume 也会走，那时窗口不可见，一旦置位本次进程就再也不弹了。
                // 只有在窗口真正可见、对话框要显示时才置位。
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
            showPrompt(activity, describePackage(activity))
        }.onFailure {
            attempting = false
            HLog.e("$TAG 弹窗异常: ${it.message}", it)
        }
    }

    private fun showPrompt(activity: Activity, info: String) {
        val builder = themedBuilder(activity)
            ?: return HLog.e("$TAG 无法创建对话框")

        builder
            .setTitle("Hchat 需要适配")
            .setMessage(
                buildString {
                    append("检测到微信包体已变化：\n")
                    append(info)
                    append("\n\n需要重新扫描微信 dex 来定位下列功能：\n")
                    append("· 我页条目（我 → 设置 → Hchat）\n\n")
                    append("点「立即适配」开始，大约 1~3 秒。")
                }
            )
            .setPositiveButton("立即适配") { _, _ -> startAdapt(activity) }
            .setNegativeButton("稍后") { _, _ -> HLog.i("$TAG 用户选择了稍后") }
            .setCancelable(false)
            .show()
    }

    private fun startAdapt(activity: Activity) {
        val context = activity.applicationContext
        val classLoader = activity.classLoader
        val runtimeKey = AdaptState.runtimeKey(context, classLoader)
        val holder = currentHolder
        val dexKit = currentDexKit
        val fallbackFinder = currentDexFinder

        // 先清掉上一版扫出来的结果：DexFinder.resolveAll() 一命中缓存就直接 return，
        // 不清的话「全盘扫描」是假的（不会真正跑那 41 项）。
        resetScanCache(context)

        // 新建一个干净的 scanner：字段全空、缓存已清，resolveAll 必然跑完整 41 项。
        // 不复用全局单例，是因为它可能在本进程早先已经 loadCache 过，字段里是旧值。
        val dexFinder: DexFinder? = runCatching {
            if (dexKit != null) DexFinder(dexKit, classLoader, context) else fallbackFinder
        }.getOrNull()

        // 1 项我页条目 + 41 项微信 API + 1 项设置页框架
        val total = 43
        val dialog = themedBuilder(activity)
            ?.setTitle("正在扫描适配")
            ?.setMessage("准备中…")
            ?.setCancelable(false)
            ?.create()
        runCatching { dialog?.show() }

        val handler = Handler(Looper.getMainLooper())
        fun show(text: String) {
            handler.post { runCatching { dialog?.setMessage(text) } }
        }

        Thread {
            val failed = ArrayList<String>()
            var located: MyTabDexFinder.Located? = null

            show("1/$total 正在扫描：我页条目…")
            located = runCatching { holder?.let { MyTabDexFinder.locate(it, classLoader) } }
                .getOrElse {
                    HLog.e("$TAG 我页条目扫描异常: ${it.message}", it)
                    null
                }
            if (located == null) {
                failed.add("我页条目")
            } else {
                MyTabDexFinder.save(context, located, runtimeKey)
            }

            if (dexFinder != null) {
                dexFinder.setAdaptProgressListener { item, index, _ ->
                    show("${index + 1}/$total 正在扫描：$item…")
                }
                val ok = runCatching { dexFinder.resolveAll() }.isSuccess
                dexFinder.setAdaptProgressListener(null)
                if (!ok) failed.add("微信 API")
            } else {
                failed.add("微信 API（DexFinder 未就绪）")
            }

            show("$total/$total 正在扫描：设置页框架…")
            if (dexKit != null) {
                val ok = runCatching {
                    SettingsDexFinder(dexKit, classLoader, context).resolveAll(false)
                }.isSuccess
                if (!ok) failed.add("设置页框架")
            } else {
                failed.add("设置页框架（DexKit 未就绪）")
            }

            handler.post {
                runCatching { dialog?.dismiss() }
                if (failed.isNotEmpty()) {
                    showResult(
                        activity,
                        "适配未完全成功",
                        "失败项：${failed.joinToString("、")}\n\n其余功能不受影响。可以再点一次「立即适配」重试。",
                        false
                    )
                } else {
                    AdaptState.markAdapted(context, classLoader, versionName(activity))
                    showResult(
                        activity,
                        "适配完成",
                        "共 $total 项全部定位成功。\n我页条目：${located?.toText().orEmpty()}\n\n重启微信后生效。",
                        true
                    )
                }
            }
        }.also { it.name = "Hchat-AdaptScan"; it.isDaemon = true }.start()
    }

    /**
     * 清掉扫描结果缓存（FastKV），保证「立即适配」一定真扫。
     * 只清定位缓存，业务数据（假转发记录、缓存帖）不动。
     */
    private fun resetScanCache(context: Context) {
        for (name in SCAN_CACHE_PREFS) {
            runCatching { HchatStorage.preferences(context, name).edit().clear().commit() }
                .onFailure { HLog.e("$TAG 清扫描缓存失败($name): ${it.message}", it) }
        }
        HLog.i("$TAG 已清空扫描缓存，等待全量重扫: ${SCAN_CACHE_PREFS.joinToString()}")
    }

    private fun showResult(activity: Activity, title: String, message: String, canRestart: Boolean) {
        val builder = themedBuilder(activity) ?: return
        builder.setTitle(title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(if (canRestart) "重启微信" else "知道了") { _, _ ->
                if (canRestart) restartWeChat()
            }
        if (canRestart) builder.setNegativeButton("稍后") { _, _ -> }
        builder.show()
    }

    private fun restartWeChat() {
        HLog.i("$TAG 用户确认重启微信")
        runCatching { android.os.Process.killProcess(android.os.Process.myPid()) }
    }

    private fun themedBuilder(activity: Activity): AlertDialog.Builder? = runCatching {
        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
    }.getOrElse {
        runCatching { AlertDialog.Builder(activity) }.getOrNull()
    }

    private fun describePackage(context: Context): String {
        val pm = context.packageManager
        val info = runCatching { pm.getPackageInfo(context.packageName, 0) }.getOrNull()
        val versionName = info?.versionName ?: "?"
        val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
            runCatching { info?.longVersionCode }.getOrNull() ?: 0L
        } else {
            @Suppress("DEPRECATION")
            runCatching { info?.versionCode?.toLong() }.getOrNull() ?: 0L
        }
        val installedAt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
            .format(java.util.Date(AdaptState.installedAt(context)))
        return "版本 $versionName（$versionCode）\n安装于 $installedAt"
    }

    private fun versionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty()

    /** 由 Feature 安装时注入，供扫描线程取 DexKit。 */
    @Volatile
    var currentHolder: h.Hchat.dexkit.DexBridgeHolder? = null

    /** 共享的 DexFinder：全盘扫描就是调它的 resolveAll（41 项微信 API）。 */
    @Volatile
    var currentDexFinder: DexFinder? = null

    @Volatile
    var currentDexKit: DexKitBridge? = null
}