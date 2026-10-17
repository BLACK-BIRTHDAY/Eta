package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.terminal.TerminalExecutionContext
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import org.json.JSONArray
import org.json.JSONObject

internal class SubagentExecutor(
    private val parentConfig: AgentModelClient.ModelConfig,
    private val parentToolExecutor: AgentModelClient.ToolExecutor,
    private val providerResolver: (AgentModelClient.ModelConfig) -> AgentProviderClient = {
        ProviderClientFactory.getClient(it)
    },
    private val runController: AgentRunController = AgentRunController(),
    private val skillContext: SkillContext = SkillContext.EMPTY,
    private val memoryContext: AgentMemoryContext = AgentMemoryContext.DISABLED,
    private val additionalTools: JSONArray = JSONArray(),
    private val executionContext: TerminalExecutionContext? = null,
    private val capabilitiesProvider: () -> AgentToolCapabilities = { AgentToolCapabilities(rootAvailable = false) },
    private val parentOnEvent: (AgentEvent) -> Unit = {},
    private val parentRound: Int = 1,
) {
    private val traceFormatter = AgentTraceFormatter()

    companion object {
        private const val MAX_COLLECTED_OUTPUT_CHARS = 2_000
    }

    fun execute(
        call: AgentModelClient.ToolCall,
        round: Int = parentRound,
    ): AgentModelClient.ToolResult {
        val argsObj = runCatching { JSONObject(call.argumentsJson) }.getOrNull()
        val task = argsObj?.optString("task")?.trim().orEmpty()
        if (task.isBlank()) {
            return AgentModelClient.ToolResult(
                content = JSONObject()
                    .put("ok", false)
                    .put("code", "INVALID_ARGUMENT")
                    .put("message", "任务描述 (task) 不能为空")
                    .toString(),
            )
        }
        val acceptanceCriteria = argsObj?.optString("acceptance_criteria")?.trim()?.takeIf { it.isNotBlank() }
        val rawMaxSteps = if (argsObj?.has("max_steps") == true) {
            argsObj.optInt("max_steps", AgentSubagentToolCatalog.DEFAULT_MAX_STEPS)
        } else {
            AgentSubagentToolCatalog.DEFAULT_MAX_STEPS
        }
        val maxSteps = rawMaxSteps.coerceIn(
            AgentSubagentToolCatalog.MIN_MAX_STEPS,
            AgentSubagentToolCatalog.MAX_MAX_STEPS,
        )

        val workerConfig = parentConfig.toSubagentConfig()
        var stepsTaken = 0
        var limitTriggered = false
        val stepsTrace = mutableListOf<JSONObject>()
        val collectedOutputs = mutableListOf<JSONObject>()
        var latestObservation: JSONObject? = null

        val wrappedToolExecutor = createWorkerToolExecutor(
            maxSteps = maxSteps,
            stepsTrace = stepsTrace,
            collectedOutputs = collectedOutputs,
            onObservationCaptured = { latestObservation = it },
            onLimitTriggered = { limitTriggered = true },
            getStepsTaken = { stepsTaken },
            setStepsTaken = { stepsTaken = it },
        )

        val childOnEvent: (AgentEvent) -> Unit = { event ->
            when (event) {
                is AgentEvent.ToolStarted -> {
                    parentOnEvent(
                        event.copy(
                            round = round,
                            toolCallId = "${call.id}-sub-${event.round}-${event.toolCallId}",
                            argsPreview = "[子代理] ${event.argsPreview}",
                        ),
                    )
                }
                is AgentEvent.ToolFinished -> {
                    parentOnEvent(
                        event.copy(
                            round = round,
                            toolCallId = "${call.id}-sub-${event.round}-${event.toolCallId}",
                        ),
                    )
                }
                else -> Unit
            }
        }

        val subagentPrompt = buildPrompt(task, acceptanceCriteria)

        val filteredAdditionalTools = JSONArray().apply {
            for (i in 0 until additionalTools.length()) {
                val toolObj = additionalTools.optJSONObject(i)
                val toolName = toolObj?.optJSONObject("function")?.optString("name")
                    ?: toolObj?.optString("name")
                if (toolName != AgentSubagentToolCatalog.TOOL_NAME) {
                    put(additionalTools.opt(i))
                }
            }
        }

        val response = try {
            AgentModelClient.complete(
                config = workerConfig,
                prompt = subagentPrompt,
                toolExecutor = wrappedToolExecutor,
                provider = providerResolver(workerConfig),
                runController = runController,
                skillContext = skillContext,
                memoryContext = memoryContext,
                additionalTools = filteredAdditionalTools,
                executionContext = executionContext,
                capabilitiesProvider = capabilitiesProvider,
                onEvent = childOnEvent,
            )
        } catch (e: Exception) {
            runController.throwIfCancelled()
            if (e is java.util.concurrent.CancellationException) throw e
            if (stepsTaken > 0) {
                val partialJson = JSONObject()
                    .put("ok", true)
                    .put("partial", true)
                    .put("limit_reached", stepsTaken >= maxSteps && limitTriggered)
                    .put("interrupted_code", "SUBAGENT_PARTIAL_RESULTS")
                    .put("interrupted_message", e.message ?: e.javaClass.simpleName)
                    .put("worker_model", workerConfig.model)
                    .put("steps_taken", stepsTaken)
                    .put("max_steps", maxSteps)
                    .put("actions_trace", JSONArray(stepsTrace))
                    .put("collected_outputs", JSONArray(collectedOutputs))
                    .put(
                        "worker_report",
                        "子代理在完成 $stepsTaken 步后结束（${e.message ?: e.javaClass.simpleName}），已保留全部阶段性执行结果，请结合 collected_outputs 与 actions_trace 继续处理。",
                    )
                latestObservation?.let { partialJson.put("latest_observation", it) }
                return AgentModelClient.ToolResult(content = partialJson.toString())
            }
            return AgentModelClient.ToolResult(
                content = JSONObject()
                    .put("ok", false)
                    .put("code", "SUBAGENT_EXECUTION_FAILED")
                    .put("message", e.message ?: e.javaClass.simpleName)
                    .put("worker_model", workerConfig.model)
                    .put("steps_taken", stepsTaken)
                    .put("max_steps", maxSteps)
                    .put("limit_reached", stepsTaken >= maxSteps && limitTriggered)
                    .put("actions_trace", JSONArray(stepsTrace))
                    .toString(),
            )
        }

        val finalResultJson = JSONObject()
            .put("ok", true)
            .put("partial", stepsTaken >= maxSteps && limitTriggered)
            .put("worker_model", workerConfig.model)
            .put("steps_taken", stepsTaken)
            .put("max_steps", maxSteps)
            .put("limit_reached", stepsTaken >= maxSteps && limitTriggered)
            .put("actions_trace", JSONArray(stepsTrace))
            .put("collected_outputs", JSONArray(collectedOutputs))
            .put("worker_report", response.content)

        latestObservation?.let { finalResultJson.put("latest_observation", it) }

        return AgentModelClient.ToolResult(content = finalResultJson.toString())
    }

    private fun buildPrompt(task: String, acceptanceCriteria: String?): String = buildString {
        appendLine("【子代理执行任务】")
        appendLine("目标：$task")
        if (!acceptanceCriteria.isNullOrBlank()) {
            appendLine("完成条件 (停止标准)：$acceptanceCriteria")
        }
        appendLine()
        appendLine("执行说明：")
        appendLine("1. 快速、高效、连续地调用工具完成上述任务。你具备与主模型同等的全套工具能力（终端与命令执行、文件管理、网页搜索与浏览器交互、设备与个人数据查询、GUI 微操作、技能与外部 MCP 工具）。")
        appendLine("2. 进行 GUI 界面操作时，成功操作后优先使用返回的 after 界面信息判断变化，不必重复调用 observe_screen；进行终端、文件或网络查询时，根据实际返回结果推进下一步。")
        appendLine("3. 一旦满足完成条件，或工具返回 SUBAGENT_MAX_STEPS_REACHED，立即停止调用工具。")
        appendLine("4. 任务结束时，输出简明扼要的执行总结，汇报完成的具体操作、产物及最终状态。")
    }

    internal fun createWorkerToolExecutor(
        maxSteps: Int,
        stepsTrace: MutableList<JSONObject>,
        collectedOutputs: MutableList<JSONObject> = mutableListOf(),
        onObservationCaptured: (JSONObject) -> Unit,
        onLimitTriggered: () -> Unit,
        getStepsTaken: () -> Int,
        setStepsTaken: (Int) -> Unit,
    ): AgentModelClient.ToolExecutor {
        return AgentModelClient.ToolExecutor { childCall ->
            if (childCall.name == AgentSubagentToolCatalog.TOOL_NAME) {
                return@ToolExecutor AgentModelClient.ToolResult(
                    content = JSONObject()
                        .put("ok", false)
                        .put("code", "RECURSIVE_SUBAGENT_FORBIDDEN")
                        .put("message", "子代理内部禁止再次调用 delegate_task")
                        .toString(),
                )
            }
            val currentSteps = getStepsTaken()
            if (currentSteps >= maxSteps) {
                onLimitTriggered()
                return@ToolExecutor AgentModelClient.ToolResult(
                    content = JSONObject()
                        .put("ok", true)
                        .put("limit_reached", true)
                        .put("code", "SUBAGENT_MAX_STEPS_REACHED")
                        .put(
                            "message",
                            "已达到规划模型设定的步数上限 ($maxSteps 步)。请不要再调用任何工具，立即将前面 $maxSteps 步已经获取到的所有结果与进展整理成详细报告交回主模型，不要丢弃任何已有信息。",
                        )
                        .toString(),
                )
            }
            val nextStep = currentSteps + 1
            setStepsTaken(nextStep)
            val result = parentToolExecutor.execute(childCall)
            val ok = traceFormatter.isSuccessResult(result)
            val argsSummary = traceFormatter.summarizeArguments(childCall)
            val resultSummary = traceFormatter.summarizeResult(childCall.name, result)
            stepsTrace.add(
                JSONObject()
                    .put("step", nextStep)
                    .put("tool", childCall.name)
                    .put("args_summary", argsSummary)
                    .put("result_summary", resultSummary)
                    .put("ok", ok),
            )
            collectedOutputs.add(
                JSONObject()
                    .put("step", nextStep)
                    .put("tool", childCall.name)
                    .put("ok", ok)
                    .put("output", result.content.take(MAX_COLLECTED_OUTPUT_CHARS)),
            )

            val resultJson = runCatching { JSONObject(result.content) }.getOrNull()
            val afterObj = resultJson?.optJSONObject("after")
            if (afterObj != null) {
                onObservationCaptured(afterObj)
            } else if (childCall.name == "observe_screen" && resultJson != null && resultJson.optBoolean("ok", true)) {
                onObservationCaptured(resultJson)
            }

            result
        }
    }
}
