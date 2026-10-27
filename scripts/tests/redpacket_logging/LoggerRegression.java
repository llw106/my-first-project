package h.Hchat.hooks.items.payment.core;

import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Method;

public final class LoggerRegression {
    private static int checks;

    private static void check(boolean passed, String message) {
        checks++;
        if (!passed) throw new AssertionError(message);
    }

    public static void main(String[] arguments) throws Exception {
        Class<?> logger;
        try {
            logger = Class.forName("h.Hchat.hooks.items.payment.core.RedPacketLogger");
        } catch (ClassNotFoundException missing) {
            throw new AssertionError("缺少红包日志入口", missing);
        }
        Method log = logger.getMethod("log", Object.class);
        Method error = logger.getMethod("error", String.class, Throwable.class);
        for (String message : new String[] {"诊断启动：总开关=true 模式=1", "规则忽略：非白名单",
                "消息观察：累计=1", "hookAll 完成", "进入静默模式, sendid=test",
                "请求对象已创建", "收红包响应", "拆红包完成"}) {
            log.invoke(null, message);
            check(XposedBridge.messages.isEmpty(), "正常流程不得输出调试日志：" + message);
            check(XposedBridge.errors.isEmpty(), "普通状态不能误报错误：" + message);
        }
        for (String message : new String[] {"ERROR 原始错误", "安装失败: 原始原因", "未找到组件", "接口不可用", "无合适方法", "回调异常"}) {
            int previous = XposedBridge.errors.size();
            log.invoke(null, message);
            check(XposedBridge.errors.size() == previous + 1, "真实错误必须写入 LSPosed 错误日志：" + message);
            check(XposedBridge.errors.get(previous).getMessage().equals("[Hchat:RedPacket] " + message), "错误原文必须完整保留");
        }
        IllegalStateException cause = new IllegalStateException("实际错误文本", new IllegalArgumentException("底层原因"));
        error.invoke(null, "消息观察安装失败", cause);
        Throwable emitted = XposedBridge.errors.get(XposedBridge.errors.size() - 1);
        check(emitted.getCause() == cause, "异常和堆栈不能只剩 getMessage");
        check(emitted.getCause().getCause() == cause.getCause(), "嵌套原因必须保留");
        log.invoke(null, cause);
        check(XposedBridge.errors.get(XposedBridge.errors.size() - 1).getCause() == cause, "Throwable 日志必须按异常输出");
        int messageCount = XposedBridge.messages.size();
        int errorCount = XposedBridge.errors.size();
        log.invoke(null, new Object[] {null});
        check(XposedBridge.messages.size() == messageCount && XposedBridge.errors.size() == errorCount, "空日志不得产生噪声");
        System.out.println("红包日志回归通过，断言=" + checks);
    }
}
