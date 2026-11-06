package h.Hchat.hooks.items.mytabswitch

import de.robv.android.xposed.XC_MethodHook
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.hooks.items.adapt.AdaptState
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import org.xmlpull.v1.XmlPullParser

private const val TAG = "[Hchat:MyTabSwitch]"

/**
 * 我页条目：照「发现页管理」的思路，「我」页每个入口一个独立开关。
 *
 * 与旧「页面净化」的区别（也是这一版要验证的关键）：
 *  - 旧版在 g0.e（逐个添加）里拦截，每次 add 都要反射判断，冷启动时页面构建期被反复打断；
 *  - 新版在 inflater 的 c(XmlPullParser, q, boolean) 返回后一次性清理。
 *
 * 微信 8.0.80 的 g0.g(int) 长这样：
 *
 *     s = true
 *     inflater.c(parser, this, true)   // 批量 inflate，期间 s=true，不刷新
 *     parser.close()
 *     s = false
 *     listView.setAdapter(this)        // 到这一步才第一次上屏
 *     notifyDataSetChanged()
 *
 * 也就是说 c() 返回时条目还没进 adapter，这时候摘掉等于"从未创建"：
 * 既不会先显示再消失，也不会触发条目的加载/红点逻辑。
 */
class MyTabSwitchFeature : BaseFeature() {

    override fun featureId(): String = ID

    override fun name(): String = "我页条目"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(MyTabSwitchSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        val hooker = MyTabSwitchHooker(context)
        if (hooker.install()) return
        DexInstallScheduler.schedule(ID, name()) { hooker.install() }
    }

    companion object {
        const val ID = "my_tab_switch"
    }
}

private class MyTabSwitchHooker(
    private val context: FeatureContext
) {

    private val sp = HchatStorage.preferences(
        context.hostContext(),
        MyTabSwitchSettings.PREFS_NAME
    )

    @Volatile
    private var installed = false

    /** 影子表：key -> 被隐藏的 Preference 实例，微信按 key 回取时兜底，避免 NPE。 */
    private val shadow = java.util.concurrent.ConcurrentHashMap<String, Any>()

    /** 上一次打过的条目快照签名，避免每次进「我」页都刷日志。 */
    @Volatile
    private var lastSnapshot = ""

    private val prefClassRef by lazy {
        val name = locatedRef?.pref ?: "com.tencent.mm.ui.base.preference.Preference"
        runCatching { context.hostClassLoader().loadClass(name) }.getOrNull()
    }

    /** 优先用「安装后适配」扫出来的类名，没有再退回已知类名（老行为，保证可用）。 */
    private val locatedRef by lazy {
        val ctx = context.hostContext()
        val cl = context.hostClassLoader()
        MyTabDexFinder.load(ctx, AdaptState.runtimeKey(ctx, cl))
    }

    private val screenClassRef by lazy {
        val name = locatedRef?.screen ?: "com.tencent.mm.ui.base.preference.g0"
        runCatching { context.hostClassLoader().loadClass(name) }.getOrNull()
    }

    /** Preference.q 是条目 key（例：朋友圈 = settings_my_album）。 */
    private val keyFieldRef by lazy {
        prefClassRef?.let { runCatching { it.getField("q") }.getOrNull() }
    }

    /** Preference.h 是条目标题。 */
    private val titleFieldRef by lazy {
        prefClassRef?.let { runCatching { it.getField("h") }.getOrNull() }
    }

    @Synchronized
    fun install(): Boolean {
        if (installed) return true
        val screenClass = screenClassRef ?: return false
        val prefClass = prefClassRef ?: return false

        // inflater 不写死类名：g0 的字段 d 就是它的实例，直接取声明类型。
        val inflaterField = runCatching { screenClass.getDeclaredField("d") }.getOrNull() ?: return false
        val inflaterClass = inflaterField.type

        // 只认 c(XmlPullParser, q, boolean)：这是「解析整份 xml」的入口。
        val parseMethod = inflaterClass.declaredMethods.firstOrNull { m ->
            m.name == "c" &&
                m.parameterTypes.size == 3 &&
                m.parameterTypes[0] == XmlPullParser::class.java &&
                m.parameterTypes[2] == java.lang.Boolean.TYPE
        } ?: return false

        val hooked = runCatching {
            HookRegistry.get().hook(parseMethod, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    runCatching { onParsed(param.args.getOrNull(1), screenClass, prefClass) }
                        .onFailure { HLog.e("$TAG 应用开关异常: ${it.message}", it) }
                }
            })
            true
        }.getOrElse {
            HLog.e("$TAG 条目开关 Hook 失败: ${it.message}", it)
            false
        }
        if (!hooked) return false
        HLog.i("$TAG 条目开关 Hook 已安装 (${inflaterClass.name}.c，inflater 取自 g0.d)")

        // 兜底 1：条目被隐藏后，微信按 key 回取会拿到 null，这里把影子对象交回去。
        runCatching {
            val lookup = screenClass.getDeclaredMethod("h", String::class.java)
            HookRegistry.get().hook(lookup, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.result != null) return
                    val key = param.args.getOrNull(0) as? String ?: return
                    param.result = shadow[key] ?: return
                }
            })
            HLog.i("$TAG 查询兜底 Hook 已安装 (g0.h)")
        }.onFailure { HLog.e("$TAG 查询兜底 Hook 失败: ${it.message}", it) }
