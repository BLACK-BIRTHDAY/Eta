package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentSubagentToolCatalog {
    const val TOOL_NAME = "delegate_task"
    const val DEFAULT_MAX_STEPS = 25
    const val MIN_MAX_STEPS = 1
    const val MAX_MAX_STEPS = 200

    fun schema(): JSONObject =
        AgentToolSchema.function(
            name = TOOL_NAME,
            description = "委派具备全能力的高速子代理（Worker Subagent）在独立上下文中连续执行多步任务（涵盖系统终端/Linux 命令、内置文件读写/编辑/检索、网页搜索与浏览器操作、设备状态与个人数据查询、GUI 界面微操作、技能调用、长期记忆及外部 MCP 工具等）。支持在同一轮同时发起多个 delegate_task 并行执行互不依赖的子任务。子代理完成操作或达到停止条件后，向主模型返回执行轨迹摘要与最终状态供主模型核对验收。子代理内部禁止再次调用 delegate_task。",
            parameters = JSONObject()
                .put("type", "object")
                .put("additionalProperties", false)
                .put(
                    "properties",
                    JSONObject()
                        .put(
                            "task",
                            JSONObject()
                                .put("type", "string")
                                .put("minLength", 1)
                                .put("maxLength", 4000)
                                .put("description", "需要子代理完成的具体操作目标、执行步骤与约束条件（支持终端、文件、网页、设备、GUI 操作等多步骤连续执行）"),
                        )
                        .put(
                            "acceptance_criteria",
                            JSONObject()
                                .put("type", "string")
                                .put("maxLength", 2000)
                                .put("description", "可选的完成判定标准或停止条件；达成后子代理立即结束并汇报"),
                        )
                        .put(
                            "max_steps",
                            JSONObject()
                                .put("type", "integer")
                                .put("minimum", MIN_MAX_STEPS)
                                .put("maximum", MAX_MAX_STEPS)
                                .put("default", DEFAULT_MAX_STEPS)
                                .put(
                                    "description",
                                    "由主模型（规划模型）根据子任务复杂度自主设定的最大工具调用步数（1～200，默认 25）。简单任务可设 10～20，复杂多源检索、长链路操作或批量任务可设 30～100。超出步数时不会废弃已有成果，而是将已完成步骤和已获取的结果完整交回主模型。",
                                ),
                        ),
                )
                .put("required", JSONArray().put("task")),
        )
}
