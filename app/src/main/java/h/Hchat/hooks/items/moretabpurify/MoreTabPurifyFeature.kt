package h.Hchat.hooks.items.moretabpurify

import android.os.Bundle
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog

private const val TAG = "[Hchat:MoreTabPurify]"

/**
 * 页面净化：像「发现页管理」一样，给微信「我」页面的入口做显示开关。
 *
 * 微信 8.0.80 的「我」页是 com.tencent.mm.ui.MoreTabUI，继承
 * com.tencent.mm.ui.base.preference.MMPreferenceFragment，
 * 页面本体是 g0（PreferenceScreen 实现）里的 preference 列表：
 *  - g0.i()          取全部条目
 *  - g0.u(Preference) 移除条目并刷新
 *  - Preference.h   条目标题（CharSequence）
 *
 * 因此页面加载完成后按标题移除即可，对其它设置页无影响。
 */
class MoreTabPurifyFeature : BaseFeature() {
    override fun featureId(): String = ID
    override fun name(): String = "页面净化"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(MoreTabPurifySettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        val hooker = MoreTabPurifyHooker(context)
        if (hooker.install()) return
        DexInstallScheduler.schedule(ID, name()) { hooker.install() }
    }

    companion object {
        const val ID = "more_tab_purify"
    }
}

