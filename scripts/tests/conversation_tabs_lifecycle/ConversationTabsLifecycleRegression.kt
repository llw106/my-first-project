package h.Hchat.hooks.items.conversationtabs

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.RelativeLayout
import com.tencent.mm.ui.conversation.MainUI
import com.tencent.mm.ui.conversation.MainUIView
import com.tencent.mm.ui.conversation.ConversationListView
import com.tencent.mm.ui.conversation.OtherUi
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.utils.HLog
import h.Hchat.utils.KavaReflector

private var checks = 0
private var scenarios = 0
private fun expect(condition: Boolean, message: String) {
    checks++
    check(condition) { message }
}
private fun scenario(name: String, block: () -> Unit) {
    ConversationTabsRuntime.reset()
    block()
    scenarios++
    println("PASS: $name")
}
private val registry get() = HookRegistry.get()
private val loader get() = MainUI::class.java.classLoader!!
private fun install() = ConversationTabsRuntime.installUiHooks(loader)
private fun invoke(owner: Any, name: String): Any? =
    registry.invoke(owner, KavaReflector.findMethodRecursive(owner.javaClass, name)!!)
private fun MainUIView.listParams() = list.layoutParams as RelativeLayout.LayoutParams
private fun MainUI.resume() = invoke(this, "onResume")
private fun MainUI.strip() = checkNotNull(ConversationTabsRuntime.strip(this)) {
    "Lifecycle recovery must attach a strip to the retained native root"
}
private fun View.windowTop(): Int = IntArray(2).also(::getLocationInWindow)[1]
private fun MainUI.observer() = nativeRoot!!.viewTreeObserver
private fun ViewTreeObserver.expectListeners(count: Int) {
    expect(layoutListenerCount == count && drawListenerCount == count,
        "Expected $count geometry listener pair(s), found $layoutListenerCount/$drawListenerCount")
}

// A native traversal lays out the changed list margin, measures the strip, then
// dispatches global layout/pre-draw. A canceled pre-draw needs another traversal.
private fun MainUI.frame(): Boolean {
    val root = nativeRoot!!
    val listParams = root.listParams()
    root.list.measureAndLayout(top = listParams.topMargin, height = 1000 - listParams.topMargin)
    val maskParams = root.mask.layoutParams as RelativeLayout.LayoutParams
    root.mask.measureAndLayout(top = maskParams.topMargin, height = maskParams.height)
    val strip = strip()
    strip.layout(top = (strip.layoutParams as FrameLayout.LayoutParams).topMargin, height = strip.measuredHeight)
    observer().dispatchOnGlobalLayout()
    return observer().dispatchOnPreDraw()
}
private fun MainUI.settle() {
    repeat(4) { if (frame()) return }
    error("Geometry never settled: production repeatedly canceled draw")
}
private fun ready(): MainUI {
    ConversationTabsRuntime.initialize(FeatureContext())
    return MainUI().also {
        invoke(it, "getLayoutView")
        it.resume()
        it.strip().measuredHeight = 48
        it.settle()
    }
}
private fun MainUI.expectBelowTitle(message: String) {
    expect(strip().visibility == View.VISIBLE, "$message: strip must be visible")
    expect(strip().windowTop() == actionBar.windowTop() + actionBar.height,
        "$message: tag top ${strip().windowTop()} must match title bottom ${actionBar.windowTop() + actionBar.height}")
}

