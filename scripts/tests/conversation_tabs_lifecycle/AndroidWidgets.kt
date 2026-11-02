package android.widget

import android.content.Context
import android.view.ViewGroup

class HorizontalScrollView(context: Context) : ViewGroup(context) {
    var isHorizontalScrollBarEnabled = true
}
class LinearLayout(context: Context) : ViewGroup(context) {
    var orientation = 0
    var gravity = 0
    companion object { const val HORIZONTAL = 0 }
}
open class FrameLayout(context: Context = Context()) : ViewGroup(context) {
    class LayoutParams(width: Int, height: Int) : ViewGroup.LayoutParams(width, height) {
        var topMargin: Int = 0
    }
}
open class RelativeLayout(context: Context = Context()) : ViewGroup(context) {
    class LayoutParams(width: Int, height: Int) : ViewGroup.LayoutParams(width, height) {
        var topMargin: Int = 0
        private val rules = mutableMapOf<Int, Int>()
        constructor(other: LayoutParams) : this(other.width, other.height) {
            rules.putAll(other.rules)
            topMargin = other.topMargin
        }
        fun addRule(rule: Int, anchor: Int = -1) { rules[rule] = anchor }
        fun getRule(rule: Int) = rules[rule] ?: 0
    }
    companion object { const val ALIGN_PARENT_TOP = 10; const val BELOW = 3 }
}
