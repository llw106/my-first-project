package h.Hchat.hooks.items.mytabswitch

import android.content.SharedPreferences

/**
 * 「我页条目」配置：像「发现页管理」那样，给微信「我」页每个入口一个独立开关。
 *
 * 语义与发现页一致：
 *  - 开 = 显示
 *  - 关 = 页面 inflate 完成、adapter 设置之前就把条目从列表里摘掉，等于从未创建，
 *        不会出现"先显示再消失"，也不会再触发条目的任何加载逻辑。
 */
object MyTabSwitchSettings {

    const val PREFS_NAME = "Hchat_my_tab_switch_config"
    const val KEY_ENABLE = "my_tab_switch_enable"
    const val DEFAULT_ENABLE = true

    /** 「设置」永远保留，关掉会导致无法进入设置。 */
    const val PROTECTED_TITLE = "设置"

    /**
     * 单个条目。
     *
     * @param title 用户看到的标题（也是 key 缺失时的兜底匹配依据）
     * @param prefsKey 该条目的开关存储键
     * @param summary 设置页里的说明
     * @param keys 微信 8.0.80 实测到的 Preference key
     */
    class Item(
        val title: String,
        val prefsKey: String,
        val summary: String,
        val keys: List<String> = emptyList()
    )

    /** 按「我」页实际显示顺序排列。 */
    val ITEMS: List<Item> = listOf(
        Item("服务", "show_service", "关闭后「我」页不显示服务（8.0.80 中显示为「支付」）", listOf("settings_mm_wallet", "settings_service", "settings_mm_service")),
        Item("收藏", "show_favorite", "关闭后「我」页不显示收藏", listOf("settings_mm_favorite")),
        Item("朋友圈", "show_sns", "关闭后「我」页不显示朋友圈", listOf("settings_my_album")),
        Item("视频号", "show_finder", "关闭后「我」页不显示视频号", listOf("settings_my_finder_album", "settings_my_finder_and_biz")),
        Item("作品", "show_finder_work", "关闭后「我」页不显示作品（部分版本没有该入口）", listOf("settings_my_finder_work")),
        Item("订单与卡包", "show_cardpackage", "关闭后「我」页不显示订单与卡包", listOf("settings_mm_cardpackage")),
        Item("小店与卡包", "show_cardpackage_new", "关闭后「我」页不显示小店与卡包（8.0.80 中显示为「卡包」）", listOf("settings_mm_cardpackage_new")),
        Item("表情", "show_emoji_store", "关闭后「我」页不显示表情", listOf("settings_emoji_store"))
    )

    /** 内部条目，不作为开关暴露。 */
    val INTERNAL_TITLES = listOf("WeUI")

    /** 「我」页判定锚点：收藏的 key，或个人信息标题。 */
    const val ANCHOR_KEY = "settings_mm_favorite"
    const val ANCHOR_TITLE = "个人信息"

    /** 「朋友圈」条目 key：MoreTabUI.s1() 会强转它，摘掉后必须跳过 s1。 */
    const val ALBUM_KEY = "settings_my_album"

    fun isEnabled(sp: SharedPreferences): Boolean = try {
        sp.getBoolean(KEY_ENABLE, DEFAULT_ENABLE)
    } catch (_: Throwable) {
        DEFAULT_ENABLE
    }

    fun isShown(sp: SharedPreferences, item: Item): Boolean = try {
        sp.getBoolean(item.prefsKey, true)
    } catch (_: Throwable) {
        true
    }

    /** 条目归属：key 精确命中优先，标题兜底；返回 null 表示不归本功能管。 */
    fun match(key: String, title: String): Item? {
        if (key.isNotEmpty()) {
            for (item in ITEMS) {
                if (item.keys.contains(key)) return item
            }
        }
        if (title.isEmpty()) return null
        if (title == PROTECTED_TITLE) return null
        if (INTERNAL_TITLES.contains(title)) return null
        for (item in ITEMS) {
            if (item.title == title) return item
        }
        return null
    }
}
