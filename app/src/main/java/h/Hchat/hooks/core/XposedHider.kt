package h.Hchat.hooks.core

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import h.Hchat.utils.HLog
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * 挡住宿主对 Xposed / LSPosed 的类探测。
 *
 * 宿主的 XposedCheck 绝大多数就是一句
 * `Class.forName("de.robv.android.xposed.XposedBridge")`：
 * 抛 ClassNotFoundException = 干净，正常返回 = 装了模块。
 *
 * 这里在三个最通用的入口上做拦截：
 *   - Class.forName(String)
 *   - Class.forName(String, boolean, ClassLoader)
 *   - ClassLoader.loadClass(String)
 *
 * 性能：正常类加载只做「首字符 + startsWith」比较，命中可疑前缀才会抓栈，
 * 微信启动那几千次类加载摊下来是微秒级，不会造成可感知的卡顿。
 *
 * 稳定性：只拦「框架外部发起」的探测。栈里出现 de.robv / org.lsposed / 模块自己的帧
 * 一律放行，避免把 LSPosed 自身的初始化打断。
 */
object XposedHider {
    private const val TAG = "[Hchat:Hide]"

    /** 已装过的 ClassLoader，同一进程只装一次。 */
    private val installedLoaders: MutableSet<ClassLoader> =
        Collections.newSetFromMap(ConcurrentHashMap<ClassLoader, Boolean>())

    /** 命中即视为「在探测 hook 框架」的类名前缀。 */
    private val PROBE_PREFIXES = arrayOf(
        "de.robv.android.xposed",
        "org.lsposed",
        "io.github.lsposed",
        "com.saurik.substrate",
        "com.elderdrivers.riru",
        "me.weishu.epic",
        "me.weishu.exposed",
        "top.canyie.pine",
        "com.swift.sandhook"
    )

    /** 这些栈帧属于框架或模块自身，必须放行。 */
    private val ALLOW_FRAME_PREFIXES = arrayOf(
        "de.robv.",
        "org.lsposed.",
        "io.github.lsposed.",
        "h.Hchat."
    )

    @JvmStatic
    fun install(classLoader: ClassLoader) {
        if (!installedLoaders.add(classLoader)) return

        val probes = ArrayList<Method>(3)
        runCatching {
            probes += Class::class.java.getDeclaredMethod("forName", String::class.java)
        }.onFailure { HLog.e("$TAG 找不到 Class.forName(String)") }
        runCatching {
            probes += Class::class.java.getDeclaredMethod(
                "forName", String::class.java, java.lang.Boolean.TYPE, ClassLoader::class.java
            )
        }.onFailure { HLog.e("$TAG 找不到 Class.forName(String, boolean, ClassLoader)") }
        runCatching {
            probes += ClassLoader::class.java.getDeclaredMethod("loadClass", String::class.java)
        }.onFailure { HLog.e("$TAG 找不到 ClassLoader.loadClass(String)") }

        var hooked = 0
        for (method in probes) {
            val result = runCatching {
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val name = param.args?.getOrNull(0) as? String ?: return
                        if (!looksLikeProbe(name)) return
                        if (calledFromFrameworkOrSelf()) return
                        param.setThrowable(ClassNotFoundException(name))
                    }
                })
            }
            if (result.isSuccess) {
                hooked++
            } else {
                HLog.e("$TAG 挂载失败: ${method.name} ${result.exceptionOrNull()?.message}", result.exceptionOrNull())
            }
        }
        HLog.i("$TAG 已安装，拦截入口=$hooked/${probes.size}")
    }

    private fun looksLikeProbe(name: String): Boolean {
        for (prefix in PROBE_PREFIXES) {
            if (name.startsWith(prefix)) return true
        }
        return false
    }

    private fun calledFromFrameworkOrSelf(): Boolean {
        val frames = Throwable().stackTrace
        val limit = minOf(frames.size, 16)
        for (i in 1 until limit) {
            val className = frames[i].className
            for (prefix in ALLOW_FRAME_PREFIXES) {
                if (className.startsWith(prefix)) return true
            }
        }
        return false
    }
}
