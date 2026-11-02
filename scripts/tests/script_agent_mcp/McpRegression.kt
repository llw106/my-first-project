package h.Hchat.hooks.items.script.agent

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private var checks = 0
private fun verify(condition: Boolean, message: String) {
    check(condition) { message }
    checks++
}

private fun failureContaining(message: String, block: () -> Unit) {
    val error = runCatching(block).exceptionOrNull()
    verify(error != null && error.message.orEmpty().contains(message), "Expected $message, got $error")
}

private fun envelope(id: Long, result: JSONObject = JSONObject()): JSONObject =
    JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)

private fun sendJson(exchange: HttpExchange, response: JSONObject) {
    val bytes = response.toString().toByteArray(Charsets.UTF_8)
    exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
    exchange.sendResponseHeaders(200, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
}

private fun sendResult(exchange: HttpExchange, request: JSONObject, result: JSONObject) =
    sendJson(exchange, envelope(request.getLong("id"), result))

private fun tools(vararg names: String): JSONObject = JSONObject().put("tools", JSONArray(names.map {
    JSONObject().put("name", it).put("inputSchema", JSONObject().put("type", "object"))
}))

private class Server(
    private val version: String = "2025-06-18",
    private val handle: (HttpExchange, JSONObject) -> Boolean = { _, _ -> false }
) : AutoCloseable {
    private val executor = Executors.newCachedThreadPool { action -> Thread(action, "mcp-fixture").apply { isDaemon = true } }
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val requests = CopyOnWriteArrayList<JSONObject>()
    val headers = CopyOnWriteArrayList<Map<String, String>>()
    private val counts = ConcurrentHashMap<String, AtomicInteger>()
    val endpoint: String get() = "http://127.0.0.1:${server.address.port}/mcp"

    init {
        server.executor = executor
        server.createContext("/mcp") { exchange ->
            try {
                val request = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
                requests += request
                headers += exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") }
                counts.computeIfAbsent(request.getString("method")) { AtomicInteger() }.incrementAndGet()
                if (!handle(exchange, request)) when (request.getString("method")) {
                    "initialize" -> {
                        exchange.responseHeaders.set("Mcp-Session-Id", "fixture-session")
                        sendResult(exchange, request, JSONObject().put("protocolVersion", version)
                            .put("capabilities", JSONObject().put("tools", JSONObject())))
                    }
                    "notifications/initialized" -> exchange.sendResponseHeaders(202, -1)
                    "tools/list" -> sendResult(exchange, request, tools("inspect"))
                    "tools/call" -> sendResult(exchange, request, JSONObject().put("content", JSONArray()))
                    else -> error("Unexpected fixture request")
                }
            } catch (_: java.io.IOException) {
                // A client completing/cancelling an SSE call intentionally closes its stream.
            } finally {
                exchange.close()
            }
        }
        server.start()
    }

    fun count(method: String): Int = counts[method]?.get() ?: 0
    fun client(cancellation: ScriptPluginAgentCancellation? = null) = ScriptPluginAgentMcpClient(endpoint, "", cancellation)
    fun config(id: String) = ScriptPluginAgentMcpServer(id, "fixture-$id", endpoint = endpoint)
    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}

private fun parserChecks() {
    fun read(text: String, sse: Boolean = true): JSONObject = ScriptPluginAgentMcpResponse.read(
        ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)), if (sse) "text/event-stream" else "application/json", 7
    )
    val response = envelope(7, JSONObject().put("value", "中文"))
    verify(read(response.toString(), false).getJSONObject("result").getString("value") == "中文", "JSON response")
    val stream = "\uFEFF: heartbeat\r\n\r\n" +
        "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\n" +
        "data: ${envelope(99)}\n\n" +
        "data: {\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ping\"}\n\n" +
        "id: event-1\r\ndata: {\"jsonrpc\":\"2.0\",\r\ndata: \"id\":7,\"result\":{\"ok\":true}}\r\n\r\n"
    verify(read(stream).getJSONObject("result").getBoolean("ok"), "SSE framing, multiline data, BOM, notifications and wrong IDs")
    val fragmented = object : ByteArrayInputStream(stream.toByteArray(Charsets.UTF_8)) {
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int = super.read(bytes, offset, minOf(length, 1))
    }
    verify(ScriptPluginAgentMcpResponse.read(fragmented, "TEXT/EVENT-STREAM; charset=utf-8", 7)
        .getJSONObject("result").getBoolean("ok"), "fragmented UTF-8 and CRLF events")
    failureContaining("完整") { read("data: $response\n") }
    failureContaining("ID") { read(envelope(8).toString(), false) }
    failureContaining("JSON-RPC") { read("data: ${response.put("jsonrpc", "1.0")}\n\n") }
    failureContaining("result/error") { read("{\"jsonrpc\":\"2.0\",\"id\":7}", false) }
    val huge = "x".repeat(ScriptPluginAgentMcpResponse.MAX_RESPONSE_BYTES)
    failureContaining("字节限制") { read(envelope(7, JSONObject().put("text", huge)).toString(), false) }
    failureContaining("字节限制") { read("data: " + envelope(7, JSONObject().put("text", huge)) + "\n\n") }
}

