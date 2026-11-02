package h.Hchat.hooks.items.conversationgroup

private fun check(value: Boolean, message: String) {
    if (!value) error(message)
}

fun main() {
    val projected = setOf("gh_one", "gh_two")
    val partial = ConversationGroupHomeVisibility.hidden(
        projected,
        mapOf("officialaccounts" to setOf("gh_one", "gh_external"))
    )
    check("gh_one" in partial && "gh_two" in partial, "projected official rows must be hidden")
    check("officialaccounts" !in partial, "partially grouped native aggregate must stay visible")

    val complete = ConversationGroupHomeVisibility.hidden(
        projected,
        mapOf("officialaccounts" to projected)
    )
    check("officialaccounts" in complete, "fully grouped native aggregate must be hidden")

    val empty = ConversationGroupHomeVisibility.hidden(
        projected,
        mapOf("officialaccounts" to emptySet())
    )
    check("officialaccounts" !in empty, "empty aggregate must stay visible")

    val sql = ConversationGroupHomeVisibility.rootWhere(
        "((parentRef is null) or (parentRef=''))",
        setOf("officialaccounts", "o'hare")
    )
    check(sql.startsWith("(((parentRef is null) or (parentRef='')))") , "original root predicate must remain")
    check("'officialaccounts'" in sql && "'o''hare'" in sql, "SQL literals must be escaped")

    println("conversation group home visibility regression passed")
}
