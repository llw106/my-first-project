package h.Hchat.hooks.items.profilewxid

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.utils.KavaReflector
import java.lang.reflect.Method
import java.util.ArrayList

/**
 * 好友/群成员资料页的微信号显示层替换。
 * 只改已经渲染出来的 TextView，不动 Preference 数据，也不写微信数据库。
 */
class ProfileWxidRuntime(
    private val context: FeatureContext,
    private val logger: (String, Throwable?) -> Unit
) {

    private val unhooks = ArrayList<XC_MethodHook.Unhook>()
    private var installed = false

    fun install() {
        if (installed) return
        installed = true
        hookProfileActivity()
    }

    fun destroy() {
        unhooks.forEach { runCatching { it.unhook() } }
        unhooks.clear()
        installed = false
    }

    private fun hookProfileActivity() {
        val loader = context.hostClassLoader()
        if (loader == null) {
            log("hostClassLoader 为空", null)
            return
        }
        val clazz = KavaReflector.loadClass(CONTACT_INFO_UI, loader)
        if (clazz == null) {
            log("未找到 $CONTACT_INFO_UI", null)
            return
        }
        val method = KavaReflector.findMethodRecursive(clazz, "initView")
        if (method == null) {
            log("未找到 ${clazz.name}#initView", null)
            return
        }
        hook(method, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val activity = param.thisObject as? Activity ?: return
                runCatching { scheduleApply(activity) }
                    .onFailure { logger("资料页微信号处理失败", it) }
            }
        })
        log("已挂载 ${clazz.name}#initView", null)
    }

    private fun hook(method: Method, callback: XC_MethodHook) {
        runCatching { HookRegistry.get().hook(method, callback) }
            .onSuccess { unhooks += it }
            .onFailure { logger("hook ${method.name} 失败", it) }
    }

    private fun scheduleApply(activity: Activity, attempt: Int = 0) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        decor.postDelayed({
            if (activity.isFinishing || activity.isDestroyed) return@postDelayed
            val done = runCatching { applyNow(activity) }
                .onFailure { logger("重写微信号失败", it) }
                .getOrDefault(false)
            if (!done && attempt < MAX_ATTEMPTS) {
                scheduleApply(activity, attempt + 1)
            }
        }, if (attempt == 0) 0L else RETRY_DELAY_MS)
    }

    /** @return true 表示已经处理完毕（成功改写或无需处理），false 表示页面还没渲染出来。 */
    private fun applyNow(activity: Activity): Boolean {
        val host = activity.applicationContext ?: return true
        if (!ProfileWxidSettings.enabled(host)) return true
        val decor = activity.window?.decorView as? ViewGroup ?: return true
        val wxid = resolveWxid(activity)
        val custom = ProfileWxidSettings.custom(host, wxid)
        val hide = custom == null && ProfileWxidSettings.hideWhenNoCustom(host)
        if (custom == null && !hide) return true

        val textViews = ArrayList<TextView>()
        collectTextViews(decor, textViews)
        val labels = textViews.filter { isWxidLabel(it.text?.toString()) }
        if (labels.isEmpty()) return false
        labels.forEach { label ->
            runCatching { rewrite(label, custom) }
                .onFailure { logger("改写微信微信号 TextView 失败", it) }
        }
        log("已处理微信号，wxid=$wxid custom=${custom ?: "<隐藏>"}", null)
        return true
    }

    private fun rewrite(label: TextView, custom: String?) {
        val raw = label.text?.toString()?.trim().orEmpty()
        val row = findRow(label)
        if (custom == null) {
            label.visibility = View.GONE
            val sibling = findSiblingValue(row, label)
            val rowHeight = row?.height ?: 0
            val maxRowHeight = (MAX_HIDE_ROW_DP * label.resources.displayMetrics.density).toInt()
            if (row != null && rowHeight in 1..maxRowHeight) {
                row.visibility = View.GONE
            } else {
                sibling?.visibility = View.GONE
            }
            return
        }
        if (raw.length > LABEL_LENGTH) {
            // 一个 TextView 里就是「微信号：xxx」，直接替换冒号后的内容。
            label.text = "$LABEL：$custom"
            return
        }
        val sibling = findSiblingValue(row, label)
        if (sibling != null) {
            sibling.text = custom
        } else {
            label.text = "$LABEL：$custom"
        }
    }

    private fun isWxidLabel(text: String?): Boolean {
        val value = text?.trim().orEmpty()
        if (value.length < LABEL_LENGTH || !value.startsWith(LABEL)) return false
        if (value.length == LABEL_LENGTH) return true
        val next = value[LABEL_LENGTH]
        return next == '：' || next == ':' || next == ' '
    }

    private fun findRow(view: View): ViewGroup? {
        var current = view.parent
        var depth = 0
        var best: ViewGroup? = null
        while (current is ViewGroup && depth < MAX_ROW_DEPTH) {
            if (current.childCount >= 2 && current.width > 0) best = current
            if (current.id == android.R.id.content) break
            current = current.parent
            depth++
        }
        return best
    }

    private fun findSiblingValue(row: ViewGroup?, label: TextView): TextView? {
        if (row == null) return null
        val views = ArrayList<TextView>()
        collectTextViews(row, views)
        return views.firstOrNull { candidate ->
            candidate !== label && candidate.text?.toString()?.trim().orEmpty().isNotEmpty()
        }
    }

    private fun collectTextViews(root: ViewGroup, out: MutableList<TextView>) {
        for (index in 0 until root.childCount) {
            val child = root.getChildAt(index)
            when (child) {
                is TextView -> if (child.visibility == View.VISIBLE) out += child
                is ViewGroup -> collectTextViews(child, out)
            }
        }
    }

    private fun resolveWxid(activity: Activity): String? {
        val intent: Intent = activity.intent ?: return null
        val direct = listOf(
            intent.getStringExtra("Contact_User"),
            intent.getStringExtra("Contact_Username")
        ).firstOrNull { !it.isNullOrBlank() }?.trim()
        if (!direct.isNullOrBlank()) return direct
        val extras = intent.extras ?: return null
        return extras.keySet()
            .mapNotNull { key ->
                runCatching { extras.get(key) as? String }.getOrNull()?.trim()
            }
            .firstOrNull { value ->
                value.isNotEmpty() &&
                    !value.endsWith("@chatroom") &&
                    !value.contains("@") &&
                    !value.contains(" ") &&
                    value.length in 5..32
            }
    }

    private fun log(message: String, throwable: Throwable?) {
        if (throwable == null) {
            XposedBridge.log("$TAG $message")
        } else {
            logger(message, throwable)
        }
    }

    companion object {
        private const val TAG = "[Hchat:ProfileWxid]"
        private const val CONTACT_INFO_UI = "com.tencent.mm.plugin.profile.ui.ContactInfoUI"
        private const val LABEL = "微信号"
        private const val LABEL_LENGTH = 3
        private const val MAX_ATTEMPTS = 4
        private const val RETRY_DELAY_MS = 150L
        private const val MAX_ROW_DEPTH = 5
        private const val MAX_HIDE_ROW_DP = 180
    }
}