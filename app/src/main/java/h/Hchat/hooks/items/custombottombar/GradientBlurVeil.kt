package h.Hchat.hooks.items.custombottombar

import android.graphics.BlendMode
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import kotlin.math.abs
import kotlin.math.min

/**
 * 底部渐变模糊（渐变算法以参照 APK 的 smali `f9/gb.smali` 为准）。
 *
 * 与「给胶囊自身做渐变」的做法不同：这里把一条渲染链直接打在
 * 「底栏宿主的兄弟节点里面积最大的那个可见 View」（= 页面内容区）上，
 * 因此渐变带覆盖底栏下方整块区域，不受胶囊形状裁剪。
 *
 * 渲染链（与 gb.apply 完全一致）：
 *   原图(runtimeShader passthrough)
 *   ⊕ blend SRC_OVER
 *   遮罩(runtimeShader mask，按 y 做 smoothstep alpha 斜坡) over 模糊链
 *   = RenderEffect.createBlendModeEffect(
 *         passEffect,
 *         RenderEffect.createChainEffect(maskEffect, blurEffect),
 *         BlendMode.SRC_OVER)
 *
 * 对应关系：
 *   gb.<init>      → 本类构造（shader 编译）
 *   gb.apply       → apply()
 *   gb.clamp       → clamp()
 *   gb.resolveTarget → resolveTarget()
 *   gb.clear       → clear()
 *   gb.update      → update()
 */
internal class GradientBlurVeil(private val host: View) {

    private val density = host.resources.displayMetrics.density

