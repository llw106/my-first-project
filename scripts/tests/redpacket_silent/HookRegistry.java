package h.Hchat.hooks.core;

import de.robv.android.xposed.XC_MethodHook;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

public final class HookRegistry {
    private static final HookRegistry INSTANCE = new HookRegistry();
    private final Map<Method, List<XC_MethodHook>> hooks = new HashMap<>();
    public static HookRegistry get() { return INSTANCE; }
    public void clear() { hooks.clear(); }
    public void hook(Method method, XC_MethodHook hook) {
        hooks.computeIfAbsent(method, ignored -> new ArrayList<>()).add(hook);
    }
    public void fire(Object request, int error, String message, Object json) throws Throwable {
        Method method = request.getClass().getMethod("onGYNetEnd", int.class, String.class, JSONObject.class);
        List<XC_MethodHook> callbacks = hooks.get(method);
        if (callbacks == null) throw new AssertionError("Callback was not hooked: " + method);
        XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
        param.thisObject = request;
        param.args = new Object[]{error, message, json};
        for (XC_MethodHook callback : callbacks) callback.callBefore(param);
        method.invoke(request, param.args);
        for (int i = callbacks.size() - 1; i >= 0; i--) callbacks.get(i).callAfter(param);
    }
}
