package h.Hchat.hooks.items.sportstep

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import de.robv.android.xposed.XC_MethodHook
import h.Hchat.event.Events
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.hooks.core.HookRegistry
import h.Hchat.utils.HLog

class SportStepFeature : BaseFeature() {
    private var host: Context? = null
    private var installed = false

    override fun featureId(): String = ID

    override fun name(): String = "微信步数"

    override fun onFeatureInit(context: FeatureContext) {
        registerSettingsProvider(SportStepSettingsProvider())
    }

    override fun onFeatureInstall(context: FeatureContext) {
        host = context.hostContext()
        installHook(context)
        subscribe(Events.DexReady::class.java) { installHook(context) }
    }

    @Synchronized
    private fun installHook(context: FeatureContext) {
        if (installed) return
        val classLoader = runCatching { context.hostClassLoader() }.getOrNull() ?: return
        val target: Class<*> = runCatching {
            classLoader.loadClass(SPORT_LISTENER_CLASS)
        }.getOrNull() ?: return
        val method = target.declaredMethods.firstOrNull { candidate ->
            candidate.name == "onSensorChanged" &&
                candidate.parameterTypes.size == 1 &&
                candidate.parameterTypes[0].name == "android.hardware.SensorEvent"
        } ?: return
        runCatching {
            HookRegistry.get().hook(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    rewriteStep(param)
                }
            })
            installed = true
            HLog.e("$TAG 已挂载步数改写: ${target.name}#${method.name}")
        }.onFailure { HLog.e("$TAG 挂载失败", it) }
    }

    private fun rewriteStep(param: XC_MethodHook.MethodHookParam) {
        val context = host ?: return
        if (!SportStepSettings.enabled(context)) return
        val event = param.args.getOrNull(0) as? SensorEvent ?: return
        val sensor = event.sensor ?: return
        if (sensor.type != Sensor.TYPE_STEP_COUNTER) return
        val values = event.values
        if (values.isEmpty()) return
        val real = values[0]
        if (real <= 0f) return
        val mode = SportStepSettings.mode(context)
        val value = SportStepSettings.value(context)
        val base = SportStepSettings.dayBase(context, real)
        val reported = if (mode == SportStepSettings.MODE_MULTIPLY) {
            base + (real - base) * value.toFloat()
        } else {
            base + value.toFloat()
        }
        if (reported.isFinite() && reported > real) {
            values[0] = reported
        }
    }

    companion object {
        const val ID = "sport_step"
        private const val TAG = "[Hchat:SportStep]"
        private const val SPORT_LISTENER_CLASS = "com.tencent.mm.plugin.sport.model.a0"
    }
}
