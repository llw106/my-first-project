package h.Hchat.hooks.items.conversationgroup

import android.content.SharedPreferences

internal object ConversationGroupStore {
    var account = "account-a"
    fun accountKey(): String = account
}

internal class MemoryPreferences(
    initial: String = "{}",
    val events: MutableList<String> = arrayListOf()
) : SharedPreferences {
    val memory = linkedMapOf("original_parent_refs" to initial)
    val disk = memory.toMutableMap()
    val failCommits = hashSetOf<Int>()
    var commitCount = 0
    var afterCommit: (() -> Unit)? = null

    override fun getString(key: String, defaultValue: String?): String? = memory[key] ?: defaultValue
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            // FastKV's editor mutates its in-memory map before commit reports disk failure.
            if (value == null) memory.remove(key) else memory[key] = value
            return this
        }
        override fun commit(): Boolean {
            commitCount++
            val success = commitCount !in failCommits
            events.add("commit:$success")
            if (success) {
                disk.clear()
                disk.putAll(memory)
            }
            afterCommit?.invoke()
            return success
        }
    }
}
