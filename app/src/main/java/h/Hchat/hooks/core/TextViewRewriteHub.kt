package h.Hchat.hooks.core

import android.app.Application
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 全局的 TextView#setText 改写分发器。
 *
 * 多个功能（自定义昵称、自定义微信号…）都需要改界面文本，如果各自 hook 一次，
 * 每次 setText 要付 N 次 Xposed 回调开销。这里统一装一个 hook，再分发给各功能；
 * 并且只在有界面的进程里安装，后台进程（:push 等）直接跳过。
 */
object TextViewRewriteHub {

    fun interface Rewriter {
        fun rewrite(param: XC_MethodHook.MethodHookParam)
    }

    private val rewriters = CopyOnWriteArrayList<Rewriter>()

    /** 映射表重建等脏活放到带 Looper 的后台线程，避免堵住 UI 线程。 */
    private val worker: Handler by lazy {
        val thread = HandlerThread("Hchat-TextRewrite").apply { start() }
        Handler(thread.looper)
    }

    fun runOnWorker(block: () -> Unit) {
        worker.post(block)
    }

    @Volatile
    private var installed = false

    @Synchronized
    fun register(rewriter: Rewriter): Boolean {
        if (rewriters.none { it === rewriter }) rewriters.add(rewriter)
        installIfNeeded()
        return installed
    }

    @Synchronized
    fun unregister(rewriter: Rewriter) {
        rewriters.removeAll { it === rewriter }
    }

    private fun installIfNeeded() {
        if (installed) return
        if (!isUiProcess()) return
        val method = runCatching {
            TextView::class.java.getDeclaredMethod(
                "setText",
                CharSequence::class.java,
                TextView.BufferType::class.java
            )
        }.getOrNull() ?: return
        val hooked = runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    // setText 极高频，没有订阅者时直接返回，省掉一次迭代器分配。
                    if (rewriters.isEmpty()) return
                    for (rewriter in rewriters) {
                        runCatching { rewriter.rewrite(param) }
                    }
                }
            })
        }.isSuccess
        if (hooked) installed = true
    }

    /** 后台进程没有界面，装改写 hook 只是白占内存。 */
    private fun isUiProcess(): Boolean {
        val name = currentProcessName() ?: return true
        return !name.contains(":")
    }

    private fun currentProcessName(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { Application.getProcessName() }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
        }
        runCatching {
            val clazz = Class.forName("android.app.ActivityThread")
            val method = clazz.getDeclaredMethod("currentProcessName")
            method.isAccessible = true
            return method.invoke(null) as? String
        }
        return runCatching {
            java.io.File("/proc/self/cmdline").readText().trim { it <= ' ' }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }
}