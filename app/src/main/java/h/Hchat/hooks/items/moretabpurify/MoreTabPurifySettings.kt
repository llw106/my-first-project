package h.Hchat.hooks.items.moretabpurify

import android.content.SharedPreferences

/**
 * 「页面净化」配置：控制微信「我」页面各入口的显示 / 隐藏。
 *
 * 存储语义用「隐藏集合」：hidden_items 里出现的标题，在我页不显示。
 * 开关语义按用户直觉：开 = 显示，关 = 隐藏。
 */
object MoreTabPurifySettings {
    const val PREFS_NAME = "Hchat_more_tab_purify_config"

    const val KEY_ENABLE = "more_tab_purify_enable"
    const val KEY_HIDDEN = "hidden_items"
    const val KEY_DISCOVERED = "discovered_items"

    const val DEFAULT_ENABLE = true

    /** 「设置」永远保留，关掉会导致无法进入设置 */
    const val PROTECTED_ITEM = "设置"

    /**
     * 内置候选条目：按微信 8.0.80 实际观测到的顺序。
     *
     * 实测「我」页真实条目为：
     * 个人信息 / 服务 / 收藏 / 朋友圈 / 作品 / 视频号 / 订单与卡包 / 小店与卡包 / 表情 / 设置 / WeUI
     */
    val KNOWN_ITEMS = listOf(
        "个人信息", "服务", "收藏", "朋友圈", "作品",
        "视频号", "订单与卡包", "小店与卡包", "表情"
    )

    /** 内部条目，不作为用户开关暴露 */
    val INTERNAL_ITEMS = listOf("WeUI")

    fun hidden(sp: SharedPreferences): Set<String> {
        return try {
            sp.getStringSet(KEY_HIDDEN, emptySet()) ?: emptySet()
        } catch (_: Throwable) {
            emptySet()
        }
    }

    fun setHidden(sp: SharedPreferences, item: String, hide: Boolean) {
        if (item == PROTECTED_ITEM) return
        val current = hidden(sp).toMutableSet()
        if (hide) current.add(item) else current.remove(item)
        try {
            sp.edit().putStringSet(KEY_HIDDEN, current).apply()
        } catch (_: Throwable) {
        }
    }

    fun isHidden(sp: SharedPreferences, item: String): Boolean {
        if (item == PROTECTED_ITEM) return false
        return hidden(sp).contains(item)
    }

    fun discovered(sp: SharedPreferences): List<String> {
        return try {
            (sp.getStringSet(KEY_DISCOVERED, emptySet()) ?: emptySet()).toList()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun rememberDiscovered(sp: SharedPreferences, items: Collection<String>) {
        if (items.isEmpty()) return
        try {
            val current = (sp.getStringSet(KEY_DISCOVERED, emptySet()) ?: emptySet()).toMutableSet()
            val before = current.size
            current.addAll(items)
            if (current.size != before) {
                sp.edit().putStringSet(KEY_DISCOVERED, current).apply()
            }
        } catch (_: Throwable) {
        }
    }

    fun allItems(sp: SharedPreferences): List<String> {
        val list = ArrayList<String>()
        for (item in KNOWN_ITEMS) list.add(item)
        for (item in discovered(sp)) {
            if (item == PROTECTED_ITEM) continue
            if (INTERNAL_ITEMS.contains(item)) continue
            if (!list.contains(item)) list.add(item)
        }
        return list
    }
}
