package io.github.mangi.eta.ui.app

import android.content.Context
import androidx.compose.runtime.Immutable
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.terminal.AlpineEnvironmentPaths
import io.github.mangi.eta.agent.terminal.DetachedTaskStatus
import io.github.mangi.eta.agent.terminal.DetachedTaskSupervisor
import io.github.mangi.eta.agent.terminal.SharedFolderMounts
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import io.github.mangi.eta.agent.terminal.UserTerminalController
import io.github.mangi.eta.core.AndroidAgentLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
internal data class TerminalBlockUi(
    val id: Long,
    val isSystem: Boolean = false,
    val command: String = "",
    val cwdAtStart: String = "",
    val output: String = "",
    val exitCode: Int? = null,
    val running: Boolean = false,
    val truncated: Boolean = false,
)

@Immutable
internal data class DaemonTaskUi(
    val id: String,
    val command: String,
    val environment: TerminalEnvironment,
    val identity: String,
    val running: Boolean,
    val startedAt: Long,
)

@Immutable
internal data class UserTerminalUiState(
    val blocks: List<TerminalBlockUi> = emptyList(),
    val environment: TerminalEnvironment = TerminalEnvironment.ANDROID,
    val cwd: String = "",
    val running: Boolean = false,
    val sessionAlive: Boolean = false,
    val linuxReady: Boolean = false,
    val daemonTasks: List<DaemonTaskUi> = emptyList(),
)

/**
 * 用户手动终端的 App 级状态所有者：把 [UserTerminalController] 的线程模型映射为 Compose 状态。
 * 由 Activity 级 ViewModel 持有，离开终端页、旋转屏幕都会话不丢；App 进程死亡则会话随之结束。
 */
