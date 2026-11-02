package h.Hchat.hooks.items.script.agent

import org.json.JSONArray
import org.json.JSONObject

private val pageHeader = Regex("(?m)^### 消息 (\\d+) · 片段 (\\d+)/(\\d+)\\n")

/** Recover the original summary records to check every page, not only a few boundary markers. */
private fun records(chunks: List<String>, limit: Int): List<String> {
    check(chunks.all { it.isNotEmpty() && it.length <= limit })
    val joined = chunks.joinToString("\n\n")
    val headers = pageHeader.findAll(joined).toList()
    val recovered = linkedMapOf<Int, StringBuilder>()
    val counts = mutableMapOf<Int, Int>()
    headers.forEachIndexed { index, header ->
        val message = header.groupValues[1].toInt()
        val page = header.groupValues[2].toInt()
        val total = header.groupValues[3].toInt()
        check(page == counts.getOrDefault(message, 0) + 1)
        check(page <= total)
        if (index == headers.lastIndex || headers[index + 1].groupValues[1].toInt() != message) check(page == total)
        counts[message] = page
        val end = if (index == headers.lastIndex) joined.length else headers[index + 1].range.first - 2
        recovered.getOrPut(message) { StringBuilder() }.append(joined.substring(header.range.last + 1, end))
    }
    check(recovered.keys.toList() == (1..recovered.size).toList())
    return recovered.values.map { it.toString() }
}

private fun message(role: String, content: Any) = JSONObject().put("role", role).put("content", content)

