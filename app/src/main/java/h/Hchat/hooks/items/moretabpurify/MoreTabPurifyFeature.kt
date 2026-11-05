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

    private fun purify(fragment: Any?) {
        if (fragment == null) return
        if (!fragment.javaClass.name.endsWith("MoreTabUI")) return
        if (!prefs.getBoolean(MoreTabPurifySettings.KEY_ENABLE, MoreTabPurifySettings.DEFAULT_ENABLE)) {
            return
        }

        val screen = screenOf(fragment) ?: return
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

        MoreTabPurifySettings.rememberDiscovered(prefs, titles)

        if (toRemove.isEmpty()) return
        var removed = 0
        for (item in toRemove) {
            if (removePreference(screen, item)) removed++
        }
        HLog.e("$TAG 我页隐藏 $removed/${toRemove.size} 项 (${hiddenTitles.joinToString("|")})，当前条目=${titles.joinToString("|")}")
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
