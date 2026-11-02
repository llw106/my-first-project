package h.Hchat.preferences

import android.content.Context

object HchatStorage {
    val store = MemoryPreferences()
    fun preferences(context: Context, name: String): MemoryPreferences = store
}

class MemoryPreferences {
    val values = mutableMapOf<String, Any?>()
    fun getString(key: String, defaultValue: String): String = values[key] as? String ?: defaultValue
    fun getBoolean(key: String, defaultValue: Boolean): Boolean = values[key] as? Boolean ?: defaultValue
    fun edit(): Editor = Editor()
    inner class Editor {
        private val pending = mutableMapOf<String, Any?>()
        fun putString(key: String, value: String): Editor = apply { pending[key] = value }
        fun putBoolean(key: String, value: Boolean): Editor = apply { pending[key] = value }
        fun commit(): Boolean { values.putAll(pending); return true }
    }
}
