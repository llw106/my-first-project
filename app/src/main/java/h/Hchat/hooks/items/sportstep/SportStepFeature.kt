package h.Hchat.hooks.items.sportstep

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.os.SystemClock
import android.util.SparseArray
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.event.Events
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.preferences.HchatStorage
import h.Hchat.utils.HLog
import java.lang.reflect.Field

/**
 * 运动步数修改。
 *
 * 逻辑一比一对齐开源模块 WeChatEnhancements（io.github.yylsping.wechatantirecall）：
 *
 *   落点：只有一个 —— android.hardware.SystemSensorManager$SensorEventQueue#dispatchSensorEvent
 *   参数：args[0]=handle(Int)，args[1]=values(FloatArray)，args[2]=accuracy(Int)，args[3]=timestamp(Long)
 *   判定：this.mSensorsEvents.get(handle) -> SensorEvent.sensor -> type
 *   只处理：TYPE_STEP_DETECTOR(18) 与 TYPE_STEP_COUNTER(19)
 *
 *   TYPE_STEP_COUNTER(19)
 *      auto（自动增加）：offset += (raw - last) * value，输出 raw + offset
 *      其它（倍率）    ：输出 raw * value
 *   TYPE_STEP_DETECTOR(18)
 *      auto（自动增加）：输出 raw + value
 *      其它（倍率）    ：输出 raw * value
 *
 * 重点：绝不把累计值一次性跳到目标。微信算的是今日增量，跳变会被当成异常数据丢弃；
 *      只有"每走一步就按倍数补一点"这种平滑增量才会被正常计入，所以是实时生效的。
 */
class SportStepFeature : BaseFeature() {

    private var host: Context? = null
    private var installed = false
    private var lastLogAt = 0L

    /** 上一次读到的累计值。 */
    @Volatile
    private var lastRawCounter = Float.NaN

    /** 累积偏移。 */
    @Volatile
    private var autoOffset = 0f

    private val counterLock = Any()

    override fun featureId(): String = ID

    override fun name(): String = "微信步数"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(SportStepSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        host = context.hostContext()
        host?.let { runCatching { SportStepSettings.migrate(it) } }
        installHooks(context)
        subscribe(Events.DexReady::class.java) { installHooks(context) }
    }

    @Synchronized
    private fun installHooks(context: FeatureContext) {
        if (installed) return
        val classLoader = runCatching { context.hostClassLoader() }.getOrNull() ?: return

        val count = hookSensorQueue(classLoader)
        installed = true
        HLog.e("$TAG 已挂载 传感器分发=$count")
    }

    // ---------------- 唯一落点 ----------------

    private fun hookSensorQueue(classLoader: ClassLoader): Int {
        var hits = 0
        val queue = runCatching {
            Class.forName("android.hardware.SystemSensorManager\$SensorEventQueue", false, classLoader)
        }.getOrNull() ?: return 0

        queue.declaredMethods
            .filter { it.name == "dispatchSensorEvent" }
            .forEach { method ->
                val done = runCatching {
                    method.isAccessible = true
                    HookRegistry.get().hook(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            runCatching { modifySensorEvent(param) }
                        }
                    })
                }.isSuccess
                if (done) hits++
            }
        return hits
    }

    /** 完全对应插件的 modifySportSensorEvent。 */
    private fun modifySensorEvent(param: XC_MethodHook.MethodHookParam) {
        val ctx = host ?: return
        val c = config(ctx)
        if (!c.enabled) return

        val args = param.args ?: return
        if (args.size < 2) return
        val handle = args[0] as? Int ?: return
        val values = args[1] as? FloatArray ?: return
        if (values.isEmpty()) return

        val raw = values[0]
        if (!raw.isFinite() || raw < 0f) return

        val events = readField(param.thisObject, "mSensorsEvents") as? SparseArray<*> ?: return
        val event = events.get(handle) as? SensorEvent ?: return
        val sensor = readField(event, "sensor") as? Sensor ?: return
        val type = sensor.type
        if (type != Sensor.TYPE_STEP_COUNTER && type != Sensor.TYPE_STEP_DETECTOR) return

        if (c.value <= 0L) return
        val value = c.value.toFloat()
        val isAuto = c.mode != SportStepSettings.MODE_MULTIPLY

        val out: Float = when (type) {
            Sensor.TYPE_STEP_COUNTER -> if (isAuto) {
                synchronized(counterLock) {
                    if (lastRawCounter.isNaN() || raw < lastRawCounter) {
                        // 首次、跨天、计数器归零
                        autoOffset = 0f
                    } else if (raw > lastRawCounter) {
                        autoOffset += (raw - lastRawCounter) * value
                    }
                    lastRawCounter = raw
                    raw + autoOffset
                }
            } else {
                raw * value
            }

            Sensor.TYPE_STEP_DETECTOR -> if (isAuto) raw + value else raw * value

            else -> return
        }

        if (!out.isFinite() || out < 0f) return
        values[0] = out
        trace("type=$type raw=$raw -> $out")
    }

    private fun readField(target: Any, name: String): Any? {
        var c: Class<*>? = target.javaClass
        while (c != null && c != Any::class.java) {
            val f: Field? = runCatching { c!!.getDeclaredField(name) }.getOrNull()
            if (f != null) {
                f.isAccessible = true
                return runCatching { f.get(target) }.getOrNull()
            }
            c = c.superclass
        }
        return null
    }

    // ---------------- 配置 ----------------

    private data class SportCfg(val enabled: Boolean, val mode: Int, val value: Long)

    @Volatile
    private var cfg: SportCfg? = null

    @Volatile
    private var cfgAt = 0L

    /**
     * 读取配置（2 秒节流）。
     *
     * 优先读界面写下的纯文本快照（[SportStepSettings.runtime]）：FastKV 是 mmap，跨进程看不到新值，
     * 而强行 reopen 会把设置界面手里的实例关掉，导致用户改的倍数丢失（改成 50 又变回 5）。
     * 快照不存在时才退回 FastKV 直接读，且绝不 close。
     */
    private fun config(ctx: Context): SportCfg {
        val now = SystemClock.elapsedRealtime()
        if (now - cfgAt < 2_000L && cfg != null) return cfg!!
        cfgAt = now

        val snapshot = SportStepSettings.runtime(ctx)
        val parsed = if (snapshot != null) {
            SportCfg(snapshot.enabled, snapshot.mode, snapshot.value)
        } else {
            runCatching {
                val sp = HchatStorage.preferences(ctx, SportStepSettings.PREFS_NAME)
                SportCfg(
                    sp.getBoolean(SportStepSettings.KEY_ENABLE, false),
                    sp.getInt(SportStepSettings.KEY_MODE, SportStepSettings.DEFAULT_MODE),
                    sp.getString(SportStepSettings.KEY_VALUE, "").orEmpty().trim().toLongOrNull() ?: -1L
                )
            }.getOrNull()
        }
        if (parsed != null) cfg = parsed
        return parsed ?: SportCfg(false, SportStepSettings.DEFAULT_MODE, -1L)
    }

    /** 只在真正改写时打一条日志，10 秒节流，避免刷屏拖慢微信主线程。 */
    private fun trace(text: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLogAt < 10_000L) return
        lastLogAt = now
        HLog.e("$TAG $text")
    }

    companion object {
        const val ID = "sport_step"
        private const val TAG = "[Hchat:SportStep]"
    }
}