private class MoreTabPurifyHooker(
    private val context: FeatureContext
) {
    private val prefs = HchatStorage.preferences(
        context.hostContext(),
        MoreTabPurifySettings.PREFS_NAME
    )

    @Volatile
    private var installed = false

    /**
     * 影子表：key -> 被隐藏的 Preference 实例。
     *
     * 微信「我」页里有些逻辑会按 key 从列表回取条目（典型：MoreTabUI.s1() 取
     * "settings_my_album" 来刷新朋友圈红点）。条目被隐藏后这些调用会拿到 null 并
     * 直接 NPE 崩溃，所以移除前留一份影子，查询兜底时把它交回去。
     */
    private val shadow = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /** 已确认属于「我」页的 PreferenceScreen 实例，用于限定添加拦截的作用范围。 */
    private val moreTabScreens: MutableSet<Any> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<Any, Boolean>())

    @Synchronized
    fun install(): Boolean {
        if (installed) return true
        val classLoader = context.hostClassLoader()
        val fragmentClass = runCatching {
            classLoader.loadClass("com.tencent.mm.ui.base.preference.MMPreferenceFragment")
        }.getOrNull() ?: return false

        val activityCreated = runCatching {
            fragmentClass.getDeclaredMethod("onActivityCreated", Bundle::class.java)
        }.getOrNull()
        val resume = runCatching {
            fragmentClass.getDeclaredMethod("onResume")
        }.getOrNull()
        if (activityCreated == null && resume == null) return false

        val callback = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                runCatching { purify(param.thisObject) }
                    .onFailure { HLog.e("$TAG 处理异常: ${it.message}", it) }
            }
        }

        // 前置：条目在添加当场就被拦下，冷启动打开「我」页时不再"先显示再消失"。
        runCatching {
            val screenClass = classLoader.loadClass("com.tencent.mm.ui.base.preference.g0")
            val prefClass = classLoader.loadClass("com.tencent.mm.ui.base.preference.Preference")
            val moreTabClass = classLoader.loadClass("com.tencent.mm.ui.MoreTabUI")
            // 登记「我」页的 screen 实例：构造时 + 每次 onTabResume(F0) 进入前。
            HookRegistry.get().hook(moreTabClass.getDeclaredConstructor(), object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) = rememberScreen(param.thisObject)
            })
            HookRegistry.get().hook(moreTabClass.getDeclaredMethod("F0"), object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) = rememberScreen(param.thisObject)
            })
            // g0.e(Preference, index) 才是真正的"添加条目"入口（g0.d 只是包一层）。
            val add = screenClass.getDeclaredMethod("e", prefClass, Integer.TYPE)
            HookRegistry.get().hook(add, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (!enabled()) return
                    val preference = param.args.getOrNull(0) ?: return
                    val title = titleOf(preference)
                    if (title.isEmpty()) return
                    if (title == MoreTabPurifySettings.PROTECTED_ITEM) return
                    // 「我」页条目 key 都是 settings_ 前缀（settings_mm_favorite / settings_my_album …）。
                    // 不靠 screen 实例登记，因为条目是在 screen 构造之后才逐个添加的。
                    val key = keyOf(preference)
                    if (key.isEmpty() || !key.startsWith("settings_")) return
                    if (!MoreTabPurifySettings.hidden(prefs).contains(title)) return
                    if (shadow.put(key, preference) == null) {
                        HLog.i("$TAG 添加时拦截: $title ($key)")
                    }
                    param.result = null
                }
            })
            HLog.i("$TAG 添加时拦截 Hook 已安装 (g0.e)")
        }.onFailure { HLog.e("$TAG 添加时拦截 Hook 失败: ${it.message}", it) }

        // 关键：屏幕对象构造完成时条目就已经从 XML 灌进来了，这里立刻清理，赶在第一帧之前。
        runCatching {
            val screenClass = classLoader.loadClass("com.tencent.mm.ui.base.preference.g0")
            HookRegistry.get().hook(screenClass.declaredConstructors.first(), object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching { purifyScreen(param.thisObject) }
                        .onFailure { HLog.e("$TAG 构造期清理异常: ${it.message}", it) }
                }
            })
            HLog.i("$TAG 构造期清理 Hook 已安装 (g0.<init>)")
        }.onFailure { HLog.e("$TAG 构造期清理 Hook 失败: ${it.message}", it) }

        // 兜底 1：微信按 key 回取条目时，若已被隐藏则把影子对象交回去，避免 NPE。
        runCatching {
            val screenClass = classLoader.loadClass("com.tencent.mm.ui.base.preference.g0")
            val lookup = screenClass.getDeclaredMethod("h", String::class.java)
            HookRegistry.get().hook(lookup, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.result != null) return
                    val key = param.args.getOrNull(0) as? String ?: return
                    val spare = shadow[key] ?: return
                    param.result = spare
                }
            })
            HLog.i("$TAG 查询兜底 Hook 已安装 (g0.h)")
        }.onFailure { HLog.e("$TAG 查询兜底 Hook 失败: ${it.message}", it) }

        // 兜底 2：MoreTabUI.s1() 只刷新「朋友圈」红点，条目隐藏后对象缺失会崩，直接跳过。
        runCatching {
            val moreTabClass = classLoader.loadClass("com.tencent.mm.ui.MoreTabUI")
            val s1 = moreTabClass.getDeclaredMethod("s1")
            HookRegistry.get().hook(s1, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.result = null
                }
            })
            HLog.i("$TAG 红点刷新跳过 Hook 已安装 (MoreTabUI.s1)")
        }.onFailure { HLog.e("$TAG MoreTabUI.s1 跳过失败: ${it.message}", it) }

        return runCatching {
            activityCreated?.let { HookRegistry.get().hook(it, callback) }
            resume?.let { HookRegistry.get().hook(it, callback) }
            installed = true
            HLog.e("$TAG 我页面板 Hook 已安装 (activityCreated=${activityCreated != null}, resume=${resume != null})")
            true
        }.getOrElse {
            HLog.e("$TAG Hook 安装失败: ${it.message}", it)
            false
        }
    }

    private fun enabled(): Boolean =
        prefs.getBoolean(MoreTabPurifySettings.KEY_ENABLE, MoreTabPurifySettings.DEFAULT_ENABLE)

    private fun rememberScreen(fragment: Any?) {
        if (fragment == null) return
        runCatching {
            val screen = screenOf(fragment) ?: return
            moreTabScreens.add(screen)
        }
    }

    private fun purify(fragment: Any?) {
        if (fragment == null) return
        if (!fragment.javaClass.name.endsWith("MoreTabUI")) return
        purifyScreen(screenOf(fragment))
    }

    private fun purifyScreen(screen: Any?) {
        if (screen == null) return
        if (!enabled()) {
            return
        }

        val items = preferenceList(screen) ?: return
        if (items.isEmpty()) return

        val titles = ArrayList<String>()
        val hiddenTitles = ArrayList<String>()
        val toRemove = ArrayList<Any>()
        val hidden = MoreTabPurifySettings.hidden(prefs)

        for (item in items) {
            if (item == null) continue
            val title = titleOf(item)
            if (title.isEmpty()) continue
            titles.add(title)
            if (title == MoreTabPurifySettings.PROTECTED_ITEM) continue
            if (hidden.contains(title)) {
                toRemove.add(item)
                hiddenTitles.add(title)
            }
        }

        // 只认「我」页：同时含「个人信息」与「设置」这两项。其它设置页直接放过。
        if (!titles.contains("个人信息") || !titles.contains(MoreTabPurifySettings.PROTECTED_ITEM)) return

        moreTabScreens.add(screen)
        MoreTabPurifySettings.rememberDiscovered(prefs, titles)

        if (toRemove.isEmpty()) return
        var removed = 0
        val recordedKeys = ArrayList<String>()
        for (item in toRemove) {
            val key = keyOf(item)
            if (key.isNotEmpty()) {
                shadow[key] = item
                recordedKeys.add(key)
            }
            if (removePreference(screen, item)) removed++
        }
        HLog.e("$TAG 我页隐藏 $removed/${toRemove.size} 项 (${hiddenTitles.joinToString("|")})，key=${recordedKeys.joinToString("|")}，当前条目=${titles.joinToString("|")}")
    }

    private fun screenOf(fragment: Any): Any? {
        var cls: Class<*>? = fragment.javaClass
        while (cls != null && cls != Any::class.java) {
            val field = runCatching { cls.getDeclaredField("d") }.getOrNull()
            if (field != null) {
                runCatching { field.isAccessible = true }
                val value = runCatching { field.get(fragment) }.getOrNull()
                if (value != null) return value
            }
            cls = cls.superclass
        }
        return null
    }

    private fun preferenceList(screen: Any): List<*>? {
        return runCatching {
            screen.javaClass.getMethod("i").invoke(screen) as? List<*>
        }.getOrNull()
    }

    /** Preference.q 是微信条目的 key（例：朋友圈 = settings_my_album）。 */
    private fun keyOf(preference: Any): String {
        val field = runCatching { preference.javaClass.getField("q") }.getOrNull() ?: return ""
        return runCatching { field.get(preference) as? String }.getOrNull().orEmpty()
    }

    private fun titleOf(preference: Any): String {
        val field = runCatching { preference.javaClass.getField("h") }.getOrNull() ?: return ""
        val value = runCatching { field.get(preference) as? CharSequence }.getOrNull() ?: return ""
        return value.toString().trim()
    }

    private fun removePreference(screen: Any, preference: Any): Boolean {
        val type = runCatching {
            context.hostClassLoader()
                .loadClass("com.tencent.mm.ui.base.preference.Preference")
        }.getOrNull() ?: return false
        val method = runCatching {
            screen.javaClass.getMethod("u", type)
        }.getOrNull() ?: return false
        return runCatching { (method.invoke(screen, preference) as? Boolean) ?: false }
            .getOrDefault(false)
    }
}
