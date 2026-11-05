package h.Hchat.hooks.items.wechatstep

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
import h.Hchat.utils.HLog
import java.lang.reflect.Field
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

/**
 * 微信步数修改（传感器方案）。
 *
 * 落点只有一个：`android.hardware.SystemSensorManager$SensorEventQueue#dispatchSensorEvent`
 *
 *   args[0] = handle(Int)，args[1] = values(FloatArray)，args[2] = accuracy，args[3] = timestamp
 *   this.mSensorsEvents.get(handle) -> SensorEvent.sensor -> type
 *
 * 只处理 TYPE_STEP_COUNTER(19) 和 TYPE_STEP_DETECTOR(18)：
 *
 *   COUNTER（累计值）
 *     自动模式：每个事件把 extra 加到倍率，输出 基准 + 真实增量 + extra
 *     倍率模式：真实增量 × 倍率累加进 extra，输出同上
 *   DETECTOR（单步事件）
 *     输出 mult / raw*mult，并按剩余额度封顶
 *
 * 相对二改版修掉三处问题：
 *   1. 过了截止时间不再把 values[0] 写 0（那会让当天步数掉到 0），而是保持原值；
 *   2. DETECTOR 分支超时后也不再继续累加；
 *   3. 跨天时把基准与 lastOut 一起对齐到当天的真实计数值，避免"昨天封顶 80000、
 *      今天一整天都停在 80000"。
 *
 * 性能上（该回调每次计步都会进）：
 *   - `mSensorsEvents` 字段按 Class 缓存，不再每个事件做一次 getDeclaredField；
 *   - `SensorEvent.sensor` 直接取公开字段，不做反射；
 *   - 日期/截止时间判断按 5 秒窗口缓存，不再每个事件 new Calendar；
 *   - 达到上限后直接返回，跳过后续算术；
 *   - 日志走 HLog.i（不建异常、不抓栈）并 10 秒节流，且放在锁外输出。
 */
class WechatStepFeature : BaseFeature() {

    private var host: Context? = null

    @Volatile
    private var installed = false

    @Volatile
    private var lastLogAt = 0L

    @Volatile
    private var cfg: WechatStepSettings.Config? = null

    @Volatile
    private var cfgAt = 0L

    private val lock = Any()

    /** SensorEventQueue 的实现类 -> mSensorsEvents 字段，只解析一次。 */
    private val eventsFieldCache = ConcurrentHashMap<Class<*>, Field>()

    private var stateDay = 0
    private var baseRaw = Float.NaN
    private var lastRaw = Float.NaN
    private var virtualBase = 0f
    private var extra = 0f
    private var lastOut = 0f

    private var clockDay = 0
    private var clockInWindow = true
    private var clockAt = 0L

    override fun featureId(): String = ID

    override fun name(): String = "微信步数修改"

    /** 钩子常驻安装，改数据与否由配置里的开关决定，这样开关即时生效。 */
    override fun isEnabled(context: FeatureContext): Boolean = true

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(WechatStepSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        host = context.hostContext()
        installHooks(context)
        subscribe(Events.DexReady::class.java) { installHooks(context) }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        host = null
    }

    @Synchronized
    private fun installHooks(context: FeatureContext) {
        if (installed) return
        val classLoader = runCatching { context.hostClassLoader() }.getOrNull() ?: return
        val count = hookSensorQueue(classLoader)
        installed = count > 0
        HLog.i("$TAG 传感器 Hook 已挂载入口数=$count")
    }

