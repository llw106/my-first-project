package de.robv.android.xposed;

public class XC_MethodHook {
    public static final class MethodHookParam {
        public Object thisObject;
        public Object[] args;
    }
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}
    public final void callBefore(MethodHookParam param) throws Throwable { beforeHookedMethod(param); }
    public final void callAfter(MethodHookParam param) throws Throwable { afterHookedMethod(param); }
}
