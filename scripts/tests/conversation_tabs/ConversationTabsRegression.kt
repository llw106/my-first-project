package h.Hchat.hooks.items.conversationtabs

private var checks = 0

private fun expect(value: Boolean, message: String) {
    checks++
    check(value) { message }
}

fun main() {
    val defaults = ConversationTabsConfig.defaults()
    expect(defaults.map { it.kind } == listOf(
        ConversationTabKind.ALL,
        ConversationTabKind.UNREAD,
        ConversationTabKind.GROUP,
        ConversationTabKind.PRIVATE,
        ConversationTabKind.OFFICIAL
    ), "default tabs keep the native categories")

    val normalized = ConversationTabsConfig.normalize(
        ConversationTabsConfig(
            enabled = true,
            tabs = listOf(
                ConversationTab("", "ignored", ConversationTabKind.CUSTOM),
                ConversationTab("custom", "  ", ConversationTabKind.CUSTOM,
                    conversationIds = setOf(" alice ", "", "alice"))
            )
        )
    )
    expect(normalized.tabs.first().kind == ConversationTabKind.ALL, "all tab is restored")
    expect(normalized.tabs.last().name == "自定义", "blank names use the kind title")
    expect(normalized.tabs.last().conversationIds == setOf("alice"), "conversation IDs are trimmed")

    val group = ConversationTabFilter(ConversationTab("g", "群聊", ConversationTabKind.GROUP))
    expect(group.accepts("room@chatroom", 0, 0, 0), "chatrooms match group tab")
    expect(!group.accepts("alice", 0, 0, 0), "private contact does not match group tab")
    expect(!group.accepts("wxid_hchat_group_root", 1, 0, 0), "virtual groups stay hidden")

    val unread = ConversationTabFilter(ConversationTab("u", "未读", ConversationTabKind.UNREAD))
    expect(unread.accepts("alice", 0, 1, 0), "muted unread conversations match")
    expect(!unread.accepts("alice", 0, 0, 0), "read conversations do not match")

    val custom = ConversationTabFilter(ConversationTab("c", "自定义", ConversationTabKind.CUSTOM,
        conversationIds = setOf("o'hare")))
    expect(custom.accepts("o'hare", 0, 0, 0), "custom IDs are matched literally")
    val sql = custom.query(
        "SELECT username FROM rconversation WHERE parentRef IS NULL ORDER BY conversationTime DESC LIMIT 20"
    )
    expect("rconversation.username IN ('o''hare')" in sql, "custom SQL escapes literals")
    expect("ORDER BY conversationTime DESC LIMIT 20" in sql, "query tail remains unchanged")

    val aliasSql = custom.query(
        "SELECT c.username FROM rconversation AS c WHERE c.parentRef IS NULL ORDER BY c.conversationTime DESC LIMIT 20"
    )
    expect("C.username IN ('o''hare')" in aliasSql, "aliased conversation SQL uses the alias")
    expect("c.parentRef" in aliasSql, "aliased parentRef remains qualified")
    expect("ORDER BY c.conversationTime DESC LIMIT 20" in aliasSql, "aliased query tail remains unchanged")

    val noWhereSql = custom.query("SELECT username FROM rconversation ORDER BY conversationTime DESC LIMIT 20")
    expect("FROM rconversation" in noWhereSql &&
        "WHERE ((rconversation.username IN ('o''hare'))" in noWhereSql,
        "queries without WHERE receive a filter before ORDER")

    val all = ConversationTabFilter(ConversationTab("all", "全部", ConversationTabKind.ALL))
    val original = "SELECT username FROM rconversation WHERE parentRef IS NULL LIMIT 20"
    expect(all.query(original) == original, "all tab leaves native SQL untouched")
    expect(all.rootWhere("parentRef IS NULL") == "parentRef IS NULL", "all tab leaves root predicate untouched")

    println("ConversationTabs: $checks checks passed")
}
