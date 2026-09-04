package io.github.mangi.eta.agent.runtime

import android.os.Handler
import android.os.Looper
import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.File

/**
 * 将内部高频的 [AgentEvent] 流式事件映射为符合 ColorOS 流体云规范的视觉状态。
 *
 * 内置 500ms 阻尼节流器，保护系统状态栏流畅度；遵循 Q1 紧凑精细模式：动词 + 核心文件名/工具名。
 */
internal object EtaFluidCloudStateMapper {

    private const val THROTTLE_INTERVAL_MS = 500L
    private val mainHandler = Handler(Looper.getMainLooper())

    private var lastEmittedTime = 0L
    private var pendingUpdateRunnable: Runnable? = null
    private var currentRunId: String? = null

    // 统计写操作的文件数量
    private val modifiedFiles = mutableSetOf<String>()

    fun reset(runId: String) {
        currentRunId = runId
        modifiedFiles.clear()
        pendingUpdateRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingUpdateRunnable = null
        lastEmittedTime = 0L
    }

    fun onAgentEvent(runId: String, event: AgentEvent) {
        if (currentRunId != runId) {
            reset(runId)
        }

        val (shortText, detailText, immediate) = when (event) {
            is AgentEvent.RoundStarted -> {
                Triple("🧠 构思中", "正在规划第 ${event.round} 轮操作...", true)
            }

            is AgentEvent.ProviderRequestStarted -> {
                Triple("🧠 模型思考", "正在等待模型生成回复...", false)
            }

            is AgentEvent.AssistantBlockStart -> {
                if (event.kind == AgentEvent.AssistantBlockKind.THINKING) {
                    Triple("🧠 深度沉思", "正在深入思考任务方案...", true)
                } else if (event.kind == AgentEvent.AssistantBlockKind.TOOL_CALL) {
                    Triple("⚡ 准备工具", "正在装配工具执行参数...", false)
                } else {
                    return
                }
            }

            is AgentEvent.AssistantBlockDelta -> {
                if (event.kind == AgentEvent.AssistantBlockKind.THINKING) {
                    Triple("🧠 沉思中", "模型正在分析...", false)
                } else {
                    return
                }
            }

            is AgentEvent.ToolStarted -> {
                val toolName = event.name
                val short = formatToolShortText(toolName, event.argsPreview, event.command)
                val detail = "正在执行: $toolName"
                Triple(short, detail, true) // 工具切换属于大阶段跳跃，根据 Q2 决策即时触发
            }

            is AgentEvent.ToolFinished -> {
                val short = "⚡ 完成操作"
                Triple(short, event.resultSummary.take(60), false)
            }

            else -> return
        }

        emitThrottled(runId, shortText, detailText, immediate)
    }

    private fun formatToolShortText(toolName: String, args: String, command: String?): String {
        return when (toolName) {
            "Edit", "Write" -> {
                val fileName = extractFileName(args)
                if (fileName.isNotBlank()) {
                    modifiedFiles.add(fileName)
                    "📝 写入: $fileName (${modifiedFiles.size})"
                } else {
                    "📝 代码修改 (${modifiedFiles.size})"
                }
            }

            "Read" -> {
                val fileName = extractFileName(args)
                if (fileName.isNotBlank()) "📖 读取: $fileName" else "📖 查看文件"
            }

            "Grep", "Glob" -> "🔍 搜索代码"

            "Bash" -> {
                val cmdHeader = (command ?: extractCommand(args))
                    .trim()
                    .split("\\s+".toRegex())
                    .firstOrNull()
                    ?.let { File(it).name }
                    ?.take(10)
                    ?: "命令"
                "🔨 执行: $cmdHeader"
            }

            else -> "⚡ 执行: $toolName"
        }
    }

    private fun extractFileName(args: String): String {
        // 从 JSON 或参数文本提取 file_path
        val match = "(?:file_path|path)[\"':\\s]+([^\"',}\\s]+)".toRegex().find(args)
        val fullPath = match?.groupValues?.get(1)?.trim() ?: return ""
        return File(fullPath).name
    }

    private fun extractCommand(args: String): String {
        val match = "command[\"':\\s]+([^\"',}\\n]+)".toRegex().find(args)
        return match?.groupValues?.get(1)?.trim().orEmpty()
    }

    private fun emitThrottled(runId: String, shortText: String, detailText: String, immediate: Boolean) {
        val now = System.currentTimeMillis()
        val timeSinceLast = now - lastEmittedTime

        pendingUpdateRunnable?.let { mainHandler.removeCallbacks(it) }

        if (immediate || timeSinceLast >= THROTTLE_INTERVAL_MS) {
            lastEmittedTime = now
            EtaLiveUpdateManager.update(runId, shortText, detailText)
        } else {
            val remaining = THROTTLE_INTERVAL_MS - timeSinceLast
            val runnable = Runnable {
                lastEmittedTime = System.currentTimeMillis()
                EtaLiveUpdateManager.update(runId, shortText, detailText)
            }
            pendingUpdateRunnable = runnable
            mainHandler.postDelayed(runnable, remaining)
        }
    }
}
