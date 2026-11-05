package h.Hchat.hooks.items.profilewxid

import android.content.Context
import h.Hchat.preferences.HchatStorage

/**
 * 资料页微信号的自定义/隐藏配置。
 * 只影响本机显示，不写入微信任何数据。
 */
object ProfileWxidSettings {

    const val PREFS_NAME = "Hchat_profile_wxid_config"
    const val KEY_ENABLE = "enable"
    const val KEY_HIDE = "hide_when_no_custom"
    const val KEY_MENU = "conversation_menu"

    const val DEFAULT_ENABLE = false
    const val DEFAULT_HIDE = true
    const val DEFAULT_MENU = true

    private const val KEY_PREFIX = "wxid_"

    fun enabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_ENABLE, DEFAULT_ENABLE)

    fun menuEnabled(context: Context): Boolean =
        enabled(context) && preferences(context).getBoolean(KEY_MENU, DEFAULT_MENU)

    /** 没有自定义值时，是否直接把原生微信号整行隐藏。 */
    fun hideWhenNoCustom(context: Context): Boolean =
        preferences(context).getBoolean(KEY_HIDE, DEFAULT_HIDE)

    fun custom(context: Context, wxid: String?): String? {
        val id = wxid?.trim().orEmpty()
        if (id.isEmpty()) return null
        return preferences(context)
            .getString(KEY_PREFIX + id, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    fun hasCustom(context: Context, wxid: String?): Boolean = custom(context, wxid) != null

    fun setCustom(context: Context, wxid: String, value: String?) {
        val id = wxid.trim()
        if (id.isEmpty()) return
        val key = KEY_PREFIX + id
        val trimmed = value?.trim().orEmpty()
        preferences(context).edit().apply {
            if (trimmed.isEmpty()) remove(key) else putString(key, trimmed)
        }.apply()
    }

    internal fun preferences(context: Context) = HchatStorage.preferences(context, PREFS_NAME)
}
