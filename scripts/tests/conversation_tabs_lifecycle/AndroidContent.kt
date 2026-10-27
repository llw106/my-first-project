package android.content

open class Context {
    val packageName: String = "com.tencent.mm"
    val resources: Resources = Resources()
}
class Resources {
    var identifierLookups = 0
        private set
    fun getIdentifier(name: String, type: String, packageName: String): Int {
        identifierLookups++
        if (type != "id" || packageName != "com.tencent.mm") return 0
        return when (name) { "ei" -> 1001; "jlt" -> 1002; else -> 0 }
    }
}
interface SharedPreferences {
    fun interface OnSharedPreferenceChangeListener {
        fun onSharedPreferenceChanged(preferences: SharedPreferences, key: String?)
    }
    fun registerOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener)
}
