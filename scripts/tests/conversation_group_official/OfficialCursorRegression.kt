package h.Hchat.hooks.items.conversationgroup

import android.database.Cursor
import android.database.MatrixCursor

private var checks = 0
private val columns = arrayOf("username", "flag", "conversationTime", "content", "blob", "score", "nullable")

private fun expect(value: Boolean, message: String) {
    checks++
    check(value) { message }
}

private fun cursor(vararg rows: Array<Any?>): MatrixCursor = MatrixCursor(columns).apply {
    rows.forEach(::addRow)
}

private fun row(user: String, flag: Long, time: Long, content: String = user): Array<Any?> =
    arrayOf(user, flag, time, content, byteArrayOf(1, 2, 3), 1.25, null)

private fun users(cursor: Cursor): List<String?> {
    val previous = cursor.position
    cursor.moveToPosition(-1)
    val values = arrayListOf<String?>()
    while (cursor.moveToNext()) values.add(cursor.getString(cursor.getColumnIndex("username")))
    cursor.moveToPosition(previous)
    return values
}

private fun expectFailure(message: String, action: () -> Unit) {
    checks++
    try {
        action()
    } catch (_: IllegalArgumentException) {
        return
    } catch (_: IllegalStateException) {
        return
    }
    error(message)
}

private fun emptyAndReorderedColumns() {
    val base = cursor()
    val extra = MatrixCursor(arrayOf("content", "conversationTime", "username", "nullable", "flag", "score", "blob"))
    extra.addRow(arrayOf("fresh article", 900L, "gh_news", null, 42L, 2.75, byteArrayOf(7, 8)))
    extra.moveToPosition(0)
    val result = ConversationGroupOfficialCursor.merge(base, listOf(extra))
    expect(result.columnNames.contentEquals(columns), "base schema and column order must survive")
    expect(result.count == 1 && result.position == -1, "empty native result must accept official rows")
    expect(base.isClosed && extra.isClosed, "successful merge owns both input cursors")
    result.moveToNext()
    expect(result.getString(0) == "gh_news" && result.getLong(1) == 42L, "reindex identity and flag")
    expect(result.getLong(2) == 900L && result.getString(3) == "fresh article", "reindex time and content")
    expect(result.getBlob(4)!!.contentEquals(byteArrayOf(7, 8)), "blob values survive projection")
    expect(result.getDouble(5) == 2.75 && result.getType(6) == Cursor.FIELD_TYPE_NULL, "float and null survive")
    result.close()
    val emptyBase = cursor()
    val emptyExtra = cursor()
    val empty = ConversationGroupOfficialCursor.merge(emptyBase, listOf(emptyExtra))
    expect(empty.count == 0 && empty.columnNames.contentEquals(columns), "empty merge retains schema")
    expect(emptyBase.isClosed && emptyExtra.isClosed, "empty merge closes sources")
    empty.close()
}

private fun replacementAndOrdering() {
    val base = cursor(
        row("ordinary", 3L, 10L),
        row("gh_news", Long.MAX_VALUE, 1L, "stale"),
        row("tie_first", 2L, 5L),
        row("tie_second", 2L, 5L)
    )
    base.moveToPosition(2)
    val extra = cursor(
        row("gh_news", 3L, 40L, "latest"),
        row("pinned", Long.MAX_VALUE, 0L),
        row("new_same_flag", 3L, 30L),
        row("negative", Long.MIN_VALUE, 99L)
    )
    val finalExtra = cursor(row("gh_news", 3L, 50L, "newest query"))
    val result = ConversationGroupOfficialCursor.merge(base, listOf(extra, finalExtra))
    expect(users(result) == listOf("pinned", "gh_news", "new_same_flag", "ordinary", "tie_first", "tie_second", "negative"),
        "native flag DESC, time DESC, stable ties; avoid subtraction overflow")
    expect(result.count == 7, "same username appears once")
    result.moveToPosition(1)
    expect(result.getString(3) == "newest query" && result.getLong(2) == 50L, "latest extra replaces native and prior extras")
    expect(result.getLong(1) == 3L, "replacement must not retain stale native flag")
    expect(base.closeCount == 1 && extra.closeCount == 1 && finalExtra.closeCount == 1, "each source is closed once")
    result.close()
    val onlyBase = cursor(row("a", 0, 1))
    val cloned = ConversationGroupOfficialCursor.merge(onlyBase, emptyList())
    expect(users(cloned) == listOf("a") && onlyBase.isClosed, "merge without extras is still owned copy")
    cloned.close()
    val aliased = cursor(row("same", 0, 1))
    val once = ConversationGroupOfficialCursor.merge(aliased, listOf(aliased, aliased))
    expect(aliased.closeCount == 1 && once.count == 1, "aliased inputs close once and dedupe")
    once.close()
}

private fun nativeLimit() {
    val base = cursor(row("stale", 100, 10), row("friend", 3, 20))
    val extra = cursor(row("stale", 2, 30), row("pinned", Long.MAX_VALUE, 0), row("latest", 3, 40))
    val limited = ConversationGroupOfficialCursor.merge(base, listOf(extra), 2)
    expect(users(limited) == listOf("pinned", "latest"), "limit follows replacement and native ordering")
    expect(limited.count == 2 && base.isClosed && extra.isClosed, "limited merge preserves source ownership")
    limited.close()
    val unboundedBase = cursor(row("a", 1, 1))
    val unboundedExtra = cursor(row("b", 2, 2))
    val unbounded = ConversationGroupOfficialCursor.merge(unboundedBase, listOf(unboundedExtra), -1)
    expect(unbounded.count == 2, "nonpositive limit remains unbounded")
    unbounded.close()
    val small = ConversationGroupOfficialCursor.merge(cursor(row("a", 1, 1)), emptyList(), 100)
    expect(small.count == 1, "large limit does not add rows")
    small.close()
}