fun main() {
    scenario("fixture keeps translation, scroll, measurement and layout distinct") {
        val parent = FrameLayout().apply { measureAndLayout(top = 30, height = 500); translationY = 9f; scrollY = 7 }
        val child = View().apply { measuredHeight = 40; translationY = 4f }
        parent.addView(child, FrameLayout.LayoutParams(100, 40))
        expect(child.height == 0, "Measured height does not imply that layout has run")
        child.layout(top = 20, height = 40)
        val requests = child.layoutRequests
        expect(child.windowTop() == 56, "Window location includes own/parent translation and subtracts parent scroll")
        child.y = 31f
        expect(child.top == 20 && child.translationY == 11f, "setY must preserve native layout top")
        expect(child.layoutRequests == requests, "Property translation must not request another layout")
        expect(!child.isAttachedToWindow && !child.isShown, "Detached views must not appear shown")
        parent.dispatchAttachedToWindow()
        expect(child.isShown && child.viewTreeObserver === parent.viewTreeObserver, "Attached hierarchy shares its observer")
    }
    scenario("early layout waits for fixed layer and never flashes at the status bar") {
        expect(install(), "Install early without FeatureContext")
        val owner = MainUI()
        val root = invoke(owner, "getLayoutView") as MainUIView
        expect(ConversationTabsRuntime.hostCount() == 0, "Detached layout waits for controller attachment")
        expect(root.childCount == 3, "Native root children stay intact")
        ConversationTabsRuntime.initialize(FeatureContext())
        owner.resume()
        val strip = owner.strip()
        expect(strip.getTag(ConversationTabsRuntime.hostTagKey()) === ConversationTabsRuntime.retainedHost(owner),
            "The live strip strongly retains the same host stored in the weak global index")
        expect(strip.tag == "hchat:conversation-tabs-root", "Keyed host storage preserves the ordinary root tag")
        expect(strip.parent === owner.contentLayer && strip.parent !== root, "Overlay belongs outside the bounce wrapper")
        expect(strip.visibility == View.INVISIBLE, "An enabled but unmeasured strip must start invisible")
        expect(owner.frame() && strip.visibility == View.INVISIBLE, "No measured strip means no zero-origin draw")
        expect(root.listParams().topMargin == 20, "No spacing is added before measurement")
        strip.measuredHeight = 48
        expect(!owner.frame() && strip.visibility == View.INVISIBLE, "Reserve the measured height before allowing a draw")
        expect(root.listParams().topMargin == 68, "Native margin receives exactly the measured strip height")
        expect(owner.frame(), "Draw resumes after the native list lays out")
        owner.expectBelowTitle("first visible frame")
        expect(strip.y == 80f, "Account for status/title height and fixed-parent window origin")
        expect(root.childCount == 3 && registry.count == 3, "No native child or lifecycle hook duplication")
    }
    scenario("resume recovers an existing native field without reinflating") {
        val owner = MainUI()
        val existing = owner.getLayoutView() as MainUIView
        expect(install(), "Late hook installation succeeds")
        owner.resume()
        expect(ConversationTabsRuntime.root(owner) === existing, "Recover the existing MainUIView field")
        expect(owner.inflationCount == 1, "Resume never calls the inflating getter")
        expect(owner.strip().visibility == View.GONE, "Early hosts remain disabled until feature initialization")
        owner.observer().expectListeners(0)
        ConversationTabsRuntime.initialize(FeatureContext())
        owner.strip().measuredHeight = 48
        owner.settle()
        owner.expectBelowTitle("initialization renders an earlier host")
    }
    scenario("collapsed, partial pull, full pull and return follow the native title") {
        val owner = ready()
        val root = owner.nativeRoot!!
        val strip = owner.strip()
        val listRequests = root.list.layoutRequests
        val stripRequests = strip.layoutRequests
        owner.expectBelowTitle("collapsed")
        owner.actionBar.translationY = 175f
        owner.bounce.translationY = 90f
        expect(owner.observer().dispatchOnPreDraw(), "Translation alone must not cancel draw")
        owner.expectBelowTitle("partial pull")
        expect(strip.y == 255f && owner.actionBar.top == 53, "Follow property translation without changing native layout bounds")
        owner.actionBar.translationY = 900f
        owner.observer().dispatchOnPreDraw()
        expect(strip.visibility == View.INVISIBLE, "Hide a strip that would extend beyond the page at full pull")
        owner.actionBar.translationY = -200f
        owner.observer().dispatchOnPreDraw()
        expect(strip.visibility == View.INVISIBLE, "Never show the strip above the fixed content layer")
        owner.actionBar.translationY = 0f
        owner.bounce.translationY = 0f
        owner.observer().dispatchOnPreDraw()
        owner.expectBelowTitle("returned to conversations")
        expect(root.listParams().topMargin == 68, "Pull-down does not repeatedly add list spacing")
        expect(root.list.layoutRequests == listRequests && strip.layoutRequests == stripRequests,
            "Following pull-down translation must not trigger layout")
    }
    scenario("window transforms and parent scrolling preserve title alignment") {
        val owner = ready()
        owner.window.translationY = 13f
        owner.window.scrollY = 6
        owner.contentLayer.translationY = 17f
        owner.contentLayer.scrollY = 9
        owner.bounce.translationY = 73f
        owner.actionBar.translationY = 25f
        expect(owner.observer().dispatchOnPreDraw(), "Coordinate changes need no layout")
        owner.expectBelowTitle("nonzero window, content translation and scroll")
        expect(owner.strip().top == 0, "Only strip translation is updated, not FrameLayout bounds")
    }
    scenario("viewport clipping uses visible coordinates when the fixed parent scrolls") {
        val owner = ready()
        owner.contentLayer.scrollY = 75
        owner.actionBar.translationY = 850f
        owner.observer().dispatchOnPreDraw()
        owner.expectBelowTitle("strip fully inside scrolled viewport")
        expect(owner.strip().y == 1005f, "Child coordinates include parent scroll even when they exceed parent height")
        owner.contentLayer.scrollY = -100
        owner.actionBar.translationY = 900f
        owner.observer().dispatchOnPreDraw()
        expect(owner.strip().visibility == View.INVISIBLE,
            "A strip outside the visible viewport must hide even when its child coordinates fit")
        owner.actionBar.translationY = 0f
        owner.contentLayer.scrollY = 0
        owner.observer().dispatchOnPreDraw()
        owner.expectBelowTitle("viewport restored")
    }
    scenario("title, status bar and strip sizes use measured geometry") {
        val owner = ready()
        val root = owner.nativeRoot!!
        owner.actionBar.measureAndLayout(top = 29 + 37, height = 71)
        owner.strip().measuredHeight = 63
        expect(!owner.frame(), "Changed strip measurement reserves space before drawing")
        expect(root.listParams().topMargin == 83, "Replace the old strip height rather than stacking both heights")
        owner.settle()
        owner.expectBelowTitle("new title and status dimensions")
        expect(owner.strip().y == 108f, "Use the actual title bottom instead of fixed dp constants")
        root.listParams().topMargin = 36
        root.list.layoutParams = root.listParams()
        owner.settle()
        expect(root.listParams().topMargin == 99, "Native margin update becomes the new base")
        ConversationTabsRuntime.setEnabled(false)
        expect(root.listParams().topMargin == 36, "Disable restores the most recent native base")
    }
    scenario("missing dimensions or hidden native page never display misplaced tags") {
        val owner = ready()
        owner.actionBar.layout(height = 0)
        owner.frame()
        expect(owner.strip().visibility == View.INVISIBLE, "Unlaid title hides tags despite its measured height")
        owner.actionBar.layout(height = 56)
        owner.contentLayer.layout(height = 0)
        owner.frame()
        expect(owner.strip().visibility == View.INVISIBLE, "Unlaid content frame hides tags")
        owner.contentLayer.layout(height = 1000)
        owner.actionBar.visibility = View.INVISIBLE
        owner.frame()
        expect(owner.strip().visibility == View.INVISIBLE, "Hidden native title hides tags")
        owner.actionBar.visibility = View.VISIBLE
        owner.nativeRoot!!.visibility = View.GONE
        owner.frame()
        expect(owner.strip().visibility == View.INVISIBLE, "Other pages cannot reveal a detached-looking tag strip")
        owner.nativeRoot!!.visibility = View.VISIBLE
        owner.settle()
        owner.expectBelowTitle("page visible again")
    }
    scenario("absent title and unsupported list params hide until native geometry returns") {
        val owner = ready()
        val root = owner.nativeRoot!!
        owner.window.removeView(owner.actionBar)
        owner.frame()
        expect(owner.strip().visibility == View.INVISIBLE, "A missing native title cannot leave stale visible coordinates")
        owner.window.addView(owner.actionBar, FrameLayout.LayoutParams(400, 56))
        owner.settle()
        owner.expectBelowTitle("native title restored")
        val nativeParams = root.listParams()
        root.list.layoutParams = ViewGroup.LayoutParams(400, 900)
        owner.observer().dispatchOnGlobalLayout()
        expect(owner.observer().dispatchOnPreDraw(), "Unsupported params must not block native drawing")
        expect(owner.strip().visibility == View.INVISIBLE, "Unsupported list geometry must not leave a visible overlay")
        root.list.layoutParams = nativeParams
        owner.settle()
        owner.expectBelowTitle("native list params restored")
    }
    scenario("only conversation list spacing changes; masks, rules and native params survive") {
        val owner = MainUI()
        val root = owner.getLayoutView() as MainUIView
        val listParams = root.listParams()
        val maskParams = root.mask.layoutParams as RelativeLayout.LayoutParams
        val maskRequests = root.mask.layoutRequests
        val backgroundParams = root.background.layoutParams
        ConversationTabsRuntime.initialize(FeatureContext())
        ConversationTabsRuntime.setEnabled(false)
        owner.resume()
        val nativeList = root.list as ConversationListView
        owner.frame()
        expect(nativeList.firstConversation.windowTop() == owner.actionBar.windowTop() + owner.actionBar.height,
            "Synthetic collapsed-header baseline places the first conversation below the native title")
        val headerTop = nativeList.nativeHeader.top
        val headerHeight = nativeList.nativeHeader.height
        val conversationTop = nativeList.firstConversation.top
        ConversationTabsRuntime.setEnabled(true)
        owner.strip().measuredHeight = 48
        owner.settle()
        expect(root.listParams() === listParams, "Keep the native list LayoutParams instance")
        expect(listParams.getRule(RelativeLayout.ALIGN_PARENT_TOP) == -1 && listParams.getRule(RelativeLayout.BELOW) == 0,
            "Preserve native positioning rules")
        expect(maskParams.topMargin == 0 && maskParams.height == 24,
            "Status mask must not shift or resize with tag spacing")
        expect(root.mask.layoutParams === maskParams && root.mask.layoutRequests == maskRequests,
            "Do not reassign status-mask layout params")
        expect(maskParams.getRule(RelativeLayout.ALIGN_PARENT_TOP) == -1 && maskParams.getRule(RelativeLayout.BELOW) == 0,
            "Do not re-anchor the native status mask")
        expect(root.background.layoutParams === backgroundParams && root.childCount == 3,
            "Preserve all native direct children")
        expect(root.list.top == 68 && owner.strip().height == 48,
            "The native list's laid-out origin moves by the measured tag height")
        expect(nativeList.firstConversation.windowTop() == owner.strip().windowTop() + owner.strip().height,
            "Added spacing puts the synthetic first conversation below the entire tag strip")
        expect(nativeList.nativeHeader.top == headerTop && nativeList.nativeHeader.height == headerHeight &&
            nativeList.firstConversation.top == conversationTop,
            "Adding strip space does not change the native header's own offset or child layout")
    }
    for (reuseParams in listOf(false, true)) scenario("recycler replacement handles reused native params=$reuseParams") {
        val owner = ready()
        val root = owner.nativeRoot!!
        val old = root.replaceWithRecycler(reuseParams)
        owner.settle()
        val base = if (reuseParams) 20 else 32
        expect(root.list.javaClass.name.endsWith("ConversationRecyclerView"), "Exercise the parallel native recycler class")
        expect(root.listParams().topMargin == base + 48, "Apply spacing once after native list replacement")
        expect(owner.strip().visibility == View.VISIBLE, "Recycler replacement keeps tags visible")
        if (!reuseParams) expect((old.layoutParams as RelativeLayout.LayoutParams).topMargin == 20,
            "Detached legacy list recovers its original margin")
        ConversationTabsRuntime.setEnabled(false)
        expect(root.listParams().topMargin == base, "Recycler disable restores its own native margin")
    }
    scenario("disable restores native geometry and respects an unseen native margin update") {
        val owner = ready()
        val root = owner.nativeRoot!!
        val observer = owner.observer()
        ConversationTabsRuntime.setEnabled(false)
        expect(owner.strip().visibility == View.GONE && root.listParams().topMargin == 20,
            "Disabled tags consume neither pixels nor native list space")
        observer.expectListeners(0)
        ConversationTabsRuntime.setEnabled(true)
        expect(owner.strip().visibility == View.INVISIBLE, "Re-enable waits for geometry")
        owner.settle()
        expect(root.listParams().topMargin == 68, "Re-enable applies spacing only once")
        root.listParams().topMargin = 44
        root.list.layoutParams = root.listParams()
        ConversationTabsRuntime.setEnabled(false)
        expect(root.listParams().topMargin == 44, "Do not overwrite native changes before our next pre-draw")
        observer.expectListeners(0)
    }
    scenario("repeated resumes and initialization reuse host, observers and children") {
        val owner = ready()
        val root = owner.nativeRoot!!
        val strip = owner.strip()
        val additions = owner.contentLayer.additions
        repeat(5) {
            expect(install(), "Repeated hook installation remains successful")
            ConversationTabsRuntime.initialize(FeatureContext())
            owner.resume()
            owner.settle()
        }
        expect(owner.strip() === strip && owner.contentLayer.additions == additions, "Do not rebuild the fixed overlay")
        expect(root.childCount == 3 && registry.count == 3, "No duplicate native children or hooks")
        expect(owner.inflationCount == 1, "Repeated resume must not inflate another root")
        owner.observer().expectListeners(1)
        expect(root.attachListenerCount == 1, "One root attach listener per host")
    }
    scenario("ordinary scrolling avoids resource lookup, tree scans and layout requests") {
        val owner = ready()
        val root = owner.nativeRoot!!
        val childReads = root.childReads
        val idLookups = owner.window.idLookups
        val resources = root.resources.identifierLookups
        val listRequests = root.list.layoutRequests
        val stripRequests = owner.strip().layoutRequests
        repeat(120) {
            root.list.scrollY = it * 3
            owner.observer().dispatchOnPreDraw()
        }
        expect(root.childReads == childReads, "Scroll-time pre-draw must reuse the cached list")
        expect(owner.window.idLookups == idLookups, "Scroll-time pre-draw must reuse the cached title")
        expect(root.resources.identifierLookups == resources, "Resource IDs must not be resolved each frame")
        expect(root.list.layoutRequests == listRequests && owner.strip().layoutRequests == stripRequests,
            "Stable geometry must not request repeated layout")
    }
    scenario("new native root replaces host and restores the detached old list") {
        val owner = ready()
        val previous = owner.nativeRoot!!
        val oldStrip = owner.strip()
        val current = invoke(owner, "getLayoutView") as MainUIView
        expect(current !== previous && current === owner.nativeRoot, "Return the fresh native layout unchanged")
        expect(oldStrip.parent == null && previous.listParams().topMargin == 20, "Remove old overlay and spacing immediately")
        expect(oldStrip.getTag(ConversationTabsRuntime.hostTagKey()) == null, "Root replacement releases the old strip's host tag")
        expect(previous.attachListenerCount == 0, "Remove old root attach listener")
        owner.resume()
        owner.strip().measuredHeight = 48
        owner.settle()
        expect(ConversationTabsRuntime.root(owner) === current, "Host tracks the replacement root")
        owner.expectBelowTitle("rebuilt page")
        owner.observer().expectListeners(1)
    }
    scenario("externally removed overlay is repaired without stacking list spacing") {
        val owner = ready()
        val root = owner.nativeRoot!!
        val oldStrip = owner.strip()
        owner.contentLayer.removeView(oldStrip)
        owner.resume()
        val fresh = owner.strip()
        expect(fresh !== oldStrip && fresh.parent === owner.contentLayer, "Reattach after native removal")
        fresh.measuredHeight = 48
        owner.settle()
        expect(root.listParams().topMargin == 68 && root.childCount == 3, "Repair never adds the height twice")
        owner.observer().expectListeners(1)
    }
    scenario("content parent remount removes old overlay and installs on the new fixed frame") {
        val owner = ready()
        val oldStrip = owner.strip()
        val root = owner.nativeRoot!!
        val observer = owner.observer()
        owner.contentLayer.removeView(owner.bounce)
        observer.expectListeners(0)
        expect(oldStrip.visibility == View.INVISIBLE, "Detached page immediately hides its overlay")
        val replacement = FrameLayout().apply { id = 1002; measureAndLayout(top = 41, height = 900) }
        owner.window.addView(replacement, FrameLayout.LayoutParams(400, 900))
        replacement.addView(owner.bounce, FrameLayout.LayoutParams(400, 900))
        owner.resume()
        expect(oldStrip.parent == null && owner.strip().parent === replacement, "Resume follows the new fixed content ancestor")
        owner.strip().measuredHeight = 48
        owner.settle()
        owner.expectBelowTitle("remounted fixed parent")
        expect(root.listParams().topMargin == 68 && root.attachListenerCount == 1, "Keep one spacing adjustment and attach listener")
        observer.expectListeners(1)
    }
    scenario("detach, reattach and window observer replacement cleanly manage listeners") {
        val owner = ready()
        val root = owner.nativeRoot!!
        val originalObserver = owner.observer()
        owner.bounce.removeView(root)
        originalObserver.expectListeners(0)
        expect(owner.strip().visibility == View.INVISIBLE, "Detach hides strip before another draw")
        owner.bounce.addView(root, FrameLayout.LayoutParams(400, 1000))
        originalObserver.expectListeners(1)
        owner.settle()
        owner.expectBelowTitle("reattached root")
        owner.window.dispatchDetachedFromWindow()
        expect(!originalObserver.isAlive && originalObserver.drawListenerCount == 0, "Old window observer dies without retained callbacks")
        owner.window.dispatchAttachedToWindow()
        val replacementObserver = owner.observer()
        expect(replacementObserver !== originalObserver, "A recreated window has a fresh tree observer")
        replacementObserver.expectListeners(1)
        owner.settle()
        val destroyedStrip = owner.strip()
        invoke(owner, "onDestroy")
        replacementObserver.expectListeners(0)
        expect(destroyedStrip.getTag(ConversationTabsRuntime.hostTagKey()) == null, "Destroy clears the strong keyed host reference")
        expect(root.attachListenerCount == 0 && root.listParams().topMargin == 20, "Destroy restores geometry and releases root listener")
        expect(ConversationTabsRuntime.hostCount() == 0, "Destroy releases the host")
    }
    scenario("moving the content into another window reacquires that window's native title") {
        val owner = ready()
        val oldObserver = owner.observer()
        val secondWindow = FrameLayout().apply { measureAndLayout(top = 23, height = 1200) }
        val secondTitle = View().apply { id = 1001; measureAndLayout(top = 70, height = 64) }
        secondWindow.addView(secondTitle, FrameLayout.LayoutParams(400, 64))
        secondWindow.dispatchAttachedToWindow()
        owner.window.removeView(owner.contentLayer)
        oldObserver.expectListeners(0)
        secondWindow.addView(owner.contentLayer, FrameLayout.LayoutParams(400, 1000))
        owner.settle()
        expect(owner.actionBar.isAttachedToWindow, "Old title remains attached to the old window")
        expect(owner.strip().visibility == View.VISIBLE && owner.strip().windowTop() == secondTitle.windowTop() + secondTitle.height,
            "A still-attached title from another window must not stay cached")
        owner.observer().expectListeners(1)
        invoke(owner, "onDestroy")
        owner.observer().expectListeners(0)
    }
    scenario("destroy affects only its owner and lifecycle hooks accept another page") {
        val first = ready()
        val second = MainUI()
        invoke(second, "getLayoutView"); second.resume()
        second.strip().measuredHeight = 48
        second.settle()
        val secondStrip = second.strip()
        invoke(first, "onDestroy")
        expect(ConversationTabsRuntime.hostCount() == 1, "Destroy only the matching host")
        expect(secondStrip.parent === second.contentLayer && secondStrip.visibility == View.VISIBLE, "Other live owner remains visible")
        invoke(second, "onDestroy")
        expect(ConversationTabsRuntime.hostCount() == 0, "Last destroy clears hosts")
        val fresh = MainUI()
        invoke(fresh, "getLayoutView"); fresh.resume()
        expect(ConversationTabsRuntime.hostCount() == 1, "Installed lifecycle hooks remain reusable")
    }
    scenario("unrelated lifecycle callbacks, absent roots and native failures are ignored") {
        install()
        invoke(OtherUi(), "onResume")
        invoke(OtherUi(), "onDestroy")
        invoke(MainUI(), "onResume")
        expect(ConversationTabsRuntime.hostCount() == 0, "Only MainUI with an existing root can attach")
        registry.dispatch(MainUI(), KavaReflector.findMethodRecursive(MainUI::class.java, "getLayoutView")!!,
            MainUIView(), IllegalStateException("Native layout failed"))
        expect(ConversationTabsRuntime.hostCount() == 0, "Native failure cannot create a host")
    }
    scenario("partial hook installation rolls back and retries") {
        registry.failOn = "onResume"
        expect(!install(), "Report partial hook failure")
        expect(registry.count == 0 && registry.unhooks == 1, "Unhook the successful prefix")
        expect(HLog.errors.size == 1, "Keep installation errors observable")
        registry.failOn = null
        expect(install() && registry.count == 3, "Retry installs one hook per event")
        val owner = MainUI()
        invoke(owner, "getLayoutView"); owner.resume()
        expect(ConversationTabsRuntime.hostCount() == 1, "Retried hooks mount a host")
    }
    println("Conversation tabs lifecycle: $scenarios scenarios, $checks checks, 0 failures")
}
