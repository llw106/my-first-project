package h.Hchat.hooks.items.mytabswitch

import android.content.Context
import h.Hchat.dexkit.DexBridgeHolder
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog

/**
 * 「我页条目」的 DexKit 运行时定位。
 *
 * 为什么要它：微信每次升级都会重新混淆，`com.tencent.mm.ui.base.preference.g0`
 * 这种名字随时会变。但有两样东西不会变——
 *
 *   1. 「我」页条目用的字符串 key：`more_setting` / `settings_my_album` /
 *      `settings_my_finder_and_biz`（业务语义，改了就功能坏了）；
 *   2. 数据结构：我页 Fragment 持有一个 PreferenceGroup，这个 Group 上有
 *      `h(String)`（按 key 取条目）和 `u(Preference)`（移除条目）。
 *
 * 所以定位链是：
 *
 *   同时引用这 3 个 key 的类 ──(是 Fragment)──▶ 我页 Fragment
 *   Fragment 的字段类型里，同时有 h(String) / u(Preference) ──▶ PreferenceGroup
 *   h(String) 的返回类型 ──▶ Preference
 *
 * 全程不出现任何混淆名，微信换版本只要语义没变就还能定位。
 */
object MyTabDexFinder {

    private const val TAG = "[Hchat:MyTabDex]"

    const val PREFS = "Hchat_my_tab_dex_cache"

    private const val KEY_MORE_TAB = "cls.moreTab"
    private const val KEY_SCREEN = "cls.screen"
    private const val KEY_PREF = "cls.pref"
    private const val KEY_RUNTIME = "cls.runtime"

    /** 「我」页独有的三个 key，业务含义固定，不会被混淆。 */
    private val ANCHORS = arrayOf(
        "more_setting",
        "settings_my_album",
        "settings_my_finder_and_biz"
    )

    data class Located(
        val moreTab: String,
        val screen: String,
        val pref: String
    ) {
        fun toText(): String =
            "我页=${moreTab.substringAfterLast('.')} 列表=${screen.substringAfterLast('.')} 条目=${pref.substringAfterLast('.')}"
    }

    /**
     * 真正扫描 dex 定位。调用方必须放在后台线程（实测 1~3 秒）。
     */
    fun locate(holder: DexBridgeHolder, classLoader: ClassLoader): Located? {
        val candidates = runCatching { holder.findClassesByStrings(*ANCHORS) }.getOrNull() ?: emptyList()
        HLog.i("$TAG 候选类 ${candidates.size} 个: ${candidates.take(8).joinToString(",")}")
        if (candidates.isEmpty()) return null

        val androidxFragment = runCatching {
            classLoader.loadClass("androidx.fragment.app.Fragment")
        }.getOrNull()

        var moreTab: Class<*>? = null
        for (name in candidates) {
            val clazz = runCatching { classLoader.loadClass(name) }.getOrNull() ?: continue
            // 候选里还有一个纯数据类（插件 tab 配置表），用 Fragment 身份排掉。
            val isFragment = androidxFragment?.isAssignableFrom(clazz) == true
            if (!isFragment) continue
            moreTab = clazz
            break
        }
        if (moreTab == null) {
            HLog.e("$TAG 候选类里没有 Fragment，定位失败")
            return null
        }

        // 我页 Fragment 的字段里，那个「能按 key 取条目、也能移除条目」的就是列表容器。
        val screen = moreTab.declaredFields
            .mapNotNull { runCatching { it.type }.getOrNull() }
            .distinct()
            .firstOrNull { c ->
                val methods = runCatching { c.methods }.getOrNull() ?: emptyArray()
                val hasGet = methods.any {
                    it.name == "h" && it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == String::class.java &&
                        it.returnType != Void.TYPE
                }
                val hasRemove = methods.any {
                    it.parameterTypes.size == 1 && it.returnType == java.lang.Boolean.TYPE
                }
                hasGet && hasRemove
            }
        if (screen == null) {
            HLog.e("$TAG ${moreTab.name} 的字段里没找到条目容器，定位失败")
            return null
        }

        val getMethod = screen.methods.first {
            it.name == "h" && it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == String::class.java && it.returnType != Void.TYPE
        }
        val pref = getMethod.returnType
            ?: return null

        val located = Located(moreTab.name, screen.name, pref.name)
        HLog.i("$TAG 定位成功: ${located.toText()}")
        return located
    }

    fun save(context: Context, located: Located, runtimeKey: String) {
        HchatStorage.preferences(context, PREFS).edit()
            .putString(KEY_MORE_TAB, located.moreTab)
            .putString(KEY_SCREEN, located.screen)
            .putString(KEY_PREF, located.pref)
            .putString(KEY_RUNTIME, runtimeKey)
            .apply()
    }

    /**
     * 取缓存结果。runtimeKey 不匹配直接当没有（微信换包了）。
     */
    fun load(context: Context, runtimeKey: String): Located? {
        if (runtimeKey.isEmpty()) return null
        val p = HchatStorage.preferences(context, PREFS)
        if (p.getString(KEY_RUNTIME, "").orEmpty() != runtimeKey) return null
        val moreTab = p.getString(KEY_MORE_TAB, "").orEmpty()
        val screen = p.getString(KEY_SCREEN, "").orEmpty()
        val pref = p.getString(KEY_PREF, "").orEmpty()
        if (moreTab.isEmpty() || screen.isEmpty() || pref.isEmpty()) return null
        return Located(moreTab, screen, pref)
    }

    fun clear(context: Context) {
        runCatching { HchatStorage.preferences(context, PREFS).edit().clear().apply() }
    }
}
