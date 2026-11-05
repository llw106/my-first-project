package h.Hchat.utils;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XposedBridge;

/**
 * 模块日志。
 *
 * LSPosed treats Throwable logs as error logs：e() 走异常路径，会填充调用栈，开销不小。
 * 因此真正的失败用 e()，诊断/高频/重复输出用 i() 或 once()（不建异常、不抓栈）。
 */
public final class HLog {
    private static final Set<String> ONCE_KEYS = ConcurrentHashMap.newKeySet();

    private HLog() {
    }

    /** 普通日志：不构造异常、不抓栈，可放在高频路径。 */
    public static void i(String message) {
        if (message == null) return;
        try {
            XposedBridge.log("[Hchat] " + message);
        } catch (Throwable ignored) {
        }
    }

    /** 同一个 key 只输出一次，用于重试/重复安装这类必然刷屏的诊断。 */
    public static void once(String key, String message) {
        if (key == null) {
            i(message);
            return;
        }
        if (!ONCE_KEYS.add(key)) return;
        i(message);
    }

    /** 失败日志（error 级，带调用栈）。 */
    public static void e(String message) {
        XposedBridge.log(new RuntimeException(message));
    }

    public static void e(String message, Throwable throwable) {
        if (throwable == null) {
            e(message);
            return;
        }
        XposedBridge.log(new RuntimeException(message, throwable));
    }
}
