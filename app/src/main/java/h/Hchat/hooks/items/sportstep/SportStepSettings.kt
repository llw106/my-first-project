package h.Hchat.hooks.items.sportstep

import android.content.Context
import h.Hchat.preferences.HchatStorage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SportStepSettings {
    const val PREFS_NAME = "Hchat_sport_step_config"
    const val KEY_ENABLE = "sport_step_enable"
    const val KEY_MODE = "sport_step_mode"
    const val KEY_VALUE = "sport_step_value"
    const val KEY_DAY = "sport_step_day"
    const val KEY_BASE = "sport_step_base"

    const val DEFAULT_ENABLE = false
    const val MODE_TARGET = 0
    const val MODE_MULTIPLY = 1
    const val DEFAULT_MODE = MODE_TARGET
    const val DEFAULT_VALUE = "80000"

    fun enabled(context: Context?): Boolean {
        if (context == null) return false
        return HchatStorage.preferences(context, PREFS_NAME)
            .getBoolean(KEY_ENABLE, DEFAULT_ENABLE)
    }

    fun mode(context: Context?): Int {
        if (context == null) return DEFAULT_MODE
        return HchatStorage.preferences(context, PREFS_NAME)
            .getInt(KEY_MODE, DEFAULT_MODE)
    }

    fun setMode(context: Context, mode: Int) {
        HchatStorage.preferences(context, PREFS_NAME).edit().putInt(KEY_MODE, mode).apply()
    }

    fun valueText(context: Context?): String {
        if (context == null) return DEFAULT_VALUE
        return HchatStorage.preferences(context, PREFS_NAME)
            .getString(KEY_VALUE, DEFAULT_VALUE) ?: DEFAULT_VALUE
    }

    fun value(context: Context?): Double {
        return valueText(context).toDoubleOrNull()?.takeIf { it > 0.0 } ?: DEFAULT_VALUE.toDouble()
    }

    fun setValue(context: Context, text: String) {
        HchatStorage.preferences(context, PREFS_NAME).edit().putString(KEY_VALUE, text).apply()
    }

    fun today(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    /**
     * 返回当天第一次看到的累计步数作为基准；跨天自动重置。
     * 微信的“今日步数”就是从当天基准开始累加的，只有基准对齐才能算出准确的目标值。
     */
    fun dayBase(context: Context, real: Float): Float {
        val sp = HchatStorage.preferences(context, PREFS_NAME)
        val day = today()
        val savedDay = sp.getString(KEY_DAY, "")
        val savedBase = sp.getFloat(KEY_BASE, -1f)
        if (savedDay == day && savedBase > 0f) return savedBase
        sp.edit().putString(KEY_DAY, day).putFloat(KEY_BASE, real).apply()
        return real
    }
}
