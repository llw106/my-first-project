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
    const val KEY_MENU = "conversation_menu"

    const val DEFAULT_ENABLE = false
    const val DEFAULT_MENU = true

    private const val KEY_PREFIX = "wxid_"

    fun enabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_ENABLE, DEFAULT_ENABLE)

    fun menuEnabled(context: Context): Boolean =
        enabled(context) && preferences(context).getBoolean(KEY_MENU, DEFAULT_MENU)

    fun custom(context: Context, wxid: String?): String? {
        val id = wxid?.trim().orEmpty()
        if (id.isEmpty()) return null
        return preferences(context)
            .getString(KEY_PREFIX + id, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    fun hasCustom(context: Context, wxid: String?): Boolean = custom(context, wxid) != null

    fun configured(context: Context): List<Pair<String, String>> =
        preferences(context).all.entries
            .mapNotNull { (key, value) ->
                val text = (value as? String)?.trim().orEmpty()
                if (!key.startsWith(KEY_PREFIX) || text.isEmpty()) {
                    null
                } else {
                    key.removePrefix(KEY_PREFIX) to text
                }
            }
            .sortedBy { it.first }

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
