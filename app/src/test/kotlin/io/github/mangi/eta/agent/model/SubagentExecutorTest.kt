package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.data.model.ReasoningEffort
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SubagentExecutorTest {

    @Test
    fun mainModelDelegatesToWorkerSubagentAndVerifiesExecution() {
        val events = mutableListOf<AgentEvent>()
        val executedTools = mutableListOf<String>()

        val mainProvider = ScriptedProvider(
            // Main Round 1: Plan and call delegate_task
            assistant(
                finishReason = "tool_calls",
                toolCalls = listOf(
                    toolCall(
                        id = "call_delegate",
                        name = "delegate_task",
                        arguments = """{"task":"在当前列表点赞第一项并向下滚动","max_steps":5}""",
                    ),
                ),
            ),
            // Main Round 2: Verify subagent outcome and answer user
            assistant(
                content = "已为您完成点赞和滚动。",
                finishReason = "stop",
            ),
        )

        val workerProvider = ScriptedProvider(
            // Worker Round 1: Tap element
            assistant(
                finishReason = "tool_calls",
                toolCalls = listOf(
                    toolCall(
                        id = "worker_call_tap",
                        name = "tap_element",
                        arguments = """{"index":0,"observation_id":"obs_1"}""",
                    ),
                ),
            ),
            // Worker Round 2: Scroll
            assistant(
                finishReason = "tool_calls",
                toolCalls = listOf(
                    toolCall(
                        id = "worker_call_scroll",
                        name = "scroll",
                        arguments = """{"direction":"down"}""",
                    ),
                ),
            ),
            // Worker Round 3: Summarize what was done
            assistant(
                content = "已点赞第一项并向下滚动一屏。",
                finishReason = "stop",
            ),
        )

        val mainConfig = modelConfig(
            model = "gemini-4-argon",
            subagentModel = "gemini-3.8-flash-tiered",
            subagentEnabled = true,
        )

        val toolExecutor = AgentModelClient.ToolExecutor { call ->
            executedTools += call.name
            when (call.name) {
                "tap_element" -> AgentModelClient.ToolResult(
                    content = JSONObject()
                        .put("ok", true)
                        .put("after", JSONObject().put("observation_id", "obs_2"))
                        .toString(),
                )
                "scroll" -> AgentModelClient.ToolResult(
                    content = JSONObject()
                        .put("ok", true)
                        .put("after", JSONObject().put("observation_id", "obs_3"))
                        .toString(),
                )
                else -> AgentModelClient.ToolResult("""{"ok":true}""")
            }
        }

        val result = AgentModelClient.complete(
            config = mainConfig,
            prompt = "帮我点赞并滚动",
            toolExecutor = toolExecutor,
            providerResolver = { config ->
                if (config.model == "gemini-4-argon") mainProvider else workerProvider
            },
            onEvent = events::add,
        )

        // Verify main model result
        assertEquals("已为您完成点赞和滚动。", result.content)
        assertEquals(listOf("tap_element", "scroll"), executedTools)

        // Verify request sent to main model in round 2 contains structured delegate_task result
        val mainSecondRequest = mainProvider.requests[1]
        val toolResultJson = JSONObject(
            mainSecondRequest.getJSONObject(mainSecondRequest.length() - 1).getString("content"),
        )
        assertTrue(toolResultJson.getBoolean("ok"))
        assertEquals("gemini-3.8-flash-tiered", toolResultJson.getString("worker_model"))
        assertEquals(2, toolResultJson.getInt("steps_taken"))
        assertEquals(5, toolResultJson.getInt("max_steps"))
        assertFalse(toolResultJson.getBoolean("limit_reached"))
        assertEquals("已点赞第一项并向下滚动一屏。", toolResultJson.getString("worker_report"))
        assertEquals("obs_3", toolResultJson.getJSONObject("latest_observation").getString("observation_id"))

        val traceArray = toolResultJson.getJSONArray("actions_trace")
        assertEquals(2, traceArray.length())
        assertEquals("tap_element", traceArray.getJSONObject(0).getString("tool"))
        assertTrue(traceArray.getJSONObject(0).getBoolean("ok"))
        assertEquals("scroll", traceArray.getJSONObject(1).getString("tool"))
        assertTrue(traceArray.getJSONObject(1).getBoolean("ok"))

        // Verify forwarded child events have "[子代理]" prefix and correct IDs
        val toolStartedEvents = events.filterIsInstance<AgentEvent.ToolStarted>()
        val subagentTapStarted = toolStartedEvents.find { it.name == "tap_element" }
        assertTrue(subagentTapStarted != null)
        assertTrue(subagentTapStarted!!.argsPreview.startsWith("[子代理]"))
        assertTrue(subagentTapStarted.toolCallId.startsWith("call_delegate-sub-"))

        val toolFinishedEvents = events.filterIsInstance<AgentEvent.ToolFinished>()
        val subagentTapFinished = toolFinishedEvents.find { it.name == "tap_element" }
        assertTrue(subagentTapFinished != null)
        assertEquals(subagentTapStarted.toolCallId, subagentTapFinished!!.toolCallId)
    }

    @Test
    fun workerSubagentCannotHaveDelegateTaskToolAndRejectsRecursiveDelegation() {
        val mainConfig = modelConfig(
            model = "gemini-4-argon",
            subagentModel = "gemini-3.8-flash-tiered",
            subagentEnabled = true,
        )
        val workerConfig = mainConfig.toSubagentConfig()

        // 1. Worker config has subagentEnabled = false and correct model
        assertFalse(workerConfig.subagentEnabled)
        assertEquals("gemini-3.8-flash-tiered", workerConfig.model)
        assertTrue(workerConfig.thinkingEnabled)
        assertEquals(ReasoningEffort.DEFAULT, workerConfig.reasoningEffort)

        // 2. Worker complete() does not expose delegate_task in tools catalog
        var publishedToolsString = ""
        val workerProvider = ScriptedProvider(
            listOf(
                { request: ProviderRequest, _: AgentRunController ->
                    publishedToolsString = request.tools.toString()
                    assistant(content = "子代理已完成", finishReason = "stop")
                },
            ),
        )

        AgentModelClient.complete(
            config = workerConfig,
            prompt = "执行子任务",
            toolExecutor = AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("""{"ok":true}""") },
            provider = workerProvider,
        )
        assertFalse(
            "Worker subagent catalog must NOT contain delegate_task",
            publishedToolsString.contains(AgentSubagentToolCatalog.TOOL_NAME),
        )

        // 3. Direct call to delegate_task inside worker tool executor is rejected with RECURSIVE_SUBAGENT_FORBIDDEN
        val executor = SubagentExecutor(
            parentConfig = mainConfig,
            parentToolExecutor = AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("""{"ok":true}""") },
        )
        val workerToolExecutor = executor.createWorkerToolExecutor(
            maxSteps = 5,
            stepsTrace = mutableListOf(),
            onObservationCaptured = {},
            onLimitTriggered = {},
            getStepsTaken = { 0 },
            setStepsTaken = {},
        )
        val directCallResult = workerToolExecutor.execute(
            AgentModelClient.ToolCall(
                id = "direct_recursive_call",
                name = "delegate_task",
                argumentsJson = """{"task":"nested"}""",
            ),
        )
        val directResultJson = JSONObject(directCallResult.content)
        assertFalse(directResultJson.getBoolean("ok"))
        assertEquals("RECURSIVE_SUBAGENT_FORBIDDEN", directResultJson.getString("code"))
    }

    @Test
    fun workerSubagentEnforcesMaxStepsCapAndReturnsLimitReached() {
        val workerProvider = ScriptedProvider(
            listOf(
                // Step 1: Allowed
                { _: ProviderRequest, _: AgentRunController ->
                    assistant(
                        finishReason = "tool_calls",
                        toolCalls = listOf(toolCall("step_1", "tap_element", """{"index":1,"observation_id":"obs_1"}""")),
                    )
                },
                // Step 2: Allowed
                { _: ProviderRequest, _: AgentRunController ->
                    assistant(
                        finishReason = "tool_calls",
                        toolCalls = listOf(toolCall("step_2", "scroll", """{"direction":"down"}""")),
                    )
                },
                // Step 3: Exceeds max_steps = 2, rejected with SUBAGENT_MAX_STEPS_REACHED
                { _: ProviderRequest, _: AgentRunController ->
                    assistant(
                        finishReason = "tool_calls",
                        toolCalls = listOf(toolCall("step_3", "scroll", """{"direction":"down"}""")),
                    )
                },
                // Step 4: Worker reads the limit message and finishes
                { request: ProviderRequest, _: AgentRunController ->
                    val lastToolResult = request.messages.getJSONObject(request.messages.length() - 1).getString("content")
                    assertTrue(lastToolResult.contains("SUBAGENT_MAX_STEPS_REACHED"))
                    assistant(content = "已达到2步上限，当前列表已滚动到底部。", finishReason = "stop")
                },
            ),
        )

        var parentExecuted = 0
        val parentToolExecutor = AgentModelClient.ToolExecutor { call ->
            parentExecuted++
            AgentModelClient.ToolResult("""{"ok":true}""")
        }

        val mainConfig = modelConfig(
            model = "gemini-4-argon",
            subagentModel = "gemini-3.8-flash-tiered",
            subagentEnabled = true,
        )

        val executor = SubagentExecutor(
            parentConfig = mainConfig,
            parentToolExecutor = parentToolExecutor,
            providerResolver = { workerProvider },
        )

        val toolCall = AgentModelClient.ToolCall(
            id = "parent_call",
            name = "delegate_task",
            argumentsJson = """{"task":"最多翻两页","max_steps":2}""",
        )

        val result = executor.execute(toolCall)
        val resultJson = JSONObject(result.content)

        assertTrue(resultJson.getBoolean("ok"))
        assertEquals(2, parentExecuted)
        assertEquals(2, resultJson.getInt("steps_taken"))
        assertEquals(2, resultJson.getInt("max_steps"))
        assertTrue("limit_reached must be true", resultJson.getBoolean("limit_reached"))
        assertEquals("已达到2步上限，当前列表已滚动到底部。", resultJson.getString("worker_report"))
        assertEquals(2, resultJson.getJSONArray("actions_trace").length())
    }

    @Test
    fun agentRuntimeWireRoundTripsSubagentModelAndSubagentEnabled() {
        val baseConfig = modelConfig(
            model = "custom-main",
            subagentModel = "custom-worker-model",
            subagentEnabled = false,
        )

        val request = AgentRuntimeWire.RunRequest(
            runId = "subagent-wire-test",
            prompt = "测试传输",
            config = baseConfig,
            images = emptyList(),
        )

        // 1. Full round-trip
        val bundle = AgentRuntimeWire.toLegacyBundle(request)
        val deserialized = AgentRuntimeWire.runRequestFromBundle(bundle)
        assertEquals("custom-worker-model", deserialized.config.subagentModel)
        assertEquals(false, deserialized.config.subagentEnabled)

        // 2. Default fallback when keys are absent
        bundle.remove("subagent_model")
        bundle.remove("subagent_enabled")
        val fallbackDeserialized = AgentRuntimeWire.runRequestFromBundle(bundle)
        assertEquals(AgentModelClient.DEFAULT_SUBAGENT_MODEL, fallbackDeserialized.config.subagentModel)
        assertEquals(true, fallbackDeserialized.config.subagentEnabled)
    }

    private fun modelConfig(
        model: String = "test-model",
        subagentModel: String = AgentModelClient.DEFAULT_SUBAGENT_MODEL,
        subagentEnabled: Boolean = true,
    ): AgentModelClient.ModelConfig =
        AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid/v1",
            apiKey = "test-key",
            model = model,
            contextWindow = 128_000,
            systemPrompt = "",
            browserTools = false,
            subagentModel = subagentModel,
            subagentEnabled = subagentEnabled,
        )

    private fun assistant(
        content: String = "",
        finishReason: String,
        toolCalls: List<JSONObject> = emptyList(),
        reasoning: String = "",
    ): JSONObject =
        JSONObject()
            .put("role", "assistant")
            .put("content", content)
            .put("reasoning_content", reasoning)
            .put("finish_reason", finishReason)
            .also { message ->
                if (toolCalls.isNotEmpty()) {
                    message.put("tool_calls", JSONArray(toolCalls))
                }
            }

    private fun toolCall(
        id: String,
        name: String,
        arguments: String,
    ): JSONObject =
        JSONObject()
            .put("id", id)
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("arguments", arguments),
            )

    private class ScriptedProvider(
        private val responses: List<(ProviderRequest, AgentRunController) -> JSONObject>,
    ) : AgentProviderClient {
        constructor(vararg responses: JSONObject) : this(
            responses.map { response -> { _, _ -> response } },
        )

        override val id: String = "scripted"
        override val capabilities: ProviderCapabilities = ProviderCapabilities(
            endpoint = EndpointKind.CHAT_COMPLETIONS,
            streamingText = true,
            streamingToolCalls = true,
            imageInput = true,
            toolResultImages = false,
            strictTools = false,
            parallelToolCalls = false,
        )

        val requests = mutableListOf<JSONArray>()
        private var index = 0

        override fun complete(
            request: ProviderRequest,
            runController: AgentRunController,
            onEvent: (ProviderEvent) -> Unit,
        ): ProviderResponse {
            requests += JSONArray(request.messages.toString())
            val response = responses.getOrNull(index)
                ?: error("缺少第 ${index + 1} 个 scripted response")
            index += 1
            return ProviderResponse(response(request, runController))
        }
    }
}
