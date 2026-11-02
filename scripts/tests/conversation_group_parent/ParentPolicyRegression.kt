package h.Hchat.hooks.items.conversationgroup

private var checks = 0
private fun same(expected: Any?, actual: Any?, label: String) {
    check(expected == actual) { "$label: expected <$expected>, got <$actual>" }
    checks++
}

private const val GROUP_A = "wxid_hchat_group_a"
private const val GROUP_B = "wxid_hchat_group_b"
private const val LEGACY_GROUP = "hchat_conv_group:old"

fun main() {
    val policy = ConversationGroupParentPolicy
    for (parent in listOf(GROUP_A, GROUP_B, LEGACY_GROUP)) {
        same(true, policy.isModuleParent(parent), "module parent $parent")
    }
    for (parent in listOf("", "officialaccounts", "service_officialaccounts", "photoaccounts", "message_fold")) {
        same(false, policy.isModuleParent(parent), "native parent $parent")
    }
    same(true, policy.isOfficial("gh_public", 0, null), "publisher prefix")
    same(true, policy.isOfficial("verified_publisher", 8, ""), "verified publisher")
    same(true, policy.isOfficial("verified_publisher", -1, null), "nonzero verify flag")
    for (parent in listOf("officialaccounts", "service_officialaccounts", "photoaccounts")) {
        same(true, policy.isOfficial("publisher", 0, parent), "native official parent $parent")
    }
    same(false, policy.isOfficial("friend", 0, null), "unknown is not official")
    same(false, policy.isOfficial("room@chatroom", 0, "message_fold"), "ordinary folded chat")
    same(false, policy.isOfficial("officialaccounts", 0, ""), "aggregate username alone")

    // New and manually assigned publishers keep their exact native parents.
    for (native in listOf("", "officialaccounts", "service_officialaccounts", "photoaccounts")) {
        val plan = policy.plan(mapOf("publisher" to GROUP_A), mapOf("publisher" to native),
            setOf("publisher"), emptyMap())
        same(emptyMap<String, String>(), plan.updates, "protected native $native unchanged")
        same(emptyMap<String, String>(), plan.originalParents, "protected native $native no backup")
        same(emptySet<String>(), plan.unresolved, "protected native $native resolved")
    }

    // Existing grouped publishers restore saved native parents, including explicit root.
    for (module in listOf(GROUP_A, LEGACY_GROUP)) {
        for (native in listOf("", "officialaccounts", "service_officialaccounts", "photoaccounts")) {
            val originals = mapOf("publisher" to native)
            val plan = policy.plan(mapOf("publisher" to GROUP_B), mapOf("publisher" to module),
                setOf("publisher"), originals)
            same(mapOf("publisher" to native), plan.updates, "restore $module to $native")
            same(originals, plan.originalParents, "restore backup retained $native")
            same(setOf("publisher"), plan.restoring, "restore tracked $native")
            same(emptySet<String>(), plan.unresolved, "known original $native")
        }
    }

    // Turning off automatic inclusion/removing a group also restores known parents.
    val removed = policy.plan(emptyMap(), mapOf("publisher" to GROUP_A), emptySet(),
        mapOf("publisher" to "officialaccounts"))
    same(mapOf("publisher" to "officialaccounts"), removed.updates, "removed assignment restores")
    same(setOf("publisher"), removed.restoring, "removed assignment restore tracked")

    // Missing or invalid backups never become a fabricated empty/root parent.
    for (backup in listOf(emptyMap(), mapOf("publisher" to GROUP_B), mapOf("publisher" to LEGACY_GROUP))) {
        for (assigned in listOf(emptyMap(), mapOf("publisher" to GROUP_A), mapOf("publisher" to GROUP_B))) {
            for (protected in listOf(emptySet(), setOf("publisher"))) {
                val plan = policy.plan(assigned, mapOf("publisher" to GROUP_A), protected, backup)
                same(emptyMap<String, String>(), plan.updates, "unknown original cannot write")
                same(setOf("publisher"), plan.unresolved, "unknown original keeps parent reachable")
                same(backup, plan.originalParents, "unknown original is retained")
                same(emptySet<String>(), plan.restoring, "unknown original is not restored")
            }
        }
    }

    // Ordinary chats keep existing grouping and capture the current native parent before moving.
    val newlyGrouped = policy.plan(mapOf("friend" to GROUP_A), mapOf("friend" to "message_fold"),
        emptySet(), mapOf("friend" to "stale_parent"))
    same(mapOf("friend" to GROUP_A), newlyGrouped.updates, "ordinary grouping")
    same(mapOf("friend" to "message_fold"), newlyGrouped.originalParents, "fresh native supersedes stale backup")
    val move = policy.plan(mapOf("friend" to GROUP_B), mapOf("friend" to GROUP_A), emptySet(),
        mapOf("friend" to ""))
    same(mapOf("friend" to GROUP_B), move.updates, "ordinary group move")
    same(mapOf("friend" to ""), move.originalParents, "ordinary move preserves original")
    same(emptySet<String>(), move.restoring, "ordinary move is not restoration")
    val unchanged = policy.plan(mapOf("friend" to GROUP_A), mapOf("friend" to GROUP_A),
        emptySet(), mapOf("friend" to ""))
    same(emptyMap<String, String>(), unchanged.updates, "unchanged ordinary membership")
    same(emptySet<String>(), unchanged.unresolved, "unchanged known original")

    // A row absent from a failed/incomplete read is never interpreted as the root parent.
    val missing = policy.plan(mapOf("friend" to GROUP_A), emptyMap(), emptySet(),
        mapOf("friend" to "message_fold"))
    same(emptyMap<String, String>(), missing.updates, "missing row no update")
    same(setOf("friend"), missing.unresolved, "missing row unresolved")
    same(mapOf("friend" to "message_fold"), missing.originalParents, "missing row backup preserved")

    // Successful native restoration with a failed final backup save is harmless on the next pass.
    for (protected in listOf(emptySet(), setOf("publisher"))) {
        val restored = policy.plan(emptyMap(), mapOf("publisher" to "service_officialaccounts"),
            protected, mapOf("publisher" to "officialaccounts"))
        same(emptyMap<String, String>(), restored.updates, "native restoration not repeated")
        same(emptyMap<String, String>(), restored.originalParents, "stale restored backup removed")
    }

    // Plan construction is side-effect free; persistence/update failure leaves inputs retryable.
    val assigned = linkedMapOf("publisher" to GROUP_A, "friend" to GROUP_B)
    val current = linkedMapOf("publisher" to GROUP_A, "friend" to "message_fold")
    val protected = linkedSetOf("publisher")
    val originals = linkedMapOf("publisher" to "officialaccounts", "unobserved" to "photoaccounts")
    val assignedBefore = assigned.toMap()
    val currentBefore = current.toMap()
    val protectedBefore = protected.toSet()
    val originalsBefore = originals.toMap()
    val beforeWrite = policy.plan(assigned, current, protected, originals)
    same(assignedBefore, assigned, "assignment input unchanged")
    same(currentBefore, current, "current input unchanged")
    same(protectedBefore, protected, "protection input unchanged")
    same(originalsBefore, originals, "backup input unchanged")
    same("message_fold", beforeWrite.originalParents["friend"], "prospective backup ready before write")
    same("officialaccounts", beforeWrite.originalParents["publisher"], "pending restoration still backed up")
    same("photoaccounts", beforeWrite.originalParents["unobserved"], "unobserved backup untouched")
    same(beforeWrite, policy.plan(assigned, current, protected, originals), "failed persistence is retryable")
    // Model one successful restore and one failed grouping write; only confirmed restore is retired.
    val afterPartialWrite = policy.plan(assigned,
        current + ("publisher" to "officialaccounts"), protected,
        beforeWrite.originalParents - "publisher")
    same(mapOf("friend" to GROUP_B), afterPartialWrite.updates, "partial write retries remaining chat")
    same("message_fold", afterPartialWrite.originalParents["friend"], "partial failure retains fresh backup")
    same(emptySet<String>(), afterPartialWrite.restoring, "successful restore does not repeat")
    println("ConversationGroupParentPolicy: $checks assertions passed")
}