private fun mergeFailureOwnership() {
    val base = cursor(row("a", 0, 1), row("b", 0, 2))
    base.moveToPosition(1)
    val goodExtra = cursor(row("c", 0, 3))
    val missingColumn = MatrixCursor(arrayOf("username", "flag", "conversationTime"))
    missingColumn.addRow(arrayOf("d", 1L, 4L))
    expectFailure("missing source column must fail instead of shifting values") {
        ConversationGroupOfficialCursor.merge(base, listOf(goodExtra, missingColumn))
    }
    expect(!base.isClosed && base.position == 1, "failed merge restores native cursor and keeps it open")
    expect(base.getString(0) == "b", "original native row is still readable after failure")
    expect(goodExtra.isClosed && missingColumn.isClosed, "failed merge closes all extras")
    base.close()

    val readFailure = object : MatrixCursor(columns) {
        override fun getType(index: Int): Int {
            if (position == 1) error("simulated read failure")
            return super.getType(index)
        }
    }.apply {
        addRow(row("a", 0, 1))
        addRow(row("b", 0, 2))
        moveToPosition(0)
    }
    val unreadExtra = cursor(row("x", 0, 3))
    expectFailure("base read failure should propagate") {
        ConversationGroupOfficialCursor.merge(readFailure, listOf(readFailure, unreadExtra))
    }
    expect(!readFailure.isClosed && readFailure.position == 0, "aliased base must stay open on failure")
    expect(unreadExtra.closeCount == 1, "unread extras are also released on failure")
    readFailure.close()

    val missingIdentity = MatrixCursor(arrayOf("flag", "conversationTime"))
    val pendingExtra = cursor()
    expectFailure("missing username must fail") {
        ConversationGroupOfficialCursor.merge(missingIdentity, listOf(pendingExtra))
    }
    expect(!missingIdentity.isClosed && missingIdentity.position == -1 && pendingExtra.isClosed,
        "schema failure follows the same ownership contract")
    missingIdentity.close()
}

private fun homepageFilter() {
    val base = cursor(row("friend", 9, 9), row("gh_grouped", 100, 100), row("group", 0, 0))
    base.moveToPosition(1)
    val result = ConversationGroupOfficialCursor.filter(base, setOf("gh_grouped"))
    expect(users(result) == listOf("friend", "group"), "homepage filter preserves native order")
    expect(result.columnNames.contentEquals(columns), "homepage filter retains all columns")
    expect(result.position == -1 && base.closeCount == 1, "filtered copy closes source and starts before first")
    result.moveToPosition(0)
    expect(result.getBlob(4)!!.contentEquals(byteArrayOf(1, 2, 3)), "filter preserves blobs")
    expect(result.getDouble(5) == 1.25 && result.getType(6) == Cursor.FIELD_TYPE_NULL, "filter preserves float and null")
    result.close()

    val unmatched = cursor(row("friend", 1, 1), row("group", 0, 0))
    unmatched.moveToPosition(1)
    val same = ConversationGroupOfficialCursor.filter(unmatched, setOf("absent"))
    expect(same === unmatched && !unmatched.isClosed, "no matching IDs returns native cursor")
    expect(same.position == 1 && same.getString(0) == "group", "no match restores original cursor position")
    val unchanged = ConversationGroupOfficialCursor.filter(unmatched, emptySet())
    expect(unchanged === unmatched && unchanged.position == 1, "empty hidden set is side-effect free")
    unmatched.moveToPosition(unmatched.count)
    val afterLast = ConversationGroupOfficialCursor.filter(unmatched, setOf("absent"))
    expect(afterLast.position == afterLast.count, "no match also restores after-last position")
    unmatched.close()

    val all = cursor(row("gh_grouped", 0, 1), row("gh_grouped", 0, 2))
    val none = ConversationGroupOfficialCursor.filter(all, setOf("gh_grouped"))
    expect(none.count == 0 && all.isClosed, "all matching rows, including duplicates, are removed")
    none.close()
    val empty = cursor()
    val emptyResult = ConversationGroupOfficialCursor.filter(empty, setOf("x"))
    expect(emptyResult === empty && empty.position == -1 && !empty.isClosed, "empty input is preserved")
    empty.close()
}

private fun filterFailureOwnership() {
    val base = object : MatrixCursor(columns) {
        override fun getType(index: Int): Int {
            if (position == 2) error("simulated read failure")
            return super.getType(index)
        }
    }.apply {
        addRow(row("hidden", 3, 3))
        addRow(row("keep", 2, 2))
        addRow(row("fails", 1, 1))
        moveToPosition(1)
    }
    expectFailure("partial filtering failure must propagate") {
        ConversationGroupOfficialCursor.filter(base, setOf("hidden"))
    }
    expect(!base.isClosed && base.position == 1, "failed filtering preserves source ownership and position")
    expect(base.getString(0) == "keep" && base.count == 3, "failed filtering does not mutate rows")
    base.close()
}

fun main() {
    emptyAndReorderedColumns()
    replacementAndOrdering()
    nativeLimit()
    mergeFailureOwnership()
    homepageFilter()
    filterFailureOwnership()
    println("ConversationGroupOfficialCursor: $checks checks passed")
}
