package h.Hchat.hooks.items.mytabswitch

import android.content.Context
import h.Hchat.dexkit.DexBridgeHolder
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import org.luckypray.dexkit.query.FindClass
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.ClassMatcher

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
        val fieldTypes = moreTab.declaredFields
            .mapNotNull { runCatching { it.type }.getOrNull() }
            .distinct()

        // 老版本：字段类型直接就是容器实现类。
        var screen = fieldTypes.firstOrNull { hasContainerShape(it) }

        // 8.0.80 起这个字段的类型换成了空接口（实测 com.tencent.mm.ui.base.preference.q
        // 只有类声明一行、连一个方法都没有），真正实现在别处。这里按「谁实现了这个接口」再找。
        if (screen == null) {
            for (iface in fieldTypes.filter { it.isInterface }) {
                val impl = findContainerImplementation(holder, classLoader, iface) ?: continue
                screen = impl
                break
            }
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

    /**
     * 容器类的结构特征，跨版本稳定：
     *`h(String)` 按 key 取条目、`i()` 取全部条目、`u(Preference)` 移除条目。
     */
    private fun hasContainerShape(c: Class<*>): Boolean {
        if (c.isInterface || java.lang.reflect.Modifier.isAbstract(c.modifiers)) return false
        val methods = runCatching { c.methods }.getOrNull() ?: return false
        val hasGet = methods.any {
            it.name == "h" && it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == String::class.java && it.returnType != Void.TYPE
        }
        val hasRemove = methods.any {
            it.parameterTypes.size == 1 && it.returnType == java.lang.Boolean.TYPE
        }
        val hasList = methods.any {
            it.parameterTypes.isEmpty() && List::class.java.isAssignableFrom(it.returnType)
        }
        return hasGet && hasRemove && hasList
    }

    /** 按接口找实现类，再用容器特征筛出真正那个。 */
    private fun findContainerImplementation(
        holder: DexBridgeHolder,
        classLoader: ClassLoader,
        iface: Class<*>
    ): Class<*>? {
        val bridge = runCatching { holder.dexKitBridge }.getOrNull() ?: return null
        val names = LinkedHashSet<String>()
        val candidates = listOf(iface.name, "L" + iface.name.replace('.', '/') + ";")
        for (name in candidates) {
            val findClass = FindClass()
            val matcher = ClassMatcher()
            val applied = runCatching {
                matcher.addInterface(name, StringMatchType.Equals, false)
            }.isSuccess
            if (!applied) continue
            findClass.matcher(matcher)
            runCatching { bridge.findClass(findClass) }.getOrNull()?.forEach { names.add(it.name) }
        }
        if (names.isEmpty()) return null
        HLog.i("$TAG ${iface.name} 的实现类 ${names.size} 个: ${names.take(5).joinToString(",")}")
        return names.asSequence()
            .mapNotNull { runCatching { classLoader.loadClass(it) }.getOrNull() }
            .firstOrNull { hasContainerShape(it) }
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
