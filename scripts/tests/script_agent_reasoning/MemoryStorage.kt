package h.Hchat.preferences

import android.content.Context
import android.content.SharedPreferences

object HchatStorage {
    val store = MemoryPreferences()
    fun preferences(context: Context, name: String): SharedPreferences = store
}

class MemoryPreferences : SharedPreferences {
    val values = mutableMapOf<String, Any?>()
    override fun getString(key: String, defaultValue: String?): String? = values[key] as? String ?: defaultValue
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = values[key] as? Boolean ?: defaultValue
    override fun getInt(key: String, defaultValue: Int): Int = values[key] as? Int ?: defaultValue
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { pending[key] = value }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { pending[key] = value }
        override fun apply() { values.putAll(pending) }
    }
}
