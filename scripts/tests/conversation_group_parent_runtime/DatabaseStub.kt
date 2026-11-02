package h.Hchat.hooks.api.runtime

internal class WeChatDatabaseApi(
    initial: Map<String, String>,
    val events: MutableList<String> = arrayListOf()
) {
    val parents = initial.toMutableMap()
    val failTalkers = hashSetOf<String>()
    var updateCalls = 0
    var afterUpdate: (() -> Unit)? = null
    fun updateParents(talkers: List<String>, parent: String): Boolean {
        updateCalls++
        events.add("update:${talkers.joinToString()}:$parent")
        if (talkers.any { it in failTalkers }) return false
        talkers.forEach { parents[it] = parent }
        afterUpdate?.invoke()
        return true
    }
}
