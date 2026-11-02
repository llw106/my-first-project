package h.Hchat.hooks.core

import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import java.lang.reflect.Method

open class BaseFeature {
    open fun featureId(): String = ""
    open fun name(): String = ""
    open fun onFeatureInit(context: FeatureContext) {}
    open fun onFeatureInstall(context: FeatureContext) {}
    fun registerSettingsProvider(provider: Any) {}
    fun logError(message: String, error: Throwable?) {}
}
class FeatureContext {
    fun hostContext(): Context = Context()
    fun dexFinder(): Finder = Finder()
    fun eventBus(): EventBus = EventBus()
}
class Finder { val addMsgClasses: List<Class<*>>? = null }
class EventBus { fun post(event: Any) {} }
object DexInstallScheduler {
    enum class Stage { WARMUP }
    fun schedule(id: String, name: String, stage: Stage, task: () -> Boolean) {}
}
object HookRegistry {
    fun get(): HookRegistry = this
    fun hook(method: Method, callback: XC_MethodHook) {}
}
