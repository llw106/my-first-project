package h.Hchat.hooks.items.script.agent

import android.content.Context
import h.Hchat.preferences.HchatStorage
import org.json.JSONArray
import org.json.JSONObject

private fun config(mode: String, model: String, effort: String = "default") = ScriptPluginAgentConfig(
    apiBaseUrl = "", apiPath = "", apiKey = "", model = model,
    endpointMode = mode, reasoningEffort = effort,
    promptCacheMode = ScriptPluginAgentSettings.PROMPT_CACHE_OFF
)

private fun body() = JSONObject().apply {
    put("model", "test-model")
    put("temperature", 0.2)
    put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Hello")))
}

private fun prepare(config: ScriptPluginAgentConfig, source: JSONObject = body(), stream: Boolean = true) =
    ScriptPluginAgentProviderAdapter.prepare(config, source, stream, promptCacheEnabled = false).body

private fun checkEfforts(mode: String, model: String, expected: List<String>) {
    check(ScriptPluginAgentReasoning.options(mode, model).map { it.first } == listOf("default") + expected)
}

fun main() {
    val openai = ScriptPluginAgentSettings.ENDPOINT_MODE_OPENAI
    val compatible = ScriptPluginAgentSettings.ENDPOINT_MODE_OPENAI_COMPATIBLE
    val custom = ScriptPluginAgentSettings.ENDPOINT_MODE_CUSTOM_URL
    val anthropic = ScriptPluginAgentSettings.ENDPOINT_MODE_ANTHROPIC
    val gemini = ScriptPluginAgentSettings.ENDPOINT_MODE_GEMINI
    val deepseek = ScriptPluginAgentSettings.ENDPOINT_MODE_DEEPSEEK
    val siliconflow = ScriptPluginAgentSettings.ENDPOINT_MODE_SILICONFLOW
    val openrouter = ScriptPluginAgentSettings.ENDPOINT_MODE_OPENROUTER

    for (mode in listOf(openai, compatible, custom)) {
        for (effort in listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")) {
            val source = body()
            val request = prepare(config(mode, "custom-model", effort), source)
            check(request.getString("reasoning_effort") == effort)
            check(!request.has("temperature") && !request.has("reasoning"))
            check(source.has("temperature") && !source.has("reasoning_effort"))
        }
        check(!prepare(config(mode, "custom-model")).has("reasoning_effort"))
        check(prepare(config(mode, "custom-model")).getDouble("temperature") == 0.2)
    }
    // Streaming generation and non-streaming connection/compaction share the same mapper.
    for (stream in listOf(true, false)) {
        val request = prepare(config(openai, "custom-model", "high"), body(), stream)
        check(request.getBoolean("stream") == stream)
        check(request.getString("reasoning_effort") == "high")
    }
    val router = prepare(config(openrouter, "openai/gpt-5.4", "xhigh"))
    check(router.getJSONObject("reasoning").getString("effort") == "xhigh")
    check(!router.has("reasoning_effort"))
    checkEfforts(openrouter, "anthropic/claude-opus-4.6", emptyList())
    check(runCatching { prepare(config(openrouter, "anthropic/claude-opus-4.6", "high")) }.isFailure)

    checkEfforts(anthropic, "claude-opus-4-5-20251101", listOf("low", "medium", "high"))
    checkEfforts(anthropic, "claude-sonnet-4-6", listOf("low", "medium", "high", "max"))
    checkEfforts(anthropic, "claude-opus-4-8", listOf("low", "medium", "high", "xhigh", "max"))
    checkEfforts(anthropic, "unverified-claude-model", emptyList())
    val claude = prepare(config(anthropic, "claude-opus-4-6", "high"))
    check(claude.getJSONObject("output_config").getString("effort") == "high")
    check(!claude.has("thinking") && !claude.has("temperature") && !claude.has("reasoning_effort"))
    check(!prepare(config(anthropic, "claude-opus-4-6")).has("output_config"))

    checkEfforts(gemini, "gemini-3-pro-preview", listOf("low", "high"))
    checkEfforts(gemini, "models/gemini-3-flash-preview", listOf("minimal", "low", "medium", "high"))
    checkEfforts(gemini, "gemini-2.5-pro", emptyList())
    checkEfforts(gemini, "unverified-gemini", emptyList())
    val google = prepare(config(gemini, "gemini-3-flash-preview", "medium"))
    val generation = google.getJSONObject("generationConfig")
    check(generation.getJSONObject("thinkingConfig").getString("thinkingLevel") == "medium")
    check(!generation.has("temperature") && !google.has("reasoning_effort"))
    check(runCatching { prepare(config(gemini, "gemini-3-pro-preview", "medium")) }.isFailure)

    val history = body().apply {
        put("messages", JSONArray().put(JSONObject().put("role", "assistant")
            .put("content", "Calling tool").put("reasoning_content", "preserved reasoning")
            .put("tool_calls", JSONArray().put(JSONObject().put("id", "call-1")
                .put("function", JSONObject().put("name", "test").put("arguments", "{}"))))))
    }
    for (effort in listOf("default", "low", "high", "max")) {
        val request = prepare(config(deepseek, "deepseek-v4-pro", effort), history)
        check(request.getJSONArray("messages").getJSONObject(0).getString("reasoning_content") == "preserved reasoning")
        if (effort != "default") {
            check(request.getJSONObject("thinking").getString("type") == "enabled")
            check(request.getString("reasoning_effort") == effort)
        } else check(!request.has("thinking") && !request.has("reasoning_effort"))
    }
    val disabled = prepare(config(deepseek, "deepseek-flash", "none"), history)
    check(disabled.getJSONObject("thinking").getString("type") == "disabled")
    check(!disabled.has("reasoning_effort"))
    check(!disabled.getJSONArray("messages").getJSONObject(0).has("reasoning_content"))
    checkEfforts(deepseek, "deepseek-chat", emptyList())
    val silicon = prepare(config(siliconflow, "Pro/deepseek-ai/DeepSeek-V4", "max"))
    check(silicon.getBoolean("enable_thinking") && silicon.getString("reasoning_effort") == "max")
    checkEfforts(siliconflow, "deepseek-ai/DeepSeek-V3", emptyList())

    val context = Context()
    HchatStorage.store.values.clear()
    // Old profiles without the new property retain their model and use server defaults.
    HchatStorage.store.values["script_plugin_agent_profiles_v1"] = """[{"id":"old","name":"旧配置","config":{"apiEndpoint":"","model":"old-alias"}}]"""
    val legacy = ScriptPluginAgentSettings.loadActiveProfile(context)
    check(legacy.config.model == "old-alias" && legacy.config.reasoningEffort == "default")
    ScriptPluginAgentSettings.save(context, legacy.config.copy(model = "  custom-model  ", reasoningEffort = " HIGH "))
    val first = ScriptPluginAgentSettings.loadActiveProfile(context)
    check(first.config.model == "custom-model" && first.config.reasoningEffort == "high")
    // A new profile must not inherit credentials, tools, model or preferences from the active one.
    val populated = first.config.copy(
        apiBaseUrl = "https://example.invalid/custom",
        endpointMode = custom,
        apiKey = "test-only-key",
        mcpServers = listOf(ScriptPluginAgentMcpServer(
            id = "test-mcp", name = "旧工具", endpoint = "https://example.invalid/mcp",
            authorization = "test-only-token"
        )),
        autoCompactEnabled = false,
        compactTokenThreshold = 48_000,
        webSearchEnabled = false,
        workspaceWriteApprovalMode = ScriptPluginAgentSettings.WRITE_APPROVAL_ALWAYS_ALLOW,
        promptCacheMode = ScriptPluginAgentSettings.PROMPT_CACHE_OFF
    )
    ScriptPluginAgentSettings.save(context, populated)
    val second = ScriptPluginAgentSettings.createProfile(context, "第二配置")
    val emptyConfig = ScriptPluginAgentConfig(apiBaseUrl = "", apiPath = "", apiKey = "", model = "")
    check(second.config == emptyConfig)
    check(ScriptPluginAgentSettings.loadActiveProfile(context).id == second.id)
    check(ScriptPluginAgentSettings.load(context) == emptyConfig)
    check(ScriptPluginAgentSettings.loadProfiles(context).first { it.id == first.id }.config == populated)
    val beforeDuplicate = ScriptPluginAgentSettings.loadProfiles(context)
    check(runCatching { ScriptPluginAgentSettings.createProfile(context, "第二配置") }.isFailure)
    check(ScriptPluginAgentSettings.loadProfiles(context) == beforeDuplicate)
    check(ScriptPluginAgentSettings.loadActiveProfile(context).id == second.id)
    ScriptPluginAgentSettings.save(context, emptyConfig.copy(model = "another-model", reasoningEffort = "low"))
    check(ScriptPluginAgentSettings.load(context).reasoningEffort == "low")
    ScriptPluginAgentSettings.setActiveProfile(context, first.id)
    check(ScriptPluginAgentSettings.load(context).reasoningEffort == "high")
    ScriptPluginAgentSettings.setActiveProfile(context, second.id)
    check(ScriptPluginAgentSettings.load(context).reasoningEffort == "low")
    // Switching to an unsupported native model resets the saved selection visibly to default.
    ScriptPluginAgentSettings.save(context, config(gemini, "gemini-2.5-pro", "xhigh"))
    check(ScriptPluginAgentSettings.load(context).reasoningEffort == "default")
    check(ScriptPluginAgentReasoning.normalizedEffort("garbage") == "default")
    check(ScriptPluginAgentReasoning.effectiveEffort(gemini, "gemini-3-pro-preview", "medium") == "default")
    println("Plugin Agent reasoning regressions passed (wire formats, history, defaults, profile persistence, blank profiles)")
}
