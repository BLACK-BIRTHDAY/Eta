package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentRuntimePolicy
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import io.github.mangi.eta.agent.terminal.TerminalExecutionContext
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.Settings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SubagentExecutorTest {
    private val testJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

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
        assertFalse(toolResultJson.getBoolean("partial"))
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

        val collectedOutputs = toolResultJson.getJSONArray("collected_outputs")
        assertEquals(2, collectedOutputs.length())
        assertEquals("tap_element", collectedOutputs.getJSONObject(0).getString("tool"))
        assertTrue(collectedOutputs.getJSONObject(0).getString("output").contains("obs_2"))
        assertEquals("scroll", collectedOutputs.getJSONObject(1).getString("tool"))
        assertTrue(collectedOutputs.getJSONObject(1).getString("output").contains("obs_3"))

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
        assertTrue("partial must be true when step limit reached", resultJson.getBoolean("partial"))
        assertEquals("已达到2步上限，当前列表已滚动到底部。", resultJson.getString("worker_report"))
        assertEquals(2, resultJson.getJSONArray("actions_trace").length())
        assertEquals(2, resultJson.getJSONArray("collected_outputs").length())
    }

    @Test
    fun subagentExecutorForwardsFullCapabilitiesAndFiltersDelegateTask() {
        val capturedWorkerTools = mutableListOf<String>()
        val executedTools = mutableListOf<String>()

        val mainProvider = ScriptedProvider(
            assistant(
                finishReason = "tool_calls",
                toolCalls = listOf(
                    toolCall(
                        id = "call_delegate",
                        name = "delegate_task",
                        arguments = """{"task":"运行命令并查询MCP工具","max_steps":5}""",
                    ),
                ),
            ),
            assistant(
                content = "主模型已收到子代理汇报：已完成命令与查询。",
                finishReason = "stop",
            ),
        )

        val workerProvider = ScriptedProvider(
            listOf(
                // Worker Round 1: execute terminal tool
                { request: ProviderRequest, _: AgentRunController ->
                    val toolNames = (0 until request.tools.length()).map { i ->
                        val tool = request.tools.getJSONObject(i)
                        tool.optJSONObject("function")?.optString("name") ?: tool.optString("name")
                    }
                    capturedWorkerTools.addAll(toolNames)
                    assistant(
                        finishReason = "tool_calls",
                        toolCalls = listOf(
                            toolCall(
                                id = "call_worker_term",
                                name = "terminal",
                                arguments = """{"command":"ls"}""",
                            ),
                        ),
                    )
                },
                // Worker Round 2: execute mcp_weather
                { _: ProviderRequest, _: AgentRunController ->
                    assistant(
                        finishReason = "tool_calls",
                        toolCalls = listOf(
                            toolCall(
                                id = "call_worker_mcp",
                                name = "mcp_weather",
                                arguments = """{"city":"Beijing"}""",
                            ),
                        ),
                    )
                },
                // Worker Round 3: finish
                { _: ProviderRequest, _: AgentRunController ->
                    assistant(
                        content = "命令和MCP查询均已执行成功。",
                        finishReason = "stop",
                    )
                },
            ),
        )

        val mainConfig = modelConfig(
            model = "gemini-4-argon",
            subagentModel = "gemini-3.8-flash-tiered",
            subagentEnabled = true,
        ).copy(
            terminalTools = true,
            browserTools = true,
        )

        val mcpTool = JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", "mcp_weather")
                    .put("description", "Get weather")
                    .put("parameters", JSONObject().put("type", "object")),
            )
        val sneakyDelegateTool = JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", "delegate_task")
                    .put("description", "Sneaky duplicate"),
            )
        val additionalTools = JSONArray().put(mcpTool).put(sneakyDelegateTool)
        val memoryContext = AgentMemoryContext.DISABLED.copy(enabled = true)
        val executionContext = TerminalExecutionContext(TerminalEnvironment.DEBIAN, linuxRootfsPath = null)

        val toolExecutor = AgentModelClient.ToolExecutor { call ->
            executedTools += call.name
            when (call.name) {
                "terminal" -> AgentModelClient.ToolResult("""{"ok":true,"stdout":"file1\nfile2"}""")
                "mcp_weather" -> AgentModelClient.ToolResult("""{"ok":true,"temp":22}""")
                else -> AgentModelClient.ToolResult("""{"ok":true}""")
            }
        }

        val result = AgentModelClient.complete(
            config = mainConfig,
            prompt = "请执行复杂全能力委派任务",
            toolExecutor = toolExecutor,
            providerResolver = { config ->
                if (config.model == "gemini-4-argon") mainProvider else workerProvider
            },
            additionalTools = additionalTools,
            memoryContext = memoryContext,
            executionContext = executionContext,
        )

        assertEquals("主模型已收到子代理汇报：已完成命令与查询。", result.content)
        assertEquals(listOf("terminal", "mcp_weather"), executedTools)

        // Verify tools available to worker
        assertTrue("Worker must have terminal tool", capturedWorkerTools.contains("terminal"))
        assertTrue("Worker must have memory_get tool", capturedWorkerTools.contains("memory_get"))
        assertTrue("Worker must have mcp_weather tool", capturedWorkerTools.contains("mcp_weather"))
        assertFalse("Worker must NOT have delegate_task tool", capturedWorkerTools.contains("delegate_task"))

        // Verify subagent execution trace
        val mainSecondRequest = mainProvider.requests[1]
        val toolResultJson = JSONObject(
            mainSecondRequest.getJSONObject(mainSecondRequest.length() - 1).getString("content"),
        )
        assertTrue(toolResultJson.getBoolean("ok"))
        assertEquals(2, toolResultJson.getInt("steps_taken"))
        val trace = toolResultJson.getJSONArray("actions_trace")
        assertEquals(2, trace.length())
        assertEquals("terminal", trace.getJSONObject(0).getString("tool"))
        assertEquals("mcp_weather", trace.getJSONObject(1).getString("tool"))
    }

    @Test
    fun toSubagentConfigHandlesConfigJsonEffortAndContextWindow() {
        // Case 1: subagentConfigJson is blank, uses subagentModel, subagentReasoningEffort, subagentContextWindow
        val base = modelConfig(
            model = "main-model",
            subagentModel = "custom-worker-model",
            subagentReasoningEffort = ReasoningEffort.HIGH,
            subagentContextWindow = 64_000,
            subagentConfigJson = "",
        )
        val worker1 = base.toSubagentConfig()
        assertEquals("custom-worker-model", worker1.model)
        assertEquals("custom-worker-model", worker1.modelDisplayName)
        assertFalse(worker1.subagentEnabled)
        assertEquals("", worker1.subagentConfigJson)
        assertTrue(worker1.thinkingEnabled)
        assertEquals(ReasoningEffort.HIGH, worker1.reasoningEffort)
        assertEquals(64_000, worker1.contextWindow)

        // Case 2: subagentReasoningEffort is OFF
        val worker2 = base.copy(subagentReasoningEffort = ReasoningEffort.OFF).toSubagentConfig()
        assertFalse(worker2.thinkingEnabled)
        assertEquals(ReasoningEffort.OFF, worker2.reasoningEffort)

        // Case 3: subagentConfigJson is provided from another provider
        val customSubConfig = AgentModelClient.ModelConfig(
            providerId = "provider-claude",
            providerName = "Anthropic",
            providerType = "anthropic",
            baseUrl = "https://api.anthropic.com",
            apiKey = "sk-ant-test",
            model = "claude-3-7-sonnet",
            modelDisplayName = "Claude 3.7 Sonnet",
            contextWindow = 200_000,
            systemPrompt = "sub-prompt",
            terminalTools = false,
            browserTools = false,
            deviceDirectTools = false,
            deviceSensitiveReadTools = false,
            deviceSensitiveActionTools = false,
            autoCompactionEnabled = false,
        )
        val jsonStr = testJson.encodeToString(customSubConfig)

        val parentWithJson = base.copy(
            subagentConfigJson = jsonStr,
            subagentReasoningEffort = ReasoningEffort.LOW,
            subagentContextWindow = 128_000,
            terminalTools = true,
            browserTools = true,
            deviceDirectTools = true,
            deviceSensitiveReadTools = true,
            deviceSensitiveActionTools = true,
            autoCompactionEnabled = true,
        )
        val worker3 = parentWithJson.toSubagentConfig()
        // Decoded from subagentConfigJson
        assertEquals("provider-claude", worker3.providerId)
        assertEquals("Anthropic", worker3.providerName)
        assertEquals("claude-3-7-sonnet", worker3.model)
        assertEquals("https://api.anthropic.com", worker3.baseUrl)
        assertEquals("sk-ant-test", worker3.apiKey)
        // Inherits tool permissions and runtime flags from parent
        assertTrue(worker3.terminalTools)
        assertTrue(worker3.browserTools)
        assertTrue(worker3.deviceDirectTools)
        assertTrue(worker3.deviceSensitiveReadTools)
        assertTrue(worker3.deviceSensitiveActionTools)
        assertTrue(worker3.autoCompactionEnabled)
        // Controlled subagent flags
        assertFalse(worker3.subagentEnabled)
        assertEquals("", worker3.subagentConfigJson)
        assertTrue(worker3.thinkingEnabled)
        assertEquals(ReasoningEffort.LOW, worker3.reasoningEffort)
        assertEquals(128_000, worker3.contextWindow)
    }

    @Test
    fun agentRuntimeWireRoundTripsAllSubagentFields() {
        val baseConfig = modelConfig(
            model = "custom-main",
            subagentModel = "custom-worker-model",
            subagentEnabled = false,
            subagentReasoningEffort = ReasoningEffort.XHIGH,
            subagentContextWindow = 32_000,
            subagentConfigJson = """{"custom":"subagent"}""",
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
        assertEquals(ReasoningEffort.XHIGH, deserialized.config.subagentReasoningEffort)
        assertEquals(32_000, deserialized.config.subagentContextWindow)
        assertEquals("""{"custom":"subagent"}""", deserialized.config.subagentConfigJson)

        // 2. Default fallback when keys are absent
        bundle.remove("subagent_model")
        bundle.remove("subagent_enabled")
        bundle.remove("subagent_reasoning_effort")
        bundle.remove("subagent_context_window")
        bundle.remove("subagent_config_json")
        val fallbackDeserialized = AgentRuntimeWire.runRequestFromBundle(bundle)
        assertEquals(AgentModelClient.DEFAULT_SUBAGENT_MODEL, fallbackDeserialized.config.subagentModel)
        assertEquals(true, fallbackDeserialized.config.subagentEnabled)
        assertEquals(ReasoningEffort.DEFAULT, fallbackDeserialized.config.subagentReasoningEffort)
        assertNull(fallbackDeserialized.config.subagentContextWindow)
        assertEquals("", fallbackDeserialized.config.subagentConfigJson)
    }

    @Test
    fun agentRuntimePolicyConstrainsSubagentThinking() {
        val subConfig = AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid",
            apiKey = "key",
            model = "worker",
            systemPrompt = "",
            thinkingEnabled = true,
            reasoningEffort = ReasoningEffort.HIGH,
        )
        val jsonStr = testJson.encodeToString(subConfig)

        val config = modelConfig(
            model = "main",
            subagentModel = "worker",
            subagentReasoningEffort = ReasoningEffort.MEDIUM,
            subagentConfigJson = jsonStr,
        ).copy(
            thinkingEnabled = true,
            reasoningEffort = ReasoningEffort.HIGH,
        )

        val permissionsNoThinking = AgentRuntimePolicy.Permissions(
            terminalTools = true,
            browserTools = true,
            thinking = false,
        )

        val constrained = AgentRuntimePolicy.constrain(config, permissionsNoThinking)
        assertEquals(ReasoningEffort.OFF, constrained.reasoningEffort)
        assertFalse(constrained.thinkingEnabled)
        assertEquals(ReasoningEffort.OFF, constrained.subagentReasoningEffort)

        // When converted to subagent config, worker also has thinking disabled
        val worker = constrained.toSubagentConfig()
        assertFalse(worker.thinkingEnabled)
        assertEquals(ReasoningEffort.OFF, worker.reasoningEffort)
    }

    @Test
    fun settingsDefaultSubagentDisabled() {
        val defaultSettings = Settings()
        assertFalse(defaultSettings.subagentEnabled)
        assertNull(defaultSettings.subagentModelId)
        assertEquals(ReasoningEffort.DEFAULT, defaultSettings.subagentReasoningEffort)
    }

    @Test
    fun maxStepsUpTo200SupportedAndPreservesPartialResultsOnLimit() {
        assertEquals(25, AgentSubagentToolCatalog.DEFAULT_MAX_STEPS)
        assertEquals(1, AgentSubagentToolCatalog.MIN_MAX_STEPS)
        assertEquals(200, AgentSubagentToolCatalog.MAX_MAX_STEPS)

        val workerProvider = ScriptedProvider(
            listOf(
                { _: ProviderRequest, _: AgentRunController ->
                    assistant(
                        finishReason = "tool_calls",
                        toolCalls = listOf(toolCall("s1", "tap_element", """{"index":1,"observation_id":"obs_1"}""")),
                    )
                },
                { _: ProviderRequest, _: AgentRunController ->
                    assistant(
                        finishReason = "tool_calls",
                        toolCalls = listOf(toolCall("s2", "scroll", """{"direction":"down"}""")),
                    )
                },
                { _: ProviderRequest, _: AgentRunController ->
                    assistant(
                        finishReason = "tool_calls",
                        toolCalls = listOf(toolCall("s3", "tap_element", """{"index":2,"observation_id":"obs_2"}""")),
                    )
                },
                { _: ProviderRequest, _: AgentRunController ->
                    assistant(
                        finishReason = "tool_calls",
                        toolCalls = listOf(toolCall("s4", "scroll", """{"direction":"down"}""")),
                    )
                },
                { request: ProviderRequest, _: AgentRunController ->
                    val lastResult = request.messages.getJSONObject(request.messages.length() - 1).getString("content")
                    val resultJson = JSONObject(lastResult)
                    assertTrue(resultJson.getBoolean("ok"))
                    assertTrue(resultJson.getBoolean("limit_reached"))
                    assertEquals("SUBAGENT_MAX_STEPS_REACHED", resultJson.getString("code"))
                    assistant(content = "已完成前3步数据收集。", finishReason = "stop")
                },
            ),
        )

        val parentToolExecutor = AgentModelClient.ToolExecutor { call ->
            AgentModelClient.ToolResult("""{"ok":true,"result_of":"${call.name}"}""")
        }

        val mainConfig = modelConfig(
            model = "main-model",
            subagentModel = "worker-model",
            subagentEnabled = true,
        )

        val executor = SubagentExecutor(
            parentConfig = mainConfig,
            parentToolExecutor = parentToolExecutor,
            providerResolver = { workerProvider },
        )

        val toolCall = AgentModelClient.ToolCall(
            id = "call_p",
            name = "delegate_task",
            argumentsJson = """{"task":"多步任务","max_steps":3}""",
        )

        val result = executor.execute(toolCall)
        val resultJson = JSONObject(result.content)

        assertTrue(resultJson.getBoolean("ok"))
        assertTrue("partial must be true", resultJson.getBoolean("partial"))
        assertTrue("limit_reached must be true", resultJson.getBoolean("limit_reached"))
        assertEquals(3, resultJson.getInt("steps_taken"))
        assertEquals(3, resultJson.getInt("max_steps"))
        assertEquals("worker-model", resultJson.getString("worker_model"))
        assertEquals("已完成前3步数据收集。", resultJson.getString("worker_report"))

        val collectedOutputs = resultJson.getJSONArray("collected_outputs")
        assertEquals(3, collectedOutputs.length())
        assertEquals("tap_element", collectedOutputs.getJSONObject(0).getString("tool"))
        assertTrue(collectedOutputs.getJSONObject(0).getString("output").contains("result_of"))
        assertEquals("scroll", collectedOutputs.getJSONObject(1).getString("tool"))
        assertEquals("tap_element", collectedOutputs.getJSONObject(2).getString("tool"))
    }

    @Test
    fun midRunExceptionPreservesCollectedOutputsAndReturnsPartialResults() {
        val workerProvider = ScriptedProvider(
            listOf(
                { _: ProviderRequest, _: AgentRunController ->
                    assistant(
                        finishReason = "tool_calls",
                        toolCalls = listOf(toolCall("call_1", "tap_element", """{"index":1,"observation_id":"obs_1"}""")),
                    )
                },
                { _: ProviderRequest, _: AgentRunController ->
                    throw RuntimeException("Network connection lost mid-execution")
                },
            ),
        )

        val parentToolExecutor = AgentModelClient.ToolExecutor { call ->
            AgentModelClient.ToolResult("""{"ok":true,"stdout":"data chunk 1"}""")
        }

        val mainConfig = modelConfig(
            model = "main-model",
            subagentModel = "worker-model",
            subagentEnabled = true,
        )

        val executor = SubagentExecutor(
            parentConfig = mainConfig,
            parentToolExecutor = parentToolExecutor,
            providerResolver = { workerProvider },
        )

        val toolCall = AgentModelClient.ToolCall(
            id = "call_mid_fail",
            name = "delegate_task",
            argumentsJson = """{"task":"抓取数据","max_steps":10}""",
        )

        val result = executor.execute(toolCall)
        val resultJson = JSONObject(result.content)

        assertTrue("ok must be true for partial results hand-off", resultJson.getBoolean("ok"))
        assertTrue("partial must be true", resultJson.getBoolean("partial"))
        assertEquals("SUBAGENT_PARTIAL_RESULTS", resultJson.getString("interrupted_code"))
        assertTrue(resultJson.getString("interrupted_message").contains("Network connection lost"))
        assertEquals(1, resultJson.getInt("steps_taken"))
        assertEquals(10, resultJson.getInt("max_steps"))
        assertEquals("worker-model", resultJson.getString("worker_model"))

        val collected = resultJson.getJSONArray("collected_outputs")
        assertEquals(1, collected.length())
        assertEquals("tap_element", collected.getJSONObject(0).getString("tool"))
        assertTrue(collected.getJSONObject(0).getString("output").contains("data chunk 1"))

        val trace = resultJson.getJSONArray("actions_trace")
        assertEquals(1, trace.length())
        assertEquals("tap_element", trace.getJSONObject(0).getString("tool"))

        val report = resultJson.getString("worker_report")
        assertTrue(report.contains("子代理在完成 1 步后结束"))
        assertTrue(report.contains("collected_outputs"))
    }

    @Test
    fun zeroStepExceptionReturnsExecutionFailed() {
        val workerProvider = ScriptedProvider(
            listOf(
                { _: ProviderRequest, _: AgentRunController ->
                    throw RuntimeException("Initial provider connection failed")
                },
            ),
        )

        val executor = SubagentExecutor(
            parentConfig = modelConfig(),
            parentToolExecutor = AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("""{"ok":true}""") },
            providerResolver = { workerProvider },
        )

        val toolCall = AgentModelClient.ToolCall(
            id = "call_zero_fail",
            name = "delegate_task",
            argumentsJson = """{"task":"初始化即失败的任务","max_steps":10}""",
        )

        val result = executor.execute(toolCall)
        val resultJson = JSONObject(result.content)

        assertFalse("ok must be false when stepsTaken == 0", resultJson.getBoolean("ok"))
        assertEquals("SUBAGENT_EXECUTION_FAILED", resultJson.getString("code"))
        assertEquals("Initial provider connection failed", resultJson.getString("message"))
        assertEquals(0, resultJson.getInt("steps_taken"))
        assertEquals(10, resultJson.getInt("max_steps"))
    }

    private fun modelConfig(
        model: String = "test-model",
        subagentModel: String = AgentModelClient.DEFAULT_SUBAGENT_MODEL,
        subagentEnabled: Boolean = true,
        subagentReasoningEffort: ReasoningEffort = ReasoningEffort.DEFAULT,
        subagentContextWindow: Int? = null,
        subagentConfigJson: String = "",
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
            subagentReasoningEffort = subagentReasoningEffort,
            subagentContextWindow = subagentContextWindow,
            subagentConfigJson = subagentConfigJson,
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
