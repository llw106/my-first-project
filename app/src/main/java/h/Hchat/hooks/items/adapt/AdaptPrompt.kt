package h.Hchat.hooks.items.adapt

import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.hooks.items.mytabswitch.MyTabDexFinder
import h.Hchat.utils.HLog

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

    fun install(context: FeatureContext) {
        currentHolder = context.dexBridgeHolder()
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
                if (promptedThisProcess) return
                val activity = param.thisObject as? Activity ?: return
                if (!launcher.isInstance(activity)) return
                promptedThisProcess = true
                // 等首屏稳定再弹，避免和微信自己的启动动画抢焦点。
                runCatching {
                    activity.window.decorView.postDelayed({ maybePrompt(activity) }, 1800L)
                }.onFailure { maybePrompt(activity) }
            }
        })
        HLog.i("$TAG 安装后适配提醒已安装 (LauncherUI.onResume)")
    }

    private fun maybePrompt(activity: Activity) {
        runCatching {
            val app = activity.applicationContext
            val classLoader = activity.classLoader
            if (AdaptState.isAdapted(app, classLoader)) {
                HLog.i("$TAG 当前包体已适配，不提示")
                return
            }
            val key = AdaptState.runtimeKey(app, classLoader)
            HLog.i("$TAG 当前包体未适配 key=$key")
            showPrompt(activity, describePackage(activity))
        }.onFailure { HLog.e("$TAG 弹窗异常: ${it.message}", it) }
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
        val progress = runCatching {
            ProgressDialog(activity).apply {
                setMessage("正在扫描适配…\n我页条目")
                setCancelable(false)
                setCanceledOnTouchOutside(false)
                show()
            }
        }.getOrNull()

        val context = activity.applicationContext
        val classLoader = activity.classLoader
        val runtimeKey = AdaptState.runtimeKey(context, classLoader)
        val holder = currentHolder

        Thread {
            val located = runCatching {
                if (holder == null) null else MyTabDexFinder.locate(holder, classLoader)
            }.getOrElse {
                HLog.e("$TAG 扫描异常: ${it.message}", it)
                null
            }

            activity.runOnUiThread {
                runCatching { progress?.dismiss() }
                if (located == null) {
                    showResult(activity, "适配失败", "没有定位到「我页条目」需要的类。\n\n可能是微信这一版改了结构，需要更新 Hchat。", false)
                    return@runOnUiThread
                }
                MyTabDexFinder.save(context, located, runtimeKey)
                AdaptState.markAdapted(context, classLoader, versionName(activity))
                showResult(
                    activity,
                    "适配完成",
                    "我页条目：${located.toText()}\n\n重启微信后生效。",
                    true
                )
            }
        }.also { it.name = "Hchat-AdaptScan"; it.isDaemon = true }.start()
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
}