package h.Hchat.hooks.api.message
import h.Hchat.hooks.api.model.WeChatParsedMessage
class WeChatMessageParseApi {
    fun parseAddMsg(value: Any, selfWxId: String): WeChatParsedMessage? = null
    fun isLikelyAddMsgClass(type: Class<*>): Boolean = false
}
