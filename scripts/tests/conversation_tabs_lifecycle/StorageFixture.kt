package h.Hchat.preferences

import android.content.Context
import android.content.SharedPreferences

object HchatStorage {
    val prefs = Preferences()
    fun preferences(context: Context, name: String) = prefs
    class Preferences : SharedPreferences {
        var registrations = 0
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
            registrations++
        }
    }
}
