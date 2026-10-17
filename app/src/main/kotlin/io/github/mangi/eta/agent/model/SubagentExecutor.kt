package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.skill.SkillContext
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
    private val capabilitiesProvider: () -> AgentToolCapabilities = { AgentToolCapabilities(rootAvailable = false) },
    private val parentOnEvent: (AgentEvent) -> Unit = {},
    private val parentRound: Int = 1,
) {
    private val traceFormatter = AgentTraceFormatter()

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
        var latestObservation: JSONObject? = null

        val wrappedToolExecutor = createWorkerToolExecutor(
            maxSteps = maxSteps,
            stepsTrace = stepsTrace,
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

        val response = try {
            AgentModelClient.complete(
                config = workerConfig,
                prompt = subagentPrompt,
                toolExecutor = wrappedToolExecutor,
                provider = providerResolver(workerConfig),
                runController = runController,
                skillContext = skillContext,
                memoryContext = AgentMemoryContext.DISABLED,
                capabilitiesProvider = capabilitiesProvider,
                onEvent = childOnEvent,
            )
        } catch (e: Exception) {
            runController.throwIfCancelled()
            if (e is java.util.concurrent.CancellationException) throw e
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
            .put("worker_model", workerConfig.model)
            .put("steps_taken", stepsTaken)
            .put("max_steps", maxSteps)
            .put("limit_reached", stepsTaken >= maxSteps && limitTriggered)
            .put("actions_trace", JSONArray(stepsTrace))
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
        appendLine("1. 快速、机械、连续地调用工具完成上述任务。")
        appendLine("2. 成功操作后优先使用返回的 after 界面信息判断界面变化，不必重复调用 observe_screen。")
        appendLine("3. 一旦满足完成条件，或工具返回 SUBAGENT_MAX_STEPS_REACHED，立即停止调用工具。")
        appendLine("4. 任务结束时，输出简明扼要的执行总结，汇报完成的具体操作及最终界面/任务状态。")
    }

    internal fun createWorkerToolExecutor(
        maxSteps: Int,
        stepsTrace: MutableList<JSONObject>,
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
                        .put("ok", false)
                        .put("code", "SUBAGENT_MAX_STEPS_REACHED")
                        .put("message", "已达到子代理步数上限 ($maxSteps)，请立即停止调用工具并输出当前执行总结。")
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
