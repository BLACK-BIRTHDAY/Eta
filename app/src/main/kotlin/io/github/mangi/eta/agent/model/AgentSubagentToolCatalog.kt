package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentSubagentToolCatalog {
    const val TOOL_NAME = "delegate_task"
    const val DEFAULT_MAX_STEPS = 10
    const val MIN_MAX_STEPS = 1
    const val MAX_MAX_STEPS = 20

    fun schema(): JSONObject =
        AgentToolSchema.function(
            name = TOOL_NAME,
            description = "委派高速子代理（Worker Subagent）在独立上下文中连续执行简单、机械或重复性的界面微操作、列表滚动、文本输入或命令检查。子代理完成操作或达到停止条件后，只向主模型返回执行轨迹摘要与最终状态供主模型验收。子代理内部禁止再次调用 delegate_task。",
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
                                .put("description", "需要子代理完成的具体操作目标、执行步骤与约束条件"),
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
                                .put("description", "子代理允许执行的最大工具轮数，默认 10"),
                        ),
                )
                .put("required", JSONArray().put("task")),
        )
}
