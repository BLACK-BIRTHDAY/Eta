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
                Triple("🧠 构思", "正在规划第 ${event.round} 轮操作...", true)
            }

            is AgentEvent.ProviderRequestStarted -> {
                Triple("🧠 思考", "正在等待模型生成回复...", false)
            }

            is AgentEvent.AssistantBlockStart -> {
                if (event.kind == AgentEvent.AssistantBlockKind.THINKING) {
                    Triple("🧠 沉思", "正在深入思考任务方案...", true)
                } else if (event.kind == AgentEvent.AssistantBlockKind.TOOL_CALL) {
                    Triple("⚡ 装配", "正在装配工具执行参数...", false)
                } else {
                    return
                }
            }

            is AgentEvent.AssistantBlockDelta -> {
                if (event.kind == AgentEvent.AssistantBlockKind.THINKING) {
                    Triple("🧠 沉思", "模型正在分析...", false)
                } else {
                    return
                }
            }

            is AgentEvent.ToolStarted -> {
                val toolName = event.name
                val (short, detail) = formatToolTexts(toolName, event.argsPreview, event.command)
                Triple(short, detail, true) // 工具切换属于大阶段跳跃，根据 Q2 决策即时触发
            }

            is AgentEvent.ToolFinished -> {
                val short = "⚡ 就绪"
                Triple(short, event.resultSummary.take(60), false)
            }

            else -> return
        }

        emitThrottled(runId, shortText, detailText, immediate)
    }

    private fun formatToolTexts(toolName: String, args: String, command: String?): Pair<String, String> {
        return when (toolName) {
            "Edit", "Write" -> {
                val fileName = extractFileName(args)
                if (fileName.isNotBlank()) {
                    modifiedFiles.add(fileName)
                    Pair("📝 写入", "正在写入: $fileName (第 ${modifiedFiles.size} 个文件)")
                } else {
                    Pair("📝 编写", "正在修改代码 (${modifiedFiles.size})")
                }
            }

            "Read" -> {
                val fileName = extractFileName(args)
                if (fileName.isNotBlank()) {
                    Pair("📖 读取", "正在读取: $fileName")
                } else {
                    Pair("📖 查看", "正在查看文件")
                }
            }

            "Grep", "Glob" -> Pair("🔍 搜索", "正在搜索工程代码库...")

            "Bash" -> {
                val cmdHeader = (command ?: extractCommand(args))
                    .trim()
                    .split("\\s+".toRegex())
                    .firstOrNull()
                    ?.let { File(it).name }
                    ?.take(8)
                    ?: "命令"
                Pair("🔨 $cmdHeader".take(5), "正在执行命令: ${command ?: extractCommand(args)}")
            }

            else -> Pair("⚡ 执行", "正在执行工具: $toolName")
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