    private val passthrough: RuntimeShader? =
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching { RuntimeShader(PASSTHROUGH_SHADER) }
                .onFailure { Log.w(TAG, "passthrough shader init failed", it) }
                .getOrNull()
        } else {
            null
        }

    private val mask: RuntimeShader? =
        if (Build.VERSION.SDK_INT >= 33) {
            runCatching { RuntimeShader(MASK_SHADER) }
                .onFailure { Log.w(TAG, "mask shader init failed", it) }
                .getOrNull()
        } else {
            null
        }

    private var target: View? = null
    private var appliedView: View? = null
    private var appliedTop = Float.NaN
    private var appliedBottom = Float.NaN
    private var appliedW = 0
    private var appliedH = 0

    private var lastShown = true
    private var lastCheck = 0L
    private var lastProbe = 0L

    private val hostLoc = IntArray(2)
    private val targetLoc = IntArray(2)
    private val rect = Rect()

    /**
     * @param shown 底栏是否可见（对应 6.3.8 里 ft0.b() 的可见性布尔）
     * @param enabled 「底部渐变模糊」开关（6.3.8 里该值由 prefs 每次读取，默认 true）
     */
    fun update(shown: Boolean, enabled: Boolean) {
        val maskShader = mask
        val passShader = passthrough
        if (maskShader == null || passShader == null) return

        if (!shown) {
            if (lastShown) {
                lastShown = false
                clear()
            }
            return
        }
        lastShown = true

        val now = SystemClock.uptimeMillis()
        if (now - lastCheck < CHECK_INTERVAL_MS) return
        lastCheck = now

        if (!enabled) {
            clear()
            return
        }
        if (!host.isAttachedToWindow) {
            clear()
            return
        }

        val targetView = resolveTarget(now) ?: return
        if (!targetView.isAttachedToWindow ||
            targetView.visibility != View.VISIBLE ||
            targetView.width <= 0 ||
            targetView.height <= 0
        ) {
            clear()
            return
        }

        host.getLocationInWindow(hostLoc)
        targetView.getLocationInWindow(targetLoc)
        val zoneTop = clamp((hostLoc[1] - targetLoc[1]).toFloat(), 0f, (targetView.height - 8).toFloat())
        val zoneBottom = min(targetView.height.toFloat(), (host.rootView.height - targetLoc[1]).toFloat())
        if (zoneBottom - zoneTop < MIN_ZONE_PX) return

        val unchanged = targetView === appliedView &&
            targetView.width == appliedW &&
            targetView.height == appliedH &&
            abs(zoneTop - appliedTop) <= TOP_EPSILON &&
            abs(zoneBottom - appliedBottom) <= TOP_EPSILON
        if (unchanged) return

        apply(targetView, zoneTop, zoneBottom)
    }

    fun clear() {
        val view = appliedView
        if (view != null && (appliedW != 0 || appliedH != 0 || !appliedTop.isNaN())) {
            runCatching { view.setRenderEffect(null) }
        }
        appliedTop = Float.NaN
        appliedBottom = Float.NaN
        appliedW = 0
        appliedH = 0
    }

    private fun resolveTarget(now: Long): View? {
        val cached = target
        if (cached != null) {
            if (!cached.isAttachedToWindow || cached.width <= 0 || cached.visibility != View.VISIBLE) {
                clear()
                target = null
            } else {
                return cached
            }
        }
        if (now - lastProbe < PROBE_INTERVAL_MS) return null
        lastProbe = now

        val parent = host.parent as? ViewGroup ?: return null
        var best: View? = null
        var bestArea = 0L
        for (index in 0 until parent.childCount) {
            val child = parent.getChildAt(index)
            if (child === host) continue
            if (child.visibility != View.VISIBLE) continue
            if (child.alpha < 0.5f) continue
            rect.setEmpty()
            if (!child.getGlobalVisibleRect(rect)) continue
            if (!rect.intersect(0, 0, parent.width, parent.height)) continue
            val area = rect.height().toLong() * rect.width().toLong()
            if (area > bestArea) {
                best = child
                bestArea = area
            }
        }
        target = best
        return best
    }

    private fun apply(view: View, zoneTop: Float, zoneBottom: Float) {
        val passShader = passthrough ?: return
        val maskShader = mask ?: return

        val passEffect = RenderEffect.createRuntimeShaderEffect(passShader, "content")
        // 参照版取值：axis=(0,1) 竖向投影、band=(zoneTop,zoneBottom)、curve=0.5、level=0、slope=-1
        maskShader.setFloatUniform("in_gradAxis", 0f, 1f)
        maskShader.setFloatUniform("in_gradBand", zoneTop, zoneBottom)
        maskShader.setFloatUniform("in_curve", 0.5f)
        maskShader.setFloatUniform("in_level", 0f)
        maskShader.setFloatUniform("in_slope", -1f)
        val radius = BLUR_DP * density
        val maskEffect = RenderEffect.createRuntimeShaderEffect(maskShader, "child")
        val blurEffect = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.MIRROR)
        val chain = RenderEffect.createChainEffect(maskEffect, blurEffect)
        val result = RenderEffect.createBlendModeEffect(passEffect, chain, BlendMode.SRC_OVER)

        val previous = appliedView
        if (previous != null && previous !== view) {
            runCatching { previous.setRenderEffect(null) }
        }
        view.setRenderEffect(result)

        appliedView = view
        appliedW = view.width
        appliedH = view.height
        appliedTop = zoneTop
        appliedBottom = zoneBottom
    }

    private fun clamp(value: Float, minValue: Float, maxValue: Float): Float =
        when {
            value < minValue -> minValue
            value > maxValue -> maxValue
            else -> value
        }

    private companion object {
        const val TAG = "Hchat:Veil"
        const val CHECK_INTERVAL_MS = 100L
        const val PROBE_INTERVAL_MS = 250L
        const val BLUR_DP = 27f
        const val MIN_ZONE_PX = 8f
        const val TOP_EPSILON = 3f

        val PASSTHROUGH_SHADER = """
            uniform shader content;

            half4 main(float2 coord) {
                return content.eval(coord);
            }
        """.trimIndent()

        // 与参照 APK 反编译出的 smali `f9/gb.smali` 的 BLUR_MASK_SHADER 逐字一致。
        // 5 参数投影渐变（轴/带/曲线/电平/斜率），实际调用参数 axis=(0,1)、curve=0.5、
        // level=0、slope=-1，代入后等价于 smoothstep(sqrt(smoothstep(t)))。
        val MASK_SHADER = """
            uniform shader child;
            uniform float2 in_gradAxis;
            uniform float2 in_gradBand;
            uniform float in_curve;
            uniform float in_level;
            uniform float in_slope;

            half4 main(float2 xy) {
                float p = dot(xy, in_gradAxis);
                float raw = clamp((p - in_gradBand.x) / (in_gradBand.y - in_gradBand.x), 0.0, 1.0);
                raw = pow(raw * raw * (3.0 - 2.0 * raw), in_curve);
                float w = clamp(in_level - raw * in_slope, 0.0, 1.0);
                w = w * w * (3.0 - 2.0 * w);
                half4 c = child.eval(xy);
                if (c.a > 0.0039) {
                    c = half4(c.rgb / c.a, 1.0);
                }
                return c * half(w);
            }
        """.trimIndent()
    }
}
