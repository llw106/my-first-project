package de.robv.android.xposed

open class XC_MethodHook {
    open fun afterHookedMethod(param: MethodHookParam) = Unit
    class MethodHookParam(val thisObject: Any, var result: Any?, val throwable: Throwable? = null) {
        fun hasThrowable() = throwable != null
    }
    class Unhook(val callback: XC_MethodHook)
}
object XposedBridge { fun log(message: String) = Unit }
