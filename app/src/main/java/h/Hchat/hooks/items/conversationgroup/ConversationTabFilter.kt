package h.Hchat.hooks.items.conversationgroup

/**
 * 会话页签功能已下线（source 已移除）：这里保留聊天分组首页投影所需的最小过滤契约。
 * 恒为 inactive，等价于「不做页签过滤」，聊天分组本身的展示与分组逻辑不受影响。
 */
internal class ConversationTabFilter {
    val active: Boolean = false
    val predicate: String = "1"

    fun accepts(username: String, unread: Int, mutedUnread: Int, verifyFlag: Int): Boolean = true

    fun rootWhere(original: String): String = original

    fun query(sql: String): String = sql
}