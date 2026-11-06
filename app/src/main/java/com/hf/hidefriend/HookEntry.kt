package com.hf.hidefriend

import android.app.Application
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 入口：只挂在微信主进程。
 */
class HookEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET) return

        val process = runCatching { lpparam.processName }.getOrNull()
        if (process != null && process != TARGET) return

        val version = runCatching {
            XposedHelpers.getObjectField(lpparam.appInfo, "versionName") as? String
        }.getOrNull() ?: "?"
        log("装载微信 $version 进程=$process")

        try {
            Hidden.reload()
            val app = applicationOf(lpparam)
            if (app == null) log("拿不到 Application，继续尝试安装")
            HomeHider.install(lpparam)
            log("初始化完成：名单 ${Hidden.size()} 人，开关=${Hidden.enabled()} 激进=${Hidden.aggressive()}")
        } catch (t: Throwable) {
            log("初始化异常: $t")
        }
    }

    private fun applicationOf(lpparam: XC_LoadPackage.LoadPackageParam): Application? = runCatching {
        XposedHelpers.callStaticMethod(
            XposedHelpers.findClass("android.app.ActivityThread", lpparam.classLoader),
            "currentApplication"
        ) as? Application
    }.getOrNull()

    companion object {
        const val TARGET = "com.tencent.mm"

        @JvmStatic
        fun log(msg: String) {
            XposedBridge.log("HF: $msg")
        }
    }
}