    private fun hookSensorQueue(classLoader: ClassLoader): Int {
        val queue = runCatching {
            Class.forName("android.hardware.SystemSensorManager\$SensorEventQueue", false, classLoader)
        }.getOrNull() ?: return 0

        var hits = 0
        queue.declaredMethods
            .filter { method ->
                method.name == "dispatchSensorEvent" &&
                    method.returnType == Void.TYPE &&
                    method.parameterTypes.size >= 4 &&
                    method.parameterTypes[0] == Integer.TYPE &&
                    method.parameterTypes[1] == FloatArray::class.java
            }
            .forEach { method ->
                val ok = runCatching {
                    method.isAccessible = true
                    HookRegistry.get().hook(method, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            runCatching { rewriteSensorEvent(param) }
                                .onFailure { logError("改写传感器事件失败", it) }
                        }
                    })
                }.isSuccess
                if (ok) hits++
            }
        return hits
    }

    private fun rewriteSensorEvent(param: XC_MethodHook.MethodHookParam) {
        val context = host ?: return
        val config = config(context)
        if (!config.enabled) return

        val args = param.args ?: return
        if (args.size < 2) return
        val handle = args[0] as? Int ?: return
        val values = args[1] as? FloatArray ?: return
        if (values.isEmpty()) return

        val raw = values[0]
        if (!raw.isFinite() || raw < 0f) return

        val queue = param.thisObject ?: return
        val field = eventsField(queue.javaClass) ?: return
        val events = runCatching { field.get(queue) as? SparseArray<*> }.getOrNull() ?: return
        val event = events.get(handle) as? SensorEvent ?: return
        val type = event.sensor?.type ?: return
        if (type != Sensor.TYPE_STEP_COUNTER && type != Sensor.TYPE_STEP_DETECTOR) return

        val max = config.maximum.toFloat()
        val multiplier = config.multiplier.toFloat()
        var message: String? = null

        synchronized(lock) {
            refreshClock(config)

            if (clockDay != stateDay) {
                stateDay = clockDay
                baseRaw = raw
                lastRaw = raw
                // 跨天：基准与输出都对齐到今天的真实计数值，避免昨天封顶把今天锁死。
                virtualBase = raw
                extra = 0f
                lastOut = raw
            }

            // 传感器服务重启后累计值会归零，用虚拟基准把数值接上，避免当天步数跳水。
            if (raw + SENSOR_RESET_GAP < lastRaw) {
                virtualBase = lastOut - extra
                baseRaw = raw
            }

            when (type) {
                Sensor.TYPE_STEP_COUNTER -> if (clockInWindow) {
                    if (lastOut < max) {
                        if (config.autoIncrease) {
                            extra = (extra + multiplier).coerceAtMost(max)
                        } else {
                            val delta = raw - lastRaw
                            if (delta > 0f && delta < MAX_REASONABLE_DELTA) {
                                extra = (extra + delta * (multiplier - 1f)).coerceAtMost(max)
                            }
                        }
                        val walked = raw - baseRaw
                        val out = (virtualBase + (if (walked > 0f) walked else 0f) + extra)
                            .coerceIn(0f, max)
                        if (out > lastOut) lastOut = out
                    }
                    values[0] = lastOut
                }

                Sensor.TYPE_STEP_DETECTOR -> if (clockInWindow) {
                    val remain = (max - lastOut).coerceAtLeast(0f)
                    val want = if (config.autoIncrease) {
                        multiplier
                    } else {
                        (raw * multiplier).coerceAtLeast(raw)
                    }
                    val applied = want.coerceAtMost(remain)
                    lastOut += applied
                    values[0] = applied
                }
            }

            lastRaw = raw

            val now = SystemClock.elapsedRealtime()
            if (now - lastLogAt >= LOG_INTERVAL_MS) {
                lastLogAt = now
                message = "type=$type raw=$raw out=${values[0]} extra=$extra lastOut=$lastOut"
            }
        }

        // 日志放到锁外，避免写日志（跨进程 IPC）拖住传感器线程持有锁的时间。
        message?.let { HLog.i("$TAG $it") }
    }

    /** 日期与截止时间每 5 秒才重算一次，计步事件高频，Calendar 不该每个事件都造。 */
    private fun refreshClock(config: WechatStepSettings.Config) {
        val now = SystemClock.elapsedRealtime()
        if (clockAt != 0L && now - clockAt < CLOCK_INTERVAL_MS) return
        clockAt = now
        val calendar = Calendar.getInstance()
        clockDay = calendar.get(Calendar.YEAR) * 10000 +
            (calendar.get(Calendar.MONTH) + 1) * 100 +
            calendar.get(Calendar.DAY_OF_MONTH)
        clockInWindow = calendar.get(Calendar.HOUR_OF_DAY) < config.endHour
    }

    private fun eventsField(clazz: Class<*>): Field? {
        eventsFieldCache[clazz]?.let { return it }
        var current: Class<*>? = clazz
        while (true) {
            val target = current ?: break
            if (target == Any::class.java) break
            val found = runCatching { target.getDeclaredField(FIELD_NAME) }.getOrNull()
            if (found != null) {
                runCatching { found.isAccessible = true }
                eventsFieldCache[clazz] = found
                return found
            }
            current = target.superclass
        }
        return null
    }

    private fun config(context: Context): WechatStepSettings.Config {
        val now = SystemClock.elapsedRealtime()
        val cached = cfg
        if (cached != null && now - cfgAt < CONFIG_TTL_MS) return cached
        val loaded = WechatStepSettings.read(context)
        cfg = loaded
        cfgAt = now
        return loaded
    }

    companion object {
        const val ID = "wechat_step"
        private const val TAG = "[Hchat:WechatStep]"
        private const val FIELD_NAME = "mSensorsEvents"
        private const val CONFIG_TTL_MS = 2_000L
        private const val CLOCK_INTERVAL_MS = 5_000L
        private const val LOG_INTERVAL_MS = 10_000L
        private const val SENSOR_RESET_GAP = 100f
        private const val MAX_REASONABLE_DELTA = 500f
    }
}