private fun lifecycleAndNoReplayChecks() {
    Server(version = "2025-03-26") { exchange, request ->
        if (request.getString("method") != "tools/call") false else {
            when (request.getJSONObject("params").getString("name")) {
                "tool_error" -> sendResult(exchange, request, JSONObject().put("isError", true).put("content", JSONArray()))
                "rpc_error" -> sendJson(exchange, JSONObject().put("jsonrpc", "2.0").put("id", request.getLong("id"))
                    .put("error", JSONObject().put("code", -32602).put("message", "synthetic-invalid-arguments")))
                "unavailable" -> {
                    exchange.responseHeaders.set("Retry-After", "0")
                    exchange.sendResponseHeaders(503, -1)
                }
                "disconnect" -> exchange.close()
            }
            true
        }
    }.use { server ->
        val client = server.client()
        verify(JSONObject(client.listTools()).getJSONArray("tools").length() == 1, "tools discovered")
        verify(server.requests.first().getJSONObject("params").getString("protocolVersion") == "2025-06-18", "known protocol requested")
        verify(server.headers.drop(1).all { it["mcp-protocol-version"] == "2025-03-26" }, "negotiated version used on subsequent requests")
        verify(server.headers.drop(1).all { it["mcp-session-id"] == "fixture-session" }, "session carried forward")
        verify(JSONObject(client.callTool("tool_error", JSONObject())).getBoolean("isError"), "tool error remains visible to caller")
        failureContaining("synthetic-invalid-arguments") { client.callTool("rpc_error", JSONObject()) }
        failureContaining("503") { client.callTool("unavailable", JSONObject()) }
        verify(runCatching { client.callTool("disconnect", JSONObject()) }.isFailure, "disconnect is returned as failure")
        verify(server.count("tools/call") == 4, "tool errors, HTTP Retry-After and lost responses never replay tool calls")
        verify(server.count("initialize") == 1, "one initialization per client")
    }
    Server(version = "2099-01-01").use { server ->
        failureContaining("不支持的协议版本") { server.client().listTools() }
        verify(server.count("notifications/initialized") == 0 && server.count("tools/list") == 0, "unknown protocol stops initialization")
    }
}

private fun openSse(exchange: HttpExchange) {
    exchange.responseHeaders.set("Content-Type", "text/event-stream")
    exchange.sendResponseHeaders(200, 0)
}

private fun streamingAndCancellationChecks() {
    val release = CountDownLatch(1)
    Server { exchange, request ->
        if (request.getString("method") != "tools/list") false else {
            openSse(exchange)
            val id = request.getLong("id")
            exchange.responseBody.write((": alive\n\ndata: ${envelope(id + 50)}\n\n" +
                "data: {\"jsonrpc\":\"2.0\",\"id\":$id,\n" +
                "data: \"result\":${tools("streamed")}}\n\n").toByteArray())
            exchange.responseBody.flush()
            release.await(10, TimeUnit.SECONDS)
            true
        }
    }.use { server ->
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<String> { server.client().listTools() }
            val result = JSONObject(future.get(3, TimeUnit.SECONDS))
            verify(release.count == 1L && result.getJSONArray("tools").getJSONObject(0).getString("name") == "streamed",
                "matching SSE result returns before server closes connection")
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
    val streaming = CountDownLatch(1)
    val cancelledRelease = CountDownLatch(1)
    Server { exchange, request ->
        if (request.getString("method") != "tools/list") false else {
            openSse(exchange)
            exchange.responseBody.write(": alive\n\n".toByteArray())
            exchange.responseBody.flush()
            streaming.countDown()
            cancelledRelease.await(10, TimeUnit.SECONDS)
            true
        }
    }.use { first ->
        Server().use { second ->
            val cancellation = ScriptPluginAgentCancellation()
            val clients = ScriptPluginAgentMcpClients(listOf(first.config("a"), second.config("b")), cancellation)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val future = executor.submit<Throwable?> { runCatching { clients.listTools() }.exceptionOrNull() }
                verify(streaming.await(3, TimeUnit.SECONDS), "SSE request started")
                cancellation.cancel()
                verify(future.get(3, TimeUnit.SECONDS) is java.util.concurrent.CancellationException, "cancel propagates through catalog aggregation")
                verify(second.count("initialize") == 0, "cancel prevents connecting remaining servers")
            } finally {
                cancelledRelease.countDown()
                executor.shutdownNow()
            }
        }
    }
}