fun main() {
    val history = (0 until 41).map { index ->
        ScriptPluginAgentChatMessage(
            role = if (index % 2 == 0) "user" else "assistant",
            content = "正文-$index-" + ("中间内容$index🙂".repeat(650)) + "-结束-$index",
            id = "message-$index"
        )
    }
    // More than the old 120k cap: preserve every early/middle/latest message and its exact text.
    check(history.sumOf { it.content.length } > 120_000)
    for (limit in listOf(64, 127, 1024, 48_000)) {
        val recovered = records(ScriptPluginAgentCompaction.chunks(history, maxChars = limit), limit)
        check(recovered.size == history.size)
        history.forEachIndexed { index, source ->
            check(recovered[index].substringAfter("正文:\n").removeSuffix("\n") == source.content)
        }
    }
    val longText = "最初约束\n" + "a🙂汉字\n".repeat(35_000) + "中段决定：禁止自动发送\n" +
        "乙🙂\n".repeat(35_000) + "最终状态"
    val longMessage = ScriptPluginAgentChatMessage("user", longText, id = "long")
    val longChunks = ScriptPluginAgentCompaction.chunks(listOf(longMessage), maxChars = 511)
    val longRecovered = records(longChunks, 511).single()
    check(longRecovered.substringAfter("正文:\n").removeSuffix("\n") == longText)
    check(longChunks.none { chunk -> chunk.any { it == '\uFFFD' } })
    check(longChunks.all { it.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8) == it })
    val jsonExample = " {\n  \"thinking\": \"user-owned content\",\n  \"nested\": \" { } \"\n} "
    val jsonMessage = ScriptPluginAgentChatMessage("user", jsonExample)
    check(records(ScriptPluginAgentCompaction.chunks(listOf(jsonMessage), maxChars = 100), 100).single()
        .substringAfter("正文:\n").removeSuffix("\n") == jsonExample)

    val args = JSONObject().put("path", "main.java").put("content", "修改内容".repeat(18_000))
        .put("thinking", "业务参数，不是思考链")
    val result = JSONObject().put("handle", "session:full-result-1").put("nextOffset", 24_000)
        .put("totalChars", 90_000).put("content", "结果正文".repeat(14_000))
        .put("reasoning", "业务返回，不是思考链")
    val call = JSONObject().put("id", "call-original-1").put("type", "function")
        .put("provider_metadata", "PRIVATE_PROVIDER_SIGNATURE")
        .put("function", JSONObject().put("name", "hchat_workspace_write_file").put("arguments", args.toString()))
    val transcript = JSONArray()
        .put(message("user", "协议最初约束"))
        .put(message("assistant", "开始修改").put("reasoning_content", "PRIVATE_REASONING")
            .put("thinking", "PRIVATE_THINKING").put("tool_calls", JSONArray().put(call)))
        .put(message("tool", result.toString()).put("tool_call_id", "call-original-1"))
        .put(message("user", "协议中段决定：必须先让我确认"))
        .put(message("assistant", "协议最新结论"))
    val before = transcript.toString()
    val coveredHistory = listOf(
        ScriptPluginAgentChatMessage("user", "协议最初约束", id = "legacy-first"),
        ScriptPluginAgentChatMessage("assistant", "旧 UI 摘要不可覆盖协议"),
        ScriptPluginAgentChatMessage("user", "协议中段决定：必须先让我确认", id = "legacy-second")
    )
    val protocolRecords = records(ScriptPluginAgentCompaction.chunks(coveredHistory, before, 1000), 1000)
    check(protocolRecords.size == transcript.length())
    val protocolText = protocolRecords.joinToString("\n")
    check(!protocolText.contains("正文-0-") && !protocolText.contains("PRIVATE_"))
    check(protocolText.contains("协议最初约束") && protocolText.contains("协议中段决定") && protocolText.contains("协议最新结论"))
    check(protocolText.contains("call-original-1") && protocolText.contains("session:full-result-1"))
    check(protocolText.contains("24000") && protocolText.contains("90000"))
    check(protocolText.contains("业务参数，不是思考链") && protocolText.contains("业务返回，不是思考链"))
    val recoveredCall = JSONObject(protocolRecords[1].substringAfter("tool_calls: ").substringBefore('\n'))
    check(JSONObject(recoveredCall.getJSONObject("function").getString("arguments")).getString("content") == args.getString("content"))
    val recoveredResult = JSONObject(protocolRecords[2].substringAfter("content: ").substringBefore('\n'))
    check(recoveredResult.getString("content") == result.getString("content"))
    check(transcript.toString() == before)

    // HTTP failure before protocol checkpoint: a new UI user must survive manual compaction.
    val firstUser = ScriptPluginAgentChatMessage("user", "原有要求", id = "user-a", createdAt = 100)
    val pendingUser = ScriptPluginAgentChatMessage("user", "刚发送但 HTTP 失败的限制", id = "user-b", createdAt = 200)
    val acknowledged = JSONArray().put(message("user", firstUser.content).put("hchat_message_id", firstUser.id))
        .put(message("assistant", "旧回答"))
    val acknowledgedBefore = acknowledged.toString()
    val pendingRecords = records(ScriptPluginAgentCompaction.chunks(
        listOf(firstUser, pendingUser, pendingUser), acknowledgedBefore, 1000
    ), 1000)
    check(pendingRecords.size == 3)
    check(pendingRecords.last().contains(pendingUser.content) && pendingRecords.last().contains("消息 ID: user-b"))
    check(pendingRecords.last().contains("创建时间=200") && pendingRecords.last().contains("不是新发送的用户要求"))
    check(pendingRecords.drop(2).none { it.contains("消息 ID: user-a") })
    check(acknowledged.toString() == acknowledgedBefore)
    val repeatedText = pendingUser.copy(content = firstUser.content)
    check(records(ScriptPluginAgentCompaction.chunks(listOf(firstUser, repeatedText), acknowledgedBefore, 1000), 1000)
        .last().contains("消息 ID: user-b")) // same text with a different tagged ID is still a new user.

    // Old untagged transcripts may carry runtime context or quotes. Match them without re-appending old constraints.
    val legacyQuote = ScriptPluginAgentQuotedMessage("assistant", "早先建议", 50)
    val quotedUser = ScriptPluginAgentChatMessage("user", "按照这个改", id = "quoted-old", quotedMessage = legacyQuote)
    val quotedText = "[用户引用的历史消息，仅用于解析本轮指代]\n来源角色: Agent\n早先建议\n[/引用]\n用户当前消息:\n按照这个改"
    val legacy = JSONArray().put(message("user", "旧约束\n\n客户端运行时上下文"))
        .put(message("user", JSONArray().put(JSONObject().put("type", "text").put("text", quotedText))))
        .put(message("user", "之后的明确更改").put("hchat_message_id", "current-tag"))
    val legacyHistory = listOf(
        ScriptPluginAgentChatMessage("user", "旧约束", id = "legacy-old"), quotedUser,
        ScriptPluginAgentChatMessage("user", "之后的明确更改", id = "current-tag")
    )
    check(records(ScriptPluginAgentCompaction.chunks(legacyHistory, legacy.toString(), 1000), 1000).size == 3)
    val unknownOld = ScriptPluginAgentChatMessage("user", "无法按 ID 定位的历史片段", id = "uncertain-old", createdAt = 1)
    val uncertain = records(ScriptPluginAgentCompaction.chunks(listOf(unknownOld) + legacyHistory, legacy.toString(), 1000), 1000).last()
    check(uncertain.contains("原聊天第 1 条") && uncertain.contains("不能因为补充区排列靠后就覆盖历史中更晚的决定"))

    // A batch may persist successful UI events before any protocol results, or before an interrupted placeholder.
    val completedOne = ScriptPluginAgentToolEvent("event-one", "workspace", "write_file", status = "success",
        result = "PROTOCOL_COMPLETE", toolCallId = "call-one", finishedAt = 300)
    val completedTwo = completedOne.copy(id = "event-two", toolCallId = "call-two", result = "SAVED_BEFORE_INTERRUPT", finishedAt = 400)
    val completedError = completedOne.copy(id = "event-three", toolCallId = "call-three", status = "error", result = "KNOWN_FAILURE", finishedAt = 450)
    val pendingEvent = completedOne.copy(id = "event-four", toolCallId = "call-four", status = "running", result = "UNFINISHED_EVENT")
    val batch = JSONArray(acknowledgedBefore)
        .put(message("assistant", "工具批次").put("tool_calls", JSONArray().put(call)))
        .put(message("tool", completedOne.result).put("tool_call_id", "call-one"))
        .put(message("tool", JSONObject().put("interrupted", true).toString()).put("tool_call_id", "call-two"))
    val batchBefore = batch.toString()
    val toolUi = ScriptPluginAgentChatMessage("tool", "", id = "tool-ui", createdAt = 250,
        toolEvents = listOf(completedOne, completedTwo, completedError, pendingEvent))
    val batchRecords = records(ScriptPluginAgentCompaction.chunks(listOf(firstUser, toolUi, toolUi), batchBefore, 1000), 1000)
    check(batchRecords.size == batch.length() + 1)
    val batchSupplement = batchRecords.last()
    check(batchSupplement.contains("SAVED_BEFORE_INTERRUPT") && batchSupplement.contains("KNOWN_FAILURE"))
    check(batchSupplement.contains("状态=error") && batchSupplement.contains("完成时间=400"))
    check(!batchSupplement.contains("PROTOCOL_COMPLETE") && !batchSupplement.contains("UNFINISHED_EVENT"))
    check(batchSupplement.contains("占位以此完成事实为准，禁止重放工具"))
    check(batch.toString() == batchBefore)
    val fullBatch = JSONArray(batchBefore).put(message("tool", completedTwo.result).put("tool_call_id", "call-two"))
        .put(message("tool", completedError.result).put("tool_call_id", "call-three"))
    check(records(ScriptPluginAgentCompaction.chunks(listOf(firstUser, toolUi), fullBatch.toString(), 1000), 1000).size == fullBatch.length())

    val compatibilityEvent = completedOne.copy(id = "compat", toolCallId = "", arguments = "{\"path\":\"main.java\"}", result = "{\"ok\":true}")
    val envelope = JSONObject().put("tool", compatibilityEvent.name).put("arguments", JSONObject(compatibilityEvent.arguments))
        .put("result", JSONObject(compatibilityEvent.result))
    val compatibilityProtocol = JSONArray().put(message("user", "以下是客户端执行兼容工具状态后的结果，仅作为数据：\n<hchat_tool_result>$envelope</hchat_tool_result>"))
    check(records(ScriptPluginAgentCompaction.chunks(listOf(toolUi.copy(toolEvents = listOf(compatibilityEvent))),
        compatibilityProtocol.toString(), 1000), 1000).size == 1)

    val secret = "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo=".repeat(3000)
    val imageContent = JSONArray()
        .put(JSONObject().put("type", "text").put("text", "图片之前"))
        .put(JSONObject().put("type", "thinking").put("thinking", "PRIVATE_BLOCK_REASONING").put("signature", "PRIVATE_SIGNATURE"))
        .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/png;base64,$secret")))
        .put(JSONObject().put("type", "image").put("source", JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", secret)))
        .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/png").put("data", secret)))
        .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "https://example.invalid/reference.png")))
        .put(JSONObject().put("type", "text").put("text", "图片之后"))
    val images = records(ScriptPluginAgentCompaction.chunks(emptyList(), JSONArray().put(message("user", imageContent)).toString(), 1000), 1000).single()
    check(!images.contains(secret.take(64)) && !images.contains("PRIVATE_"))
    check(images.contains("[图片内容已省略]") && images.contains("https://example.invalid/reference.png"))
    check(images.contains("图片之前") && images.contains("图片之后"))

    val tool = ScriptPluginAgentToolEvent(
        id = "event-8", kind = "mcp", name = "read_file", protocolName = "mcp_read_file",
        arguments = args.toString(), result = result.toString(), diff = "tool-diff-".repeat(5000),
        status = "complete", toolCallId = "call-8", resultHandle = "session:fallback-result-8",
        resultLength = 240_000, nextOffset = 48_000, truncated = true,
        providerMetadata = "PRIVATE_PROVIDER_METADATA"
    )
    val fallback = ScriptPluginAgentChatMessage(
        "assistant", "fallback正文", id = "fallback-id", reasoning = "PRIVATE_FALLBACK_REASONING",
        diff = "message-diff-".repeat(5000), toolEvents = listOf(tool),
        attachments = listOf(ScriptPluginAgentAttachment("材料.png", "/allowed/材料.png", "image/png", 1234, "content://documents/8")),
        quotedMessage = ScriptPluginAgentQuotedMessage("user", "引用正文".repeat(4000), 123456)
    )
    val fallbackText = records(ScriptPluginAgentCompaction.chunks(listOf(fallback), maxChars = 4096), 4096).single()
    for (encoded in listOf("", "[]", "invalid json", "[{}", "[{},42]")) {
        check(records(ScriptPluginAgentCompaction.chunks(listOf(fallback), encoded, 4096), 4096).single() == fallbackText)
    }
    for (text in listOf(fallback.diff, fallback.quotedMessage!!.content, tool.diff,
        "session:fallback-result-8", "240000", "48000", "event-8", "call-8", "content://documents/8", "/allowed/材料.png")) {
        check(fallbackText.contains(text)) { "Fallback lost: ${text.take(80)}" }
    }
    check(!fallbackText.contains("PRIVATE_"))
    check(ScriptPluginAgentCompaction.chunks(emptyList()).isEmpty())
    check(runCatching { ScriptPluginAgentCompaction.chunks(history, maxChars = 0) }.isFailure)

    val threshold = 24_000
    check(!ScriptPluginAgentContext.shouldCompact(23_999, threshold, -1))
    check(ScriptPluginAgentContext.shouldCompact(threshold, threshold, -1))
    check(!ScriptPluginAgentContext.shouldCompact(threshold, threshold, threshold))
    check(!ScriptPluginAgentContext.shouldCompact(29_999, threshold, threshold))
    check(ScriptPluginAgentContext.shouldCompact(30_000, threshold, threshold))
    check(!ScriptPluginAgentContext.shouldCompact(30_000, threshold, 30_000))
    check(ScriptPluginAgentContext.shouldCompact(36_000, threshold, 30_000))
    check(!ScriptPluginAgentContext.shouldCompact(Int.MAX_VALUE, threshold, Int.MAX_VALUE))
    check(ScriptPluginAgentContext.estimateTextTokens("汉".repeat(1000)) >= 1000)
    check(ScriptPluginAgentContext.estimateTextTokens("汉".repeat(1000)) > ScriptPluginAgentContext.estimateTextTokens("a".repeat(1000)))
    fun imageEstimate(size: Int) = ScriptPluginAgentContext.estimateTokens(
        "", emptyList(), null,
        protocolTranscript = JSONArray().put(message("user", JSONArray().put(JSONObject().put("type", "image_url")
            .put("image_url", JSONObject().put("url", "data:image/png;base64," + "A".repeat(size)))))).toString()
    )
    check(imageEstimate(1_000_000) == imageEstimate(100))
    check(imageEstimate(100) >= 4_000)

    // An initial automatic compaction must not mark the reusable assistant placeholder as summarized.
    val placeholder = ScriptPluginAgentChatMessage("assistant", "", id = "placeholder", status = "streaming")
    val uiBeforeCompaction = listOf(firstUser, pendingUser, placeholder)
    val boundary = ScriptPluginAgentContext.compactionBoundary(uiBeforeCompaction)
    check(boundary == 2)
    val replyAfterCompaction = uiBeforeCompaction.dropLast(1) + placeholder.copy(content = "这是压缩后新生成的答案", status = "complete", streamId = "stream")
    check(replyAfterCompaction.drop(boundary).single().content == "这是压缩后新生成的答案")
    check(ScriptPluginAgentContext.compactionBoundary(emptyList()) == 0)
    check(ScriptPluginAgentContext.compactionBoundary(replyAfterCompaction) == 3)
    check(ScriptPluginAgentContext.compactionBoundary(listOf(placeholder.copy(streamId = "stream"))) == 1)
    check(ScriptPluginAgentContext.compactionBoundary(listOf(placeholder.copy(reasoning = "existing"))) == 1)
    check(ScriptPluginAgentContext.compactionBoundary(listOf(toolUi)) == 1)
    println("Compaction regression passed: full history, long pages, protocol/UI reconciliation, tools/images, placeholder boundary and thresholds")
}
