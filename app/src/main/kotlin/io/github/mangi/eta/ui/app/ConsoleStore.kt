package io.github.mangi.eta.ui.app

import android.content.Context
import androidx.compose.runtime.Immutable
import io.github.mangi.eta.agent.terminal.AlpineEnvironmentPaths
import io.github.mangi.eta.agent.terminal.ConsoleSessionController
import io.github.mangi.eta.agent.terminal.SharedFolderMounts
import io.github.mangi.eta.agent.terminal.ShellProcessSupervisor
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import io.github.mangi.eta.agent.terminal.TerminalScreenBuffer
import io.github.mangi.eta.agent.terminal.ptySupported
import io.github.mangi.eta.core.AndroidAgentLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 控制台一帧画面：行对象身份稳定，渲染层按 Line.id 复用、按 Line.version 重排。 */
@Immutable
internal data class ConsoleFrame(
    val lines: List<TerminalScreenBuffer.Line> = emptyList(),
    val screenRows: Int = 0,
    val cursorRow: Int = 0,
    val cursorCol: Int = 0,
    val cursorVisible: Boolean = true,
)

@Immutable
internal data class ConsoleUiState(
    val environment: TerminalEnvironment = TerminalEnvironment.LINUX,
    val connected: Boolean = false,
    val exited: Boolean = false,
    /** null = 探测中；false 时入口回退到块式终端。 */
    val ptySupported: Boolean? = null,
    val failMessage: String? = null,
    val frame: ConsoleFrame = ConsoleFrame(),
)

/**
 * 控制台页面的 App 级状态所有者：持有 PTY 会话与屏幕缓冲区，
 * 字节流在 IO 线程喂入 [TerminalScreenBuffer]，按节流节奏向 UI 发布帧。
 * 离开页面（close）即终止会话——控制台不是后台任务容器，长驻服务应走守护任务。
 */
internal class ConsoleStore(
    context: Context,
    private val scope: CoroutineScope,
) {
    private companion object {
        const val FLUSH_INTERVAL_MS = 50L
        const val SCROLLBACK_LINES = 500
    }

    private val appContext = context.applicationContext
    private val linuxRootfsPath = AlpineEnvironmentPaths.rootfsDir(appContext).absolutePath
    private val controller = ConsoleSessionController(
        logger = AndroidAgentLogger,
        linuxRootfsPath = linuxRootfsPath,
        linuxSharedMountsProvider = { SharedFolderMounts.current() },
    )
    private val bufferLock = Any()

    /** 屏幕缓冲区；写入只能在 IO 线程持锁进行，UI 线程持锁读快照。 */
    private var buffer: TerminalScreenBuffer? = null

    private val _uiState = MutableStateFlow(
        ConsoleUiState(
            environment = if (AlpineEnvironmentPaths.rootfsReady(linuxRootfsPath)) {
                TerminalEnvironment.LINUX
            } else {
                TerminalEnvironment.ANDROID
            },
        )
    )
    val uiState: StateFlow<ConsoleUiState> = _uiState.asStateFlow()

    private var lastFlushMs = 0L
    private var flushScheduled = false
    private var lastCols = 0
    private var lastRows = 0

    /** 探测 PTY 前提；结果缓存进状态，不支持时 UI 提供回退入口。 */
    fun probePtySupport() {
        scope.launch {
            val supported = withContext(Dispatchers.IO) { ptySupported(ShellProcessSupervisor()) }
            _uiState.update { it.copy(ptySupported = supported) }
        }
    }

    fun open(environment: TerminalEnvironment, cols: Int, rows: Int) {
        if (cols <= 0 || rows <= 0) return
        if (controller.activeEnvironment == environment && controller.isAlive) return
        lastCols = cols
        lastRows = rows
        synchronized(bufferLock) {
            buffer = TerminalScreenBuffer(cols, rows, SCROLLBACK_LINES)
        }
        _uiState.update { it.copy(connected = false, exited = false, failMessage = null, frame = ConsoleFrame()) }
        scope.launch(Dispatchers.IO) {
            val result = controller.open(
                environment = environment,
                cols = cols,
                rows = rows,
                onOutput = ::onOutput,
                onExit = ::onExit,
            )
            when (result) {
                ConsoleSessionController.OpenResult.Ready ->
                    _uiState.update { it.copy(connected = true, environment = environment) }
                is ConsoleSessionController.OpenResult.Failed ->
                    _uiState.update { it.copy(connected = false, failMessage = result.message) }
            }
        }
    }

    /** 切换环境：关闭当前会话并以最近一次的网格尺寸重开。 */
    fun switchEnvironment(environment: TerminalEnvironment) {
        if (_uiState.value.environment == environment && controller.isAlive) return
        if (lastCols <= 0 || lastRows <= 0) return
        scope.launch(Dispatchers.IO) { controller.close() }
        _uiState.update { it.copy(environment = environment, connected = false, exited = false) }
        open(environment, lastCols, lastRows)
    }

    /** 断开后以同一环境与网格尺寸重连。 */
    fun reconnect() {
        if (lastCols <= 0 || lastRows <= 0) return
        scope.launch(Dispatchers.IO) { controller.close() }
        open(_uiState.value.environment, lastCols, lastRows)
    }

    fun write(text: String) {
        scope.launch(Dispatchers.IO) { controller.write(text) }
    }

    fun close() {
        controller.close()
    }

    private fun onOutput(chunk: ByteArray) {
        synchronized(bufferLock) {
            buffer?.process(String(chunk, Charsets.UTF_8))
        }
        val now = System.currentTimeMillis()
        if (now - lastFlushMs >= FLUSH_INTERVAL_MS) {
            flushFrame()
        } else {
            scheduleFlush()
        }
    }

    private fun scheduleFlush() {
        if (flushScheduled) return
        flushScheduled = true
        scope.launch {
            delay(FLUSH_INTERVAL_MS)
            flushScheduled = false
            flushFrame()
        }
    }

    private fun flushFrame() {
        val frame = synchronized(bufferLock) {
            val current = buffer ?: return
            ConsoleFrame(
                lines = current.lines(),
                screenRows = current.rows,
                cursorRow = current.cursorRow,
                cursorCol = current.cursorCol,
                cursorVisible = current.cursorVisible,
            )
        }
        lastFlushMs = System.currentTimeMillis()
        _uiState.update { it.copy(frame = frame) }
    }

    private fun onExit() {
        flushFrame()
        _uiState.update { it.copy(connected = false, exited = true) }
    }
}