private fun catalogChecks() {
    Server { exchange, _ -> exchange.sendResponseHeaders(503, -1); true }.use { first ->
        Server { exchange, _ -> exchange.sendResponseHeaders(502, -1); true }.use { second ->
            val result = JSONObject(ScriptPluginAgentMcpClients(listOf(first.config("a"), second.config("b")), ScriptPluginAgentCancellation()).listTools())
            verify(result.getJSONArray("tools").length() == 0, "all servers failing leaves an empty optional catalog")
            val servers = result.getJSONArray("servers")
            verify(servers.length() == 2 && (0 until 2).all { servers.getJSONObject(it).has("error") }, "every server failure remains visible")
        }
        Server().use { good ->
            val clients = ScriptPluginAgentMcpClients(listOf(first.config("a"), good.config("b")), ScriptPluginAgentCancellation())
            val result = JSONObject(clients.listTools())
            verify(result.getJSONArray("tools").length() == 1, "healthy server survives another failure")
            clients.callTool(result.getJSONArray("tools").getJSONObject(0).getString("name"), JSONObject())
            verify(good.count("tools/call") == 1, "namespaced tool routes to healthy server")
        }
    }
    val page = AtomicInteger()
    Server { exchange, request ->
        if (request.getString("method") != "tools/list") false else {
            val index = page.incrementAndGet()
            sendResult(exchange, request, tools("page-$index").apply { if (index == 1) put("nextCursor", "next") })
            true
        }
    }.use { server ->
        verify(JSONObject(server.client().listTools()).getJSONArray("tools").length() == 2, "all pages are read")
        verify(server.requests.last().getJSONObject("params").getString("cursor") == "next", "cursor sent to next page")
    }
    Server { exchange, request ->
        if (request.getString("method") != "tools/list") false else {
            sendResult(exchange, request, tools("loop").put("nextCursor", "repeated")); true
        }
    }.use { server ->
        failureContaining("循环 cursor") { server.client().listTools() }
        verify(server.count("tools/list") == 2, "cursor cycle fails without silently returning partial tools")
    }
    Server { exchange, request ->
        if (request.getString("method") != "tools/list") false else {
            sendResult(exchange, request, tools(*Array(2001) { "tool-$it" })); true
        }
    }.use { server -> failureContaining("2000") { server.client().listTools() } }
    val pages = AtomicInteger()
    Server { exchange, request ->
        if (request.getString("method") != "tools/list") false else {
            sendResult(exchange, request, tools().put("nextCursor", "page-${pages.incrementAndGet()}")); true
        }
    }.use { server ->
        failureContaining("100 页") { server.client().listTools() }
        verify(server.count("tools/list") == 100, "unique cursor sequence is bounded")
    }
    Server { exchange, request ->
        if (request.getString("method") != "tools/list") false else {
            val result = tools("large").put("nextCursor", "next")
            result.getJSONArray("tools").getJSONObject(0).put("description", "x".repeat(ScriptPluginAgentMcpResponse.MAX_RESPONSE_BYTES / 2))
            sendResult(exchange, request, result); true
        }
    }.use { server -> failureContaining("目录超过大小限制") { server.client().listTools() } }
    Server { exchange, request ->
        if (request.getString("method") != "tools/list") false else {
            sendResult(exchange, request, JSONObject().put("padding", "x".repeat(ScriptPluginAgentMcpResponse.MAX_RESPONSE_BYTES))); true
        }
    }.use { server -> failureContaining("字节限制") { server.client().listTools() } }
}

private fun totalTimeoutCheck() {
    println("Checking total MCP deadline with continuous heartbeats (about 90 seconds)...")
    Server { exchange, request ->
        if (request.getString("method") != "tools/list") false else {
            openSse(exchange)
            while (true) {
                exchange.responseBody.write(": heartbeat\n\n".toByteArray())
                exchange.responseBody.flush()
                Thread.sleep(50)
            }
            @Suppress("UNREACHABLE_CODE")
            true
        }
    }.use { server ->
        val started = System.nanoTime()
        val error = runCatching { server.client().listTools() }.exceptionOrNull()
        val seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)
        verify(error is java.io.IOException && seconds in 85..100, "total deadline aborts despite healthy heartbeats: $seconds seconds, $error")
        verify(server.count("tools/list") == 1, "timeout does not replay request")
    }
}

fun main() {
    parserChecks()
    lifecycleAndNoReplayChecks()
    streamingAndCancellationChecks()
    catalogChecks()
    if (System.getProperty("hchat.mcp.timeoutTest") == "true") totalTimeoutCheck()
    println("MCP regressions: $checks assertions passed")
}
