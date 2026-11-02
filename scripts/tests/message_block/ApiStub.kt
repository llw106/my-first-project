package h.Hchat.hooks.api.core
import h.Hchat.hooks.api.message.WeChatMessageParseApi
object WeChatApis {
    fun messageParser(): WeChatMessageParseApi? = null
    fun account(): Account? = null
}
class Account { fun selfWxId(): String = "self" }
