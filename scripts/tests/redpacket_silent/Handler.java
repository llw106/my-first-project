package android.os;

public final class Handler {
    private static final h.Hchat.hooks.api.runtime.WeChatTaskApi tasks = new h.Hchat.hooks.api.runtime.WeChatTaskApi();
    private static boolean uiClock;
    private static long next;
    public Handler(Looper looper) {}
    public static void reset() { uiClock = true; tasks.advanceBy(100000); next = 0; }
    public static void advanceBy(long elapsed) { tasks.advanceBy(elapsed); }
    public boolean postDelayed(Runnable task, long delay) {
        if (!uiClock) throw new AssertionError("Tests must use the controlled WeChatTaskApi clock");
        tasks.runOnMainDelayed("ui:" + next++, delay, task);
        return true;
    }
}
