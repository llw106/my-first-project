package de.robv.android.xposed
open class XC_MethodHook(priority: Int) {
    open fun beforeHookedMethod(param: MethodHookParam) {}
    class MethodHookParam { var args: Array<Any?>? = null; var result: Any? = null }
}
