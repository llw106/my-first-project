package com.tencent.mm.ui.conversation

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RelativeLayout
import com.tencent.mm.ui.conversation.recycler.ConversationRecyclerView

open class MainUiBase {
    open fun onResume() = Unit
    open fun onDestroy() = Unit
}
class OtherUi : MainUiBase()

// Title sits outside id/jlt. The optional bounce wrapper sits inside that frame.
class MainUI : MainUiBase() {
    var nativeRoot: MainUIView? = null
    var inflationCount = 0
    val window = FrameLayout()
    val contentLayer = FrameLayout().apply { id = 1002 }
    val bounce = FrameLayout()
    val actionBar = View().apply { id = 1001 }
    init {
        window.measureAndLayout(height = 1200, top = 11)
        contentLayer.measureAndLayout(height = 1000, top = 29)
        bounce.measureAndLayout(height = 1000)
        actionBar.measureAndLayout(height = 56, top = 29 + 24)
        window.addView(contentLayer, FrameLayout.LayoutParams(400, 1000))
        contentLayer.addView(bounce, FrameLayout.LayoutParams(400, 1000))
        window.addView(actionBar, FrameLayout.LayoutParams(400, 56))
        window.dispatchAttachedToWindow()
    }
    fun getLayoutView(): View {
        inflationCount++
        return MainUIView().also { nativeRoot = it }
    }
    override fun onResume() {
        if (nativeRoot?.parent !== bounce) {
            bounce.removeAllViews()
            nativeRoot?.let {
                bounce.addView(it, FrameLayout.LayoutParams(400, 1000))
                it.measureAndLayout(height = 1000)
            }
        }
    }
}

class MainUIView : RelativeLayout() {
    var list: View = ConversationListView()
        private set
    val mask = View()
    val background = View().apply { visibility = View.GONE }
    init {
        addView(background, LayoutParams(400, 1000))
        addView(list, LayoutParams(400, 1000).apply {
            topMargin = 20
            addRule(ALIGN_PARENT_TOP)
        })
        addView(mask, LayoutParams(400, 24).apply { addRule(ALIGN_PARENT_TOP) })
    }
    fun replaceWithRecycler(reuseParams: Boolean): View {
        val old = list
        val params = if (reuseParams) old.layoutParams else LayoutParams(400, 1000).apply {
            topMargin = 32
            addRule(ALIGN_PARENT_TOP)
        }
        removeView(old)
        list = ConversationRecyclerView()
        addView(list, 1, params)
        return old
    }
}
class ConversationListView : ViewGroup() {
    // Synthetic collapsed-header baseline: 60 px remains in the list viewport.
    // Together with the native 20 px list margin this puts the first conversation
    // below this fixture's 24 + 56 px status/title area. This is not device evidence.
    val nativeHeader = View().apply { measureAndLayout(top = -940, height = 1000) }
    val firstConversation = View().apply { measureAndLayout(top = 60, height = 56) }
    init {
        addView(nativeHeader, LayoutParams(400, 1000))
        addView(firstConversation, LayoutParams(400, 56))
    }
}
