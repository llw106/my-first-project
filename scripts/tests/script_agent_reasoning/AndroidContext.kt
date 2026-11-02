package android.content

open class Context

interface SharedPreferences {
    fun getString(key: String, defaultValue: String?): String?
    fun getBoolean(key: String, defaultValue: Boolean): Boolean
    fun getInt(key: String, defaultValue: Int): Int
    fun edit(): Editor
    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun putInt(key: String, value: Int): Editor
        fun apply()
    }
}
