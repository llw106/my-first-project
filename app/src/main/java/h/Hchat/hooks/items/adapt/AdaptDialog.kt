package h.Hchat.hooks.items.adapt

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import h.Hchat.utils.HLog

/**
 * 「适配」相关的弹窗：安装后提示、扫描进度、扫描结果。
 *
 * 全部自绘（Dialog + 代码布局），不用系统 AlertDialog。原因：
 *  · 微信的主题千奇百怪，系统 AlertDialog 在深色模式下经常是白底黑字看不清；
 *  · 统一用 20dp 圆角卡片 + 微信绿主按钮，和 Hchat 自己的 Miuix 页面观感一致。
 */
object AdaptDialog {

    private const val TAG = "[Hchat:AdaptDialog]"

    /** 微信绿。 */
    private const val ACCENT = 0xFF07C160.toInt()

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun night(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun cardColor(context: Context): Int =
        if (night(context)) 0xFF2C2C2E.toInt() else Color.WHITE

    private fun titleColor(context: Context): Int =
        if (night(context)) 0xFFF2F2F7.toInt() else 0xFF161616.toInt()

    private fun bodyColor(context: Context): Int =
        if (night(context)) 0xFFAEAEB2.toInt() else 0xFF606066.toInt()

    private fun card(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = dp(context, 20).toFloat()
            setColor(cardColor(context))
        }
        setPadding(dp(context, 22), dp(context, 20), dp(context, 22), dp(context, 16))
    }

    private fun label(
        context: Context,
        value: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false
    ): TextView = TextView(context).apply {
        text = value
        textSize = sizeSp
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setLineSpacing(dp(context, 2).toFloat(), 1f)
    }

    private fun actionButton(
        context: Context,
        text: String,
        primary: Boolean,
        onClick: () -> Unit
    ): TextView = TextView(context).apply {
        this.text = text
        textSize = 16f
        gravity = Gravity.CENTER
        setTextColor(if (primary) Color.WHITE else bodyColor(context))
        background = GradientDrawable().apply {
            cornerRadius = dp(context, 12).toFloat()
            if (primary) setColor(ACCENT) else setColor(Color.TRANSPARENT)
        }
        isClickable = true
        setPadding(dp(context, 8), dp(context, 11), dp(context, 8), dp(context, 11))
        setOnClickListener { onClick() }
    }

    private fun buttonRow(
        context: Context,
        left: TextView?,
        right: TextView?
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(context, 16), 0, 0)
        if (left != null && right != null) {
            addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(context, 10)
            })
        } else {
            val only = left ?: right ?: return@apply
            addView(only, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    /** 把内容装进一个居中、86% 屏宽（最大 380dp）的无标题 Dialog。 */
    private fun present(activity: Activity, content: View, cancelable: Boolean): Dialog? = runCatching {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(content)
        dialog.setCancelable(cancelable)
        dialog.setCanceledOnTouchOutside(false)
        val window = dialog.window
        if (window != null) {
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setDimAmount(0.45f)
            val metrics = activity.resources.displayMetrics
            val width = (metrics.widthPixels * 0.86f).toInt()
                .coerceAtMost(dp(activity, 380))
                .coerceAtLeast(dp(activity, 240))
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setGravity(Gravity.CENTER)
        }
        dialog.show()
        dialog
    }.onFailure { HLog.e("$TAG 弹窗失败: ${it.message}", it) }.getOrNull()

    /** 安装后提示：告诉用户包体变了，问要不要现在扫。 */
    fun showPrompt(
        activity: Activity,
        info: String,
        onStart: () -> Unit,
        onLater: () -> Unit
    ) {
        val context: Context = activity
        val content = card(context)
        content.addView(label(context, "Hchat 需要适配", 19f, titleColor(context), bold = true))
        content.addView(label(context, info, 13f, bodyColor(context)).apply {
            setPadding(0, dp(context, 8), 0, 0)
        })
        content.addView(label(
            context,
            "微信升级后类名会重新混淆，需要重新扫描一遍 dex（共 ${AdaptScan.TOTAL} 项，约 3~10 秒）。",
            13f,
            bodyColor(context)
        ).apply { setPadding(0, dp(context, 12), 0, 0) })

        lateinit var dialog: Dialog
        val later = actionButton(context, "稍后", false) {
            runCatching { dialog.dismiss() }
            onLater()
        }
        val start = actionButton(context, "立即适配", true) {
            runCatching { dialog.dismiss() }
            onStart()
        }
        content.addView(buttonRow(context, later, start))
        present(activity, content, false)?.let { dialog = it }
    }

    /** 扫描进度：一个带进度条和当前项文本的卡片。 */
    class Progress internal constructor(
        private val dialog: Dialog,
        private val titleView: TextView,
        private val detailView: TextView,
        private val bar: ProgressBar
    ) {
        fun update(index: Int, item: String) {
            runCatching {
                titleView.text = "正在扫描 $index/${AdaptScan.TOTAL}"
                detailView.text = item
                bar.progress = (index * 100 / AdaptScan.TOTAL).coerceIn(0, 100)
            }
        }

        fun dismiss() {
            runCatching { if (dialog.isShowing) dialog.dismiss() }
        }
    }

    fun showProgress(activity: Activity): Progress? {
        val context: Context = activity
        val content = card(context)
        val title = label(context, "正在扫描 0/${AdaptScan.TOTAL}", 17f, titleColor(context), bold = true)
        val detail = label(context, "准备中…", 13f, bodyColor(context)).apply {
            setPadding(0, dp(context, 10), 0, 0)
        }
        val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            progressTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            setPadding(0, dp(context, 16), 0, 0)
        }
        content.addView(title)
        content.addView(detail)
        content.addView(bar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 6)
        ).apply { topMargin = dp(context, 16) })
        content.addView(label(context, "扫描期间请不要退出微信", 12f, bodyColor(context)).apply {
            setPadding(0, dp(context, 14), 0, 0)
        })
        val dialog = present(activity, content, false) ?: return null
        return Progress(dialog, title, detail, bar)
    }

    /** 结果：成功给「重启微信」，有失败项就说清楚但不再强迫重扫。 */
    fun showResult(
        activity: Activity,
        title: String,
        message: String,
        primaryLabel: String,
        onPrimary: () -> Unit,
        secondaryLabel: String = "知道了",
        onSecondary: () -> Unit = {}
    ) {
        val context: Context = activity
        val content = card(context)
        content.addView(label(context, title, 19f, titleColor(context), bold = true))
        content.addView(label(context, message, 13f, bodyColor(context)).apply {
            setPadding(0, dp(context, 10), 0, 0)
        })
        lateinit var dialog: Dialog
        val secondary = actionButton(context, secondaryLabel, false) {
            runCatching { dialog.dismiss() }
            onSecondary()
        }
        val primary = actionButton(context, primaryLabel, true) {
            runCatching { dialog.dismiss() }
            onPrimary()
        }
        content.addView(buttonRow(context, secondary, primary))
        present(activity, content, true)?.let { dialog = it }
    }
}