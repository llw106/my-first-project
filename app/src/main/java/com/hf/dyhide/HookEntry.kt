package com.hf.dyhide

import android.app.Application
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 入口：只挂抖音，先装 RecyclerView 探针。
 *
 * 抖音是字节加固（Dex2C + so 加密），不能像微信那样靠 DexKit 静态搜方法，
 * 所以第一步先把「页面 → adapter → 数据元素」全部打到日志，确认私信列表长什么样。
 */
class HookEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET) return
        log("装载抖音 ? 进程=" + lpparam.processName)
        try {
            XposedHelpers.findAndHookMethod(
                Application::class.java,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        Hidden.reload()
                        log("初始化完成：名单 " + Hidden.size() + " 人，开关=" + Hidden.enabled())
                        Probe.install(lpparam.classLoader)
                    }
                }
            )
        } catch (t: Throwable) {
            log("hook Application 失败: $t")
        }
    }

    companion object {
        const val TARGET = "com.ss.android.ugc.aweme"

        @JvmStatic
        fun log(msg: String) {
            XposedBridge.log("DY: $msg")
        }
    }
}