// 兜底 2：「朋友圈」条目被摘掉后，MoreTabUI.s1() 里 h("settings_my_album") 会拿到 null，
// 接着在 null 上调用方法就会 NPE。但 s1 同时负责刷新「设置」等入口的 NewTip 状态，
// 一旦跳过它，「设置」就不显示了。所以这里【不 hook s1】，改由上面的 g0.h 影子表兜底：
// s1 取朋友圈时拿到被摘下的原对象，既能正常执行完，也不会把朋友圈加回列表。


        installed = true
        return true
    }

    private fun onParsed(screenObj: Any?, screenClass: Class<*>, prefClass: Class<*>) {
        val screen = screenObj ?: return
        if (!MyTabSwitchSettings.isEnabled(sp)) return

        val list = runCatching {
            screen.javaClass.getMethod("i").invoke(screen) as? List<*>
        }.getOrNull() ?: return
        if (list.isEmpty()) return

        val keys = ArrayList<String>(list.size)
        val titles = ArrayList<String>(list.size)
        for (p in list) {
            if (p == null) continue
            keys.add(keyOf(p))
            titles.add(titleOf(p))
        }

        // 只认「我」页：key 里必有收藏，标题里必有个人信息。其它设置页直接放过。
        if (!keys.contains(MyTabSwitchSettings.ANCHOR_KEY) && !titles.contains(MyTabSwitchSettings.ANCHOR_TITLE)) {
            return
        }
        logSnapshot(keys, titles)

        val remove = runCatching { screenClass.getMethod("u", prefClass) }.getOrNull() ?: return
        var removed = 0
        val names = ArrayList<String>()
        for (p in list) {
            if (p == null) continue
            val key = keyOf(p)
            val item = MyTabSwitchSettings.match(key, titleOf(p)) ?: continue
            if (MyTabSwitchSettings.isShown(sp, item)) continue
            if (key.isNotEmpty()) shadow[key] = p
            val ok = runCatching { (remove.invoke(screen, p) as? Boolean) ?: false }.getOrDefault(false)
            if (ok) {
                removed++
                names.add(item.title)
            }
        }
        if (removed > 0) {
            HLog.i("$TAG 已关闭 $removed 项: ${names.joinToString("|")}")
        }
    }

    /** 首次（或条目变化时）打一次完整清单，方便按用户反馈校正 key。 */
    private fun logSnapshot(keys: List<String>, titles: List<String>) {
        val signature = keys.joinToString(",") + "#" + titles.joinToString(",")
        if (signature == lastSnapshot) return
        lastSnapshot = signature
        val sb = StringBuilder()
        for (i in keys.indices) {
            if (i > 0) sb.append(" | ")
            sb.append(titles.getOrNull(i).orEmpty()).append('=').append(keys.getOrNull(i).orEmpty())
        }
        HLog.i("$TAG 我页条目清单: $sb")
    }

    private fun keyOf(preference: Any): String {
        val field = keyFieldRef ?: return ""
        return runCatching { field.get(preference) as? String }.getOrNull().orEmpty()
    }

    private fun titleOf(preference: Any): String {
        val field = titleFieldRef ?: return ""
        val value = runCatching { field.get(preference) as? CharSequence }.getOrNull() ?: return ""
        return value.toString().trim()
    }
}