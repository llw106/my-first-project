package h.Hchat.hooks.items.profilewxid

import h.Hchat.event.Events
import h.Hchat.hooks.core.BaseFeature
import h.Hchat.hooks.core.ConversationMenuExtension
import h.Hchat.hooks.core.ConversationMenuExtensionRegistry
import h.Hchat.hooks.core.ConversationMenuExtensionTarget
import h.Hchat.hooks.core.DexInstallScheduler
import h.Hchat.hooks.core.FeatureContext
import h.Hchat.ui.miuix.VoiceForwardMiuixDialog

class ProfileWxidFeature : BaseFeature() {

    private var runtime: ProfileWxidRuntime? = null

    override fun featureId(): String = ID

    override fun name(): String = "自定义微信号"

    override fun onFeatureInit(context: FeatureContext) {
        // 配置项并入「自定义好友资料」页，首页不再单独列出。
    }

    override fun onFeatureInstall(context: FeatureContext) {
        ConversationMenuExtensionRegistry.register(conversationMenuExtension)
        runtime = ProfileWxidRuntime(context, ::logFeatureError)
        scheduleInstall()
        subscribe(Events.DexReady::class.java) { scheduleInstall() }
    }

    override fun onFeatureDestroy(context: FeatureContext) {
        ConversationMenuExtensionRegistry.unregister(conversationMenuExtension)
        runtime?.destroy()
        runtime = null
    }

    private fun scheduleInstall() {
        DexInstallScheduler.schedule(ID, name()) {
            val target = runtime ?: return@schedule false
            target.install()
            true
        }
    }

    private fun logFeatureError(message: String, throwable: Throwable?) {
        logError(message, throwable)
    }

    private val conversationMenuExtension = object : ConversationMenuExtension {
        override val itemId: Int = CONVERSATION_MENU_ID
        override val order: Int = 122

        override fun title(target: ConversationMenuExtensionTarget): String = MENU_TITLE

        override fun isVisible(target: ConversationMenuExtensionTarget): Boolean {
            val host = context()?.hostContext() ?: return false
            return ProfileWxidSettings.menuEnabled(host) && target.talker.trim().isNotEmpty()
        }

        override fun onClick(target: ConversationMenuExtensionTarget) {
            showInput(target)
        }
    }

    private fun showInput(target: ConversationMenuExtensionTarget) {
        val activity = target.activity
        if (activity.isFinishing) return
        val talker = target.talker.trim()
        if (talker.isEmpty()) return
        val host = activity.applicationContext ?: return
        val current = ProfileWxidSettings.custom(host, talker).orEmpty()
        VoiceForwardMiuixDialog.showTextInput(
            activity = activity,
            title = "自定义微信号",
            summary = "仅本机资料页显示；留空并确定则按开关隐藏",
            initialValue = current,
            placeholder = "输入想显示的微信号",
            maxLength = 32,
            allowEmpty = true,
            onConfirm = { value ->
                ProfileWxidSettings.setCustom(host, talker, value)
            },
            onDismiss = {}
        )
    }

    companion object {
        const val ID = "profile_wxid"
        private const val MENU_TITLE = "自定义微信号"
        private const val CONVERSATION_MENU_ID = 0x7A1003
    }
}