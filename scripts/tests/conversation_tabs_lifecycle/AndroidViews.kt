package android.view

import android.content.Context
import kotlin.math.roundToInt

// Measurement and layout are distinct. Property translation changes no layout bounds
// and requests no layout; window coordinates include every parent transform/scroll.
open class View(val context: Context = Context()) {
    var parent: ViewGroup? = null
        internal set
    var layoutRequests = 0
        private set
    var layoutParams: ViewGroup.LayoutParams = ViewGroup.LayoutParams(0, 0)
        set(value) { field = value; requestLayout() }
    var visibility = VISIBLE
        set(value) {
            if (field == value) return
            if (field == GONE || value == GONE) requestLayout()
            field = value
        }
    var tag: Any? = null
    private val keyedTags = mutableMapOf<Int, Any>()
    var id = 0
    var overScrollMode = 0
    var measuredHeight = 0
    var minimumHeight = 0
    var translationY = 0f
    var scrollY = 0
    var top = 0
        private set
    var bottom = 0
        private set
    val height: Int get() = bottom - top
    var y: Float
        get() = top + translationY
        set(value) { translationY = value - top }
    val resources get() = context.resources
    open val rootView: View get() = parent?.rootView ?: this
    var isAttachedToWindow = false
        private set
    val isShown: Boolean get() = isAttachedToWindow && visibility == VISIBLE && (parent?.isShown ?: true)
    private var detachedObserver = ViewTreeObserver()
    val viewTreeObserver: ViewTreeObserver get() = parent?.viewTreeObserver ?: detachedObserver
    private val attachListeners = arrayListOf<OnAttachStateChangeListener>()
    val attachListenerCount get() = attachListeners.size
    var childReads = 0
    var idLookups = 0

    fun requestLayout() { layoutRequests++ }
    fun setTag(key: Int, value: Any?) {
        if (value == null) keyedTags.remove(key) else keyedTags[key] = value
    }
    fun getTag(key: Int): Any? = keyedTags[key]
    fun bringToFront() { parent?.bringChildToFront(this) }
    fun addOnAttachStateChangeListener(listener: OnAttachStateChangeListener) { attachListeners += listener }
    fun removeOnAttachStateChangeListener(listener: OnAttachStateChangeListener) { attachListeners -= listener }
    open fun dispatchAttachedToWindow() {
        if (isAttachedToWindow) return
        if (parent == null && !detachedObserver.isAlive) detachedObserver = ViewTreeObserver()
        isAttachedToWindow = true
        attachListeners.toList().forEach { it.onViewAttachedToWindow(this) }
    }
    open fun dispatchDetachedFromWindow() {
        if (!isAttachedToWindow) return
        attachListeners.toList().forEach { it.onViewDetachedFromWindow(this) }
        isAttachedToWindow = false
        if (parent == null) detachedObserver.kill()
    }
    fun layout(top: Int = this.top, height: Int = this.height) {
        this.top = top
        this.bottom = top + height
    }
    fun measureAndLayout(height: Int, top: Int = this.top) {
        measuredHeight = height
        layout(top, height)
    }
    fun getLocationInWindow(out: IntArray) {
        var locationY = top + translationY
        var current = parent
        while (current != null) {
            locationY += current.top + current.translationY - current.scrollY
            current = current.parent
        }
        out[0] = 0
        out[1] = locationY.roundToInt()
    }
    @Suppress("UNCHECKED_CAST")
    open fun <T : View> findViewById(id: Int): T? {
        idLookups++
        return if (this.id == id) this as T else null
    }
    interface OnAttachStateChangeListener {
        fun onViewAttachedToWindow(view: View)
        fun onViewDetachedFromWindow(view: View)
    }
    companion object {
        const val VISIBLE = 0
        const val INVISIBLE = 4
        const val GONE = 8
        const val OVER_SCROLL_NEVER = 2
        private var nextId = 100
        fun generateViewId() = nextId++
    }
}

open class ViewGroup(context: Context = Context()) : View(context) {
    private val children = arrayListOf<View>()
    var additions = 0
        private set
    val childCount get() = children.size
    fun getChildAt(index: Int): View { childReads++; return children[index] }
    fun addView(view: View, params: LayoutParams) = addView(view, children.size, params)
    fun addView(view: View, index: Int, params: LayoutParams) {
        check(view.parent == null) { "View already has a parent" }
        children.add(index, view)
        view.parent = this
        view.layoutParams = params
        additions++
        if (isAttachedToWindow) view.dispatchAttachedToWindow()
    }
    fun removeView(view: View) {
        if (!children.contains(view)) return
        view.dispatchDetachedFromWindow()
        children.remove(view)
        view.parent = null
    }
    fun removeAllViews() { children.toList().forEach(::removeView) }
    fun bringChildToFront(view: View) {
        if (children.remove(view)) { children.add(view); requestLayout() }
    }
    override fun dispatchAttachedToWindow() {
        super.dispatchAttachedToWindow()
        children.toList().forEach { it.dispatchAttachedToWindow() }
    }
    override fun dispatchDetachedFromWindow() {
        children.toList().forEach { it.dispatchDetachedFromWindow() }
        super.dispatchDetachedFromWindow()
    }
    @Suppress("UNCHECKED_CAST")
    override fun <T : View> findViewById(id: Int): T? {
        idLookups++
        return if (this.id == id) this as T else children.firstNotNullOfOrNull { it.findViewById<T>(id) }
    }
    @Suppress("UNCHECKED_CAST")
    fun <T : View> findViewWithTag(value: Any): T? =
        if (tag == value) this as T else children.firstNotNullOfOrNull {
            if (it.tag == value) it as T else (it as? ViewGroup)?.findViewWithTag<T>(value)
        }
    open class LayoutParams(val width: Int, var height: Int) {
        companion object { const val MATCH_PARENT = -1; const val WRAP_CONTENT = -2 }
    }
}

class ViewTreeObserver {
    fun interface OnGlobalLayoutListener { fun onGlobalLayout() }
    fun interface OnPreDrawListener { fun onPreDraw(): Boolean }
    private val layouts = arrayListOf<OnGlobalLayoutListener>()
    private val draws = arrayListOf<OnPreDrawListener>()
    var isAlive = true
        private set
    val layoutListenerCount get() = layouts.size
    val drawListenerCount get() = draws.size
    fun addOnGlobalLayoutListener(listener: OnGlobalLayoutListener) { check(isAlive); layouts += listener }
    fun removeOnGlobalLayoutListener(listener: OnGlobalLayoutListener) { check(isAlive); layouts -= listener }
    fun addOnPreDrawListener(listener: OnPreDrawListener) { check(isAlive); draws += listener }
    fun removeOnPreDrawListener(listener: OnPreDrawListener) { check(isAlive); draws -= listener }
    fun dispatchOnGlobalLayout() { check(isAlive); layouts.toList().forEach { it.onGlobalLayout() } }
    fun dispatchOnPreDraw(): Boolean {
        check(isAlive)
        var allowed = true
        // Android invokes all listeners even if an earlier listener cancels this draw.
        draws.toList().forEach { if (!it.onPreDraw()) allowed = false }
        return allowed
    }
    fun kill() { isAlive = false; layouts.clear(); draws.clear() }
}

object Gravity { const val CENTER_VERTICAL = 16 }
