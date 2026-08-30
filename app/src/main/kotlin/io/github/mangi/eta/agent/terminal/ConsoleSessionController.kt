package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.core.AgentLogger
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 控制台会话控制器：PTY 字节流原样透传，无状态行协议、无输出截断。
 *
 * 与 [UserTerminalController] 的差别在于交互模型：控制台面向全屏 TUI 与交互式 CLI，
 * 输入直接写 stdin（含方向键、Ctrl 组合键的转义字节），输出由调用方喂给
 * [TerminalScreenBuffer] 维护屏幕网格。同一时刻只保留一个控制台会话。
 */
internal class ConsoleSessionController(
    private val logger: AgentLogger,
    private val linuxRootfsPath: String? = null,
    private val linuxRootfsPathProvider: ((TerminalEnvironment) -> String?)? = null,
    private val processSupervisor: ShellProcessSupervisor = ShellProcessSupervisor(),
    private val linuxSharedMountsProvider: () -> List<SharedFolderMount> = { emptyList() },
) : AutoCloseable {

    private companion object {
        const val DEFAULT_ANDROID_CWD = "/data/local/tmp/eta"
    }

    sealed interface OpenResult {
        data object Ready : OpenResult
        data class Failed(val code: String, val message: String) : OpenResult
    }

    private val sessionLock = Any()
    private var session: PtySession? = null

    val isAlive: Boolean
        get() = synchronized(sessionLock) {
            session?.let { !it.closed && it.process.isAlive } == true
        }

    val activeEnvironment: TerminalEnvironment?
        get() = synchronized(sessionLock) {
            session?.takeIf { !it.closed && it.process.isAlive }?.environment
        }

    fun open(
        environment: TerminalEnvironment,
        cols: Int,
        rows: Int,
        onOutput: (ByteArray) -> Unit,
        onExit: () -> Unit,
    ): OpenResult {
        synchronized(sessionLock) {
            closeSessionLocked()
            val environmentRootfsPath = rootfsPath(environment)
            if (environment.isLinux &&
                !LinuxEnvironmentPaths.rootfsReady(environmentRootfsPath)
            ) {
                return OpenResult.Failed("LINUX_ENVIRONMENT_NOT_READY", "Linux 工具环境尚未安装")
            }
            val process = processSupervisor.startShellProcess(
                identity = "root",
                command = null,
                mergeStderr = true,
                environment = environment,
                linuxRootfsPath = environmentRootfsPath,
                linuxSharedMounts = if (environment.isLinux) {
                    linuxSharedMountsProvider()
                } else {
                    emptyList()
                },
                pty = true,
                ptyCols = cols,
                ptyRows = rows,
            ) ?: return OpenResult.Failed("PROCESS_START_FAILED", "无法启动控制台进程（缺少 BusyBox script？）")

            val newSession = PtySession(environment, process)
            session = newSession
            newSession.readerThread = thread(name = "console-pty-reader", isDaemon = true) {
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                try {
                    while (true) {
                        val read = process.inputStream.read(buffer)
                        if (read < 0) break
                        onOutput(buffer.copyOf(read))
                    }
                } catch (_: Exception) {
                    // 进程死亡或关闭时读端断开，交给 waiter 统一上报退出。
                }
            }
            newSession.waiterThread = thread(name = "console-pty-waiter", isDaemon = true) {
                runCatching { process.waitFor() }
                newSession.closed = true
                processSupervisor.retireExitedProcess(process)
                onExit()
            }
            // 落到环境默认工作目录；clear 清掉这条引导命令本身的回显。
            val defaultCwd = if (environment.isLinux) "/workspace" else DEFAULT_ANDROID_CWD
            runCatching {
                process.outputStream.write("mkdir -p $defaultCwd; cd $defaultCwd && clear\n".toByteArray(Charsets.UTF_8))
                process.outputStream.flush()
            }
            logger.info("Console action=open outcome=succeeded environment=${environment.wireName} cols=$cols rows=$rows")
            return OpenResult.Ready
        }
    }

    /** 向控制台写入输入字节（键盘文本、方向键/功能键转义序列）。会话不可用时静默丢弃。 */
    fun write(bytes: ByteArray) {
        val current = synchronized(sessionLock) { session } ?: return
        if (current.closed || !current.process.isAlive) return
        runCatching {
            synchronized(current.stdinLock) {
                current.process.outputStream.write(bytes)
                current.process.outputStream.flush()
            }
        }
    }

    fun write(text: String) = write(text.toByteArray(Charsets.UTF_8))

    override fun close() {
        processSupervisor.beginClosing()
        synchronized(sessionLock) {
            closeSessionLocked()
        }
    }

    private fun closeSessionLocked() {
        val current = session ?: return
        current.closed = true
        runCatching { current.process.outputStream.close() }
        processSupervisor.terminateAndReap(current.process)
        runCatching { current.readerThread.join(500) }
        runCatching { current.waiterThread.join(500) }
        processSupervisor.unregisterProcess(current.process)
        session = null
    }

    private fun rootfsPath(environment: TerminalEnvironment): String? =
        linuxRootfsPathProvider?.invoke(environment) ?: linuxRootfsPath

    private class PtySession(
        val environment: TerminalEnvironment,
        val process: Process,
    ) {
        val stdinLock = Any()

        @Volatile
        var closed: Boolean = false

        lateinit var readerThread: Thread
        lateinit var waiterThread: Thread
    }
}
