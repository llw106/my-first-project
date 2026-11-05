package h.Hchat.hooks.items.customnickname

import android.content.Context
import h.Hchat.preferences.HchatStorage

object CustomNicknameSettings {

    const val PREFS_NAME = "Hchat_custom_nickname_config"
    const val KEY_ENABLE = "enable"
    const val KEY_SCOPE_CONVERSATION = "scope_conversation"
    const val KEY_SCOPE_CHAT = "scope_chat"
    const val KEY_SCOPE_GROUP_MEMBER = "scope_group_member"
    const val KEY_CONVERSATION_MENU = "conversation_menu"

    const val DEFAULT_ENABLE = false
    const val DEFAULT_SCOPE = true
    const val DEFAULT_MENU = true

    private const val KEY_PREFIX = "nick_"

    fun enabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_ENABLE, DEFAULT_ENABLE)

    fun conversationMenuEnabled(context: Context): Boolean =
        enabled(context) && preferences(context).getBoolean(KEY_CONVERSATION_MENU, DEFAULT_MENU)

    fun scopeEnabled(context: Context, surface: NicknameSurface): Boolean {
        if (!enabled(context)) return false
        val key = when (surface) {
            NicknameSurface.CONVERSATION -> KEY_SCOPE_CONVERSATION
            NicknameSurface.CHAT -> KEY_SCOPE_CHAT
            NicknameSurface.GROUP_MEMBER -> KEY_SCOPE_GROUP_MEMBER
        }
        return preferences(context).getBoolean(key, DEFAULT_SCOPE)
    }

    fun nickname(context: Context, wxid: String?): String? {
        val id = wxid?.trim().orEmpty()
        if (id.isEmpty()) return null
        return preferences(context)
            .getString(KEY_PREFIX + id, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    fun displayName(
        context: Context,
        wxid: String?,
        surface: NicknameSurface,
        original: String?
    ): String? {
        val id = wxid?.trim().orEmpty()
        if (id.isEmpty()) return original
        if (!scopeEnabled(context, surface)) return original
        return nickname(context, id) ?: original
    }

    fun hasNickname(context: Context, wxid: String?): Boolean = nickname(context, wxid) != null

    fun setNickname(context: Context, wxid: String, value: String?) {
        val id = wxid.trim()
        if (id.isEmpty()) return
        val key = KEY_PREFIX + id
        val trimmed = value?.trim().orEmpty()
        preferences(context).edit().apply {
            if (trimmed.isEmpty()) remove(key) else putString(key, trimmed)
        }.apply()
    }

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
            .filter { (_, text) -> text.isNotEmpty() }
            .sortedBy { it.first }

    internal fun preferences(context: Context) = HchatStorage.preferences(context, PREFS_NAME)
}

enum class NicknameSurface {
    CONVERSATION,
    CHAT,
    GROUP_MEMBER
}
