package h.Hchat.hooks.items.keywordnotify
import android.content.Context
import h.Hchat.hooks.api.model.WeChatParsedMessage
object KeywordNotificationRuntime {
    fun shouldAllowBlockedMessageIntoDatabase(context: Context, message: WeChatParsedMessage): Boolean = false
}
