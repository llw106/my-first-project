package h.Hchat.hooks.items.conversationgroup

import h.Hchat.hooks.api.runtime.WeChatDatabaseApi
import org.json.JSONObject

private const val ACCOUNT = "account-a"
private const val GROUP_A = "wxid_hchat_group_a"
private const val GROUP_B = "wxid_hchat_group_b"
private const val KEY = "original_parent_refs"
private var checks = 0
private fun same(expected: Any?, actual: Any?, label: String) {
    check(expected == actual) { "$label: expected <$expected>, got <$actual>" }
    checks++
}
private fun fails(label: String, action: () -> Unit) {
    var failure: Throwable? = null
    try { action() } catch (caught: Throwable) { failure = caught }
    check(failure != null) { "$label: expected failure" }
    checks++
}
private fun originals(vararg entries: Pair<String, String>): String = JSONObject().apply {
    put(ACCOUNT, JSONObject().apply { entries.forEach { (talker, parent) -> put(talker, parent) } })
}.toString()

fun main() {
    val runtime = ParentRuntimeUnderTest
    val policy = ConversationGroupParentPolicy
    ConversationGroupStore.account = ACCOUNT

    // First commit fails after changing memory. No database mutation is authorized by memory equality.
    val events = arrayListOf<String>()
    val prefs = MemoryPreferences(events = events)
    val database = WeChatDatabaseApi(mapOf("friend" to "message_fold"), events)
    val plan = policy.plan(mapOf("friend" to GROUP_A), database.parents, emptySet(), emptyMap())
    prefs.failCommits.add(1)
    fails("failed backup commit aborts updates") { runtime.applyParentPlan(database, prefs, ACCOUNT, plan) }
    same(0, database.updateCalls, "failure never writes parent")
    same("message_fold", database.parents["friend"], "failed backup keeps native parent")
    same(mapOf("friend" to "message_fold"), runtime.loadOriginalParentRefs(prefs, ACCOUNT), "failed commit changed memory")
    same("{}", prefs.disk[KEY], "failed commit did not change disk")
    val retry = policy.plan(mapOf("friend" to GROUP_A), database.parents, emptySet(),
        runtime.loadOriginalParentRefs(prefs, ACCOUNT))
    same(setOf("friend"), runtime.applyParentPlan(database, prefs, ACCOUNT, retry), "retry succeeds")
    same(2, prefs.commitCount, "identical memory still recommits before parent mutation")
    same(listOf("commit:false", "commit:true", "update:friend:$GROUP_A"), events, "durable ordering on retry")
    same("message_fold", JSONObject(prefs.disk.getValue(KEY)).getJSONObject(ACCOUNT).getString("friend"),
        "retry persisted restoration information")
    same(GROUP_A, database.parents["friend"], "retry changes parent only after successful commit")

    // Failed native restoration retains the original mapping for a later retry.
    val restorePrefs = MemoryPreferences(originals("publisher" to "officialaccounts"))
    val restoreDb = WeChatDatabaseApi(mapOf("publisher" to GROUP_A), restorePrefs.events)
    val restorePlan = policy.plan(mapOf("publisher" to GROUP_B), restoreDb.parents, setOf("publisher"),
        runtime.loadOriginalParentRefs(restorePrefs, ACCOUNT))
    restoreDb.failTalkers.add("publisher")
    same(emptySet<String>(), runtime.applyParentPlan(restoreDb, restorePrefs, ACCOUNT, restorePlan),
        "failed restoration reports no successful talkers")
    same(GROUP_A, restoreDb.parents["publisher"], "failed restoration retains existing parent")
    same(mapOf("publisher" to "officialaccounts"), runtime.loadOriginalParentRefs(restorePrefs, ACCOUNT),
        "failed restoration keeps recovery mapping")
    same(restorePrefs.memory, restorePrefs.disk, "failed restoration backup remains persisted")
    restoreDb.failTalkers.clear()
    same(setOf("publisher"), runtime.applyParentPlan(restoreDb, restorePrefs, ACCOUNT, restorePlan),
        "restoration retries")
    same("officialaccounts", restoreDb.parents["publisher"], "restoration recovers saved native parent")
    same(emptyMap<String, String>(), runtime.loadOriginalParentRefs(restorePrefs, ACCOUNT),
        "only successful restoration removes backup")

    // Explicit saved root is valid; absence must not silently become the root.
    val rootPrefs = MemoryPreferences(originals("publisher" to ""))
    val rootDb = WeChatDatabaseApi(mapOf("publisher" to GROUP_A), rootPrefs.events)
    val rootPlan = policy.plan(emptyMap(), rootDb.parents, setOf("publisher"),
        runtime.loadOriginalParentRefs(rootPrefs, ACCOUNT))
    same(setOf("publisher"), runtime.applyParentPlan(rootDb, rootPrefs, ACCOUNT, rootPlan), "known empty restores")
    same("", rootDb.parents["publisher"], "explicit empty root recovered")
    val unknownPrefs = MemoryPreferences()
    val unknownDb = WeChatDatabaseApi(mapOf("publisher" to GROUP_A))
    val unknownPlan = policy.plan(emptyMap(), unknownDb.parents, setOf("publisher"), emptyMap())
    same(emptySet<String>(), runtime.applyParentPlan(unknownDb, unknownPrefs, ACCOUNT, unknownPlan),
        "unknown restoration has no successes")
    same(0, unknownDb.updateCalls, "unknown original never writes root")
    same(GROUP_A, unknownDb.parents["publisher"], "unknown original stays grouped")

    // Malformed JSON, wrong account shape and non-string mappings stop at loading.
    for (raw in listOf("{", "[]", "null", "{\"account-a\":false}",
        "{\"account-a\":{\"publisher\":null}}", "{\"account-a\":{\"publisher\":3}}",
        "{\"account-a\":{\"publisher\":{}}}")) {
        val broken = MemoryPreferences(raw)
        fails("malformed saved mapping $raw") { runtime.loadOriginalParentRefs(broken, ACCOUNT) }
        same(raw, broken.memory[KEY], "invalid mapping not replaced in memory")
        same(raw, broken.disk[KEY], "invalid mapping not replaced on disk")
        same(0, broken.commitCount, "invalid load never saves")
    }
    val malformedSave = MemoryPreferences("{")
    fails("save never replaces unparseable root") {
        runtime.saveOriginalParentRefs(malformedSave, ACCOUNT, mapOf("friend" to ""), true)
    }
    same("{", malformedSave.memory[KEY], "save failure preserves invalid root")
    same(0, malformedSave.commitCount, "save parse error avoids editor")
    val otherAccount = MemoryPreferences("{\"other\":{\"untouched\":\"photoaccounts\"}}")
    same(emptyMap<String, String>(), runtime.loadOriginalParentRefs(otherAccount, ACCOUNT), "missing account starts empty")
    runtime.saveOriginalParentRefs(otherAccount, ACCOUNT, mapOf("friend" to ""))
    same("photoaccounts", JSONObject(otherAccount.disk.getValue(KEY)).getJSONObject("other").getString("untouched"),
        "save preserves another account")

    // An already changed account must abort even the backup write.
    val switchedPrefs = MemoryPreferences()
    val switchedDb = WeChatDatabaseApi(mapOf("friend" to "message_fold"))
    ConversationGroupStore.account = "account-b"
    fails("account switched before apply") { runtime.applyParentPlan(switchedDb, switchedPrefs, ACCOUNT, plan) }
    same(0, switchedPrefs.commitCount, "account mismatch never persists")
    same(0, switchedDb.updateCalls, "account mismatch never mutates")
    ConversationGroupStore.account = ACCOUNT
    switchedPrefs.afterCommit = { ConversationGroupStore.account = "account-b" }
    fails("account switched during commit") { runtime.applyParentPlan(switchedDb, switchedPrefs, ACCOUNT, plan) }
    same(1, switchedPrefs.commitCount, "backup for captured account remains recoverable")
    same(0, switchedDb.updateCalls, "account rechecked after commit")
    ConversationGroupStore.account = ACCOUNT

    // Each batch rechecks identity, retaining every recovery entry if a later batch aborts.
    val batchPrefs = MemoryPreferences()
    val batchDb = WeChatDatabaseApi(mapOf("one" to "", "two" to "message_fold"), batchPrefs.events)
    val batchPlan = policy.plan(mapOf("one" to GROUP_A, "two" to GROUP_B), batchDb.parents,
        emptySet(), emptyMap())
    batchDb.afterUpdate = { ConversationGroupStore.account = "account-b" }
    fails("account changed between native batches") { runtime.applyParentPlan(batchDb, batchPrefs, ACCOUNT, batchPlan) }
    same(1, batchDb.updateCalls, "later batch not executed after account switch")
    same("message_fold", batchDb.parents["two"], "second chat untouched after account switch")
    ConversationGroupStore.account = ACCOUNT
    same(mapOf("one" to "", "two" to "message_fold"), runtime.loadOriginalParentRefs(batchPrefs, ACCOUNT),
        "interrupted batches retain all backups")

    // A failed cleanup commit after successful restoration leaves a safe, retryable disk backup.
    val cleanupPrefs = MemoryPreferences(originals("publisher" to "officialaccounts"))
    cleanupPrefs.failCommits.add(2)
    val cleanupDb = WeChatDatabaseApi(mapOf("publisher" to GROUP_A), cleanupPrefs.events)
    val cleanupPlan = policy.plan(emptyMap(), cleanupDb.parents, setOf("publisher"),
        runtime.loadOriginalParentRefs(cleanupPrefs, ACCOUNT))
    fails("restoration cleanup commit failure") { runtime.applyParentPlan(cleanupDb, cleanupPrefs, ACCOUNT, cleanupPlan) }
    same("officialaccounts", cleanupDb.parents["publisher"], "successful restore stays native after cleanup failure")
    same("officialaccounts", JSONObject(cleanupPrefs.disk.getValue(KEY)).getJSONObject(ACCOUNT).getString("publisher"),
        "cleanup failure retains durable original")
    val afterRestart = MemoryPreferences(cleanupPrefs.disk.getValue(KEY))
    val recoveredPlan = policy.plan(emptyMap(), cleanupDb.parents, setOf("publisher"),
        runtime.loadOriginalParentRefs(afterRestart, ACCOUNT))
    same(emptyMap<String, String>(), recoveredPlan.updates, "restart never rewrites already native parent")
    runtime.applyParentPlan(cleanupDb, afterRestart, ACCOUNT, recoveredPlan)
    same(emptyMap<String, String>(), runtime.loadOriginalParentRefs(afterRestart, ACCOUNT), "restart retires stale backup")
    same(1, cleanupDb.updateCalls, "cleanup retry does not repeat native write")

    println("ConversationGroupParentRuntime: $checks assertions passed")
}