internal class UserTerminalStore(
    context: Context,
    private val scope: CoroutineScope,
) {
    private companion object {
        const val MAX_BLOCKS = 200
        const val MAX_BLOCK_OUTPUT_CHARS = 64_000
        const val FLUSH_INTERVAL_MS = 120L
        // TERM=dumb 下输出基本无 ANSI；这里只兜底剔除强制上色命令的 CSI/OSC 序列。
        val ANSI_PATTERN = Regex("\u001B\\[[0-9;?]*[A-Za-z]|\u001B\\][^\u0007]*\u0007")
    }

    private val appContext = context.applicationContext
    private val controller = UserTerminalController(
        logger = AndroidAgentLogger,
        linuxRootfsPath = AlpineEnvironmentPaths.rootfsDir(appContext).absolutePath,
        linuxSharedMountsProvider = { SharedFolderMounts.current() },
    )
    private val daemonSupervisor = DetachedTaskSupervisor(
        logger = AndroidAgentLogger,
        recordsFile = DetachedTaskSupervisor.defaultRecordsFile(appContext),
        linuxRootfsPath = AlpineEnvironmentPaths.rootfsDir(appContext).absolutePath,
        linuxSharedMountsProvider = { SharedFolderMounts.current() },
    )

    private val _uiState = MutableStateFlow(
        UserTerminalUiState(
            environment = defaultEnvironment(),
            linuxReady = isLinuxReady(),
        )
    )
    val uiState: StateFlow<UserTerminalUiState> = _uiState.asStateFlow()

    private var blockId = 0L
    private val outputBuffer = StringBuilder()
    private var lastFlushMs = 0L
    private var hadSession = false

    fun refreshLinuxReady() {
        _uiState.update { it.copy(linuxReady = isLinuxReady()) }
    }

    fun refreshDaemonTasks() {
        scope.launch {
            val statuses = withContext(Dispatchers.IO) { daemonSupervisor.list() }
            _uiState.update { state ->
                state.copy(daemonTasks = statuses.map(::toDaemonTaskUi))
            }
        }
    }

    fun stopDaemonTask(id: String) {
        scope.launch {
            withContext(Dispatchers.IO) { daemonSupervisor.stop(id) }
            refreshDaemonTasks()
        }
    }

    /** 读取守护任务日志尾部；失败时返回可展示的原因文本。 */
    suspend fun daemonLogs(id: String): String = withContext(Dispatchers.IO) {
        val result = daemonSupervisor.readLogs(id)
        when {
            result.ok && result.text.isBlank() -> appContext.getString(R.string.terminal_daemon_logs_empty)
            result.ok -> result.text.trimEnd()
            else -> result.message.ifBlank { appContext.getString(R.string.terminal_daemon_logs_empty) }
        }
    }

    fun send(rawCommand: String) {
        val command = rawCommand.trim()
        if (command.isEmpty()) return
        if (command.length > 16_000) return
        val state = _uiState.value
        if (state.running) return
        val environment = state.environment
        _uiState.update { it.copy(running = true) }
        scope.launch {
            if (controller.activeEnvironment != environment || !controller.isAlive) {
                val open = withContext(Dispatchers.IO) { controller.openSession(environment) }
                when (open) {
                    is UserTerminalController.OpenResult.Failed -> {
                        appendSystemBlock(open.message)
                        _uiState.update { it.copy(running = false, sessionAlive = false) }
                        return@launch
                    }
                    is UserTerminalController.OpenResult.Ready -> {
                        if (hadSession) {
                            appendSystemBlock(appContext.getString(R.string.terminal_session_restarted))
                        }
                        hadSession = true
                        _uiState.update { it.copy(sessionAlive = true, cwd = open.cwd) }
                    }
                }
            }
            val id = ++blockId
            appendBlock(
                TerminalBlockUi(
                    id = id,
                    command = command,
                    cwdAtStart = _uiState.value.cwd,
                    running = true,
                )
            )
            synchronized(outputBuffer) { outputBuffer.setLength(0) }
            lastFlushMs = 0L
            val result = withContext(Dispatchers.IO) {
                controller.exec(command) { text, _ -> onOutputDelta(id, text) }
            }
            flushOutput(id)
            finalizeBlock(id, result)
        }
    }

    fun stop() {
        if (!_uiState.value.running) return
        scope.launch(Dispatchers.IO) { controller.stopSession() }
    }

    fun switchEnvironment(environment: TerminalEnvironment) {
        val state = _uiState.value
        if (state.environment == environment) return
        scope.launch {
            withContext(Dispatchers.IO) { controller.stopSession() }
            _uiState.update {
                it.copy(environment = environment, sessionAlive = false, cwd = "")
            }
            val label = if (environment == TerminalEnvironment.LINUX) "Linux" else "Android"
            appendSystemBlock(appContext.getString(R.string.terminal_env_switched, label))
        }
    }

    fun close() {
        controller.close()
    }

    private fun onOutputDelta(blockId: Long, text: String) {
        val clean = ANSI_PATTERN.replace(text, "")
        if (clean.isEmpty()) return
        synchronized(outputBuffer) { outputBuffer.append(clean) }
        val now = System.currentTimeMillis()
        if (now - lastFlushMs >= FLUSH_INTERVAL_MS) {
            flushOutput(blockId)
        }
    }

    private fun flushOutput(blockId: Long) {
        val chunk = synchronized(outputBuffer) {
            if (outputBuffer.isEmpty()) null else outputBuffer.toString().also { outputBuffer.setLength(0) }
        } ?: return
        lastFlushMs = System.currentTimeMillis()
        _uiState.update { state ->
            state.copy(
                blocks = state.blocks.map { block ->
                    if (block.id != blockId) {
                        block
                    } else {
                        val combined = block.output + chunk
                        if (combined.length > MAX_BLOCK_OUTPUT_CHARS) {
                            block.copy(
                                output = combined.take(MAX_BLOCK_OUTPUT_CHARS),
                                truncated = true,
                            )
                        } else {
                            block.copy(output = combined)
                        }
                    }
                }
            )
        }
    }

    private fun finalizeBlock(blockId: Long, result: UserTerminalController.ExecResult) {
        _uiState.update { state ->
            state.copy(
                running = false,
                sessionAlive = !result.sessionClosed,
                cwd = result.cwd,
                blocks = state.blocks.map { block ->
                    if (block.id == blockId) {
                        block.copy(running = false, exitCode = result.exitCode)
                    } else {
                        block
                    }
                },
            )
        }
        if (result.sessionClosed) {
            appendSystemBlock(
                appContext.getString(
                    if (result.interrupted) R.string.terminal_interrupted else R.string.terminal_session_closed
                )
            )
        }
    }

    private fun appendBlock(block: TerminalBlockUi) {
        _uiState.update { state ->
            state.copy(blocks = (state.blocks + block).takeLast(MAX_BLOCKS))
        }
    }

    private fun appendSystemBlock(message: String) {
        appendBlock(
            TerminalBlockUi(
                id = ++blockId,
                isSystem = true,
                output = message,
            )
        )
    }

    private fun isLinuxReady(): Boolean =
        AlpineEnvironmentPaths.rootfsReady(AlpineEnvironmentPaths.rootfsDir(appContext).absolutePath)

    private fun defaultEnvironment(): TerminalEnvironment =
        if (isLinuxReady()) TerminalEnvironment.LINUX else TerminalEnvironment.ANDROID

    private fun toDaemonTaskUi(status: DetachedTaskStatus) = DaemonTaskUi(
        id = status.task.id,
        command = status.task.command,
        environment = status.task.environment,
        identity = status.task.identity,
        running = status.running,
        startedAt = status.task.startedAt,
    )
}
