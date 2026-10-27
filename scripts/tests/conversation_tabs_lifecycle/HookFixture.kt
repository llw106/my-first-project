package h.Hchat.hooks.core

import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import java.lang.reflect.Method

class FeatureContext {
    fun hostClassLoader() = javaClass.classLoader!!
    fun hostContext() = Context()
}
class HookRegistry private constructor() {
    private val hooks = linkedMapOf<Method, MutableList<XC_MethodHook.Unhook>>()
    var failOn: String? = null
    var unhooks = 0
        private set
    val count get() = hooks.values.sumOf { it.size }
    fun hook(method: Method, callback: XC_MethodHook): XC_MethodHook.Unhook {
        if (method.name == failOn) error("Simulated hook failure: ${method.name}")
        return XC_MethodHook.Unhook(callback).also { hooks.getOrPut(method) { arrayListOf() }.add(it) }
    }
    fun unhook(hook: XC_MethodHook.Unhook) { hooks.values.forEach { it.remove(hook) }; unhooks++ }
    fun invoke(owner: Any, method: Method): Any? {
        val result = method.invoke(owner)
        val param = XC_MethodHook.MethodHookParam(owner, result)
        hooks[method]?.toList()?.forEach { it.callback.afterHookedMethod(param) }
        return param.result
    }
    fun dispatch(owner: Any, method: Method, result: Any?, error: Throwable? = null) {
        val param = XC_MethodHook.MethodHookParam(owner, result, error)
        hooks[method]?.toList()?.forEach { it.callback.afterHookedMethod(param) }
    }
    fun reset() { hooks.clear(); failOn = null; unhooks = 0 }
    companion object { private val registry = HookRegistry(); fun get() = registry }
}
