package io.github.mangi.eta.agent.mcp

import android.content.Context
import io.github.mangi.eta.agent.terminal.LinuxDistribution
import io.github.mangi.eta.agent.terminal.LinuxEnvironmentPaths
import io.github.mangi.eta.agent.terminal.LinuxExecutionBackend
import io.github.mangi.eta.agent.terminal.SharedFolderMounts
import io.github.mangi.eta.agent.terminal.ShellProcessSupervisor
import io.github.mangi.eta.agent.terminal.TerminalEnvironment
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.agent.terminal.isLinux
import io.github.mangi.eta.agent.terminal.shellQuote
import io.github.mangi.eta.agent.terminal.terminalEnvironment
import io.github.mangi.eta.data.model.McpProtocolMode
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpStdioEnvironment
import io.github.mangi.eta.data.model.McpToolDefinition
import io.github.mangi.eta.data.repository.LinuxEnvironmentSettingsRepository
import io.github.mangi.eta.data.repository.McpServerRepository
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.buffer
import okio.sink
import okio.source
import org.json.JSONArray
import org.json.JSONObject

/**
 * MCP 客户端统一契约：屏蔽 HTTP/SSE 与本地 Stdio 管道子进程的传输差异。
 */
internal interface McpClient : AutoCloseable {
    data class Discovery(
        val protocolVersion: String,
        val tools: List<McpToolDefinition>,
        val cacheTtlMs: Long?,
    )

    fun discoverTools(): Discovery

    fun callTool(tool: McpToolDefinition, arguments: JSONObject): JSONObject
}

/**
 * 基于标准输入输出 (stdin/stdout) 换行分隔 JSON-RPC 2.0 协议的本地 MCP 传输客户端。
 *
 * 架构对齐官方 Kotlin MCP SDK `StdioClientTransport`：
 * - stdin/stdout 严格承载 UTF-8 NDJSON 帧（禁止子进程日志污染 stdout，日志统一由 stderr 环形缓冲采集）；
 * - 独立后台读取协程/线程按 `id` 路由响应，自动忽略通知帧并在服务端发起请求时回复 `-32601 Method not found`；
 * - 进程生命周期委托给 [ShellProcessSupervisor]，支持 Linux 容器（Alpine/Debian）、Root Shell 与 App Shell，退出或超时时零残留回收整棵进程组。
 */
internal class McpStdioClient(
    private val server: McpServerSetting,
    private val envVars: Map<String, String> = emptyMap(),
    private val processFactory: (() -> StdioProcessHandle)? = null,
    private val callTimeoutMs: Long = DEFAULT_CALL_TIMEOUT_MS,
) : McpClient {

    internal interface StdioProcessHandle : AutoCloseable {
        val inputStream: InputStream
        val outputStream: OutputStream
        val errorStream: InputStream
        val isAlive: Boolean
    }

    private class PendingRequest {
        val latch = CountDownLatch(1)
        val response = AtomicReference<JSONObject?>()
        val failure = AtomicReference<Throwable?>()

        fun complete(json: JSONObject) {
            response.set(json)
            latch.countDown()
        }

        fun completeWithError(error: Throwable) {
            failure.set(error)
            latch.countDown()
        }
    }

    private val requestIds = AtomicLong(1)
    private val pendingRequests = ConcurrentHashMap<String, PendingRequest>()
    private val writeLock = Any()
    private val lifecycleLock = Any()
    private val closed = AtomicBoolean(false)

    private var processHandle: StdioProcessHandle? = null
    private var sink: BufferedSink? = null
    private var stdoutThread: Thread? = null
    private var stderrThread: Thread? = null
    private val stderrTail = ArrayDeque<String>()

    @Volatile
    private var negotiatedVersion: String? = null

    @Volatile
    private var transportFailure: Throwable? = null

    override fun discoverTools(): McpClient.Discovery {
        val protocolVersion = ensureInitialized()
        val tools = listTools()
        return McpClient.Discovery(
            protocolVersion = protocolVersion,
            tools = tools,
            cacheTtlMs = null,
        )
    }

    override fun callTool(tool: McpToolDefinition, arguments: JSONObject): JSONObject {
        ensureInitialized()
        val response = request(
            method = "tools/call",
            params = JSONObject()
                .put("name", tool.name)
                .put("arguments", arguments),
        )
        return unwrapResult(response)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val handle = synchronized(lifecycleLock) {
            val current = processHandle
            processHandle = null
            sink = null
            current
        }
        val closeException = IOException("MCP Stdio 客户端已关闭")
        pendingRequests.values.forEach { it.completeWithError(closeException) }
        pendingRequests.clear()
        runCatching { handle?.close() }
        runCatching { stdoutThread?.join(300) }
        runCatching { stderrThread?.join(300) }
    }

    private fun ensureInitialized(): String {
        negotiatedVersion?.let { return it }
        synchronized(lifecycleLock) {
            negotiatedVersion?.let { return it }
            check(!closed.get()) { "MCP 客户端已关闭" }
            startProcessAndLoopsLocked()
            val response = request(
                method = "initialize",
                params = JSONObject()
                    .put("protocolVersion", McpProtocolMode.LEGACY)
                    .put("capabilities", JSONObject())
                    .put(
                        "clientInfo",
                        JSONObject().put("name", "Eta").put("version", CLIENT_VERSION),
                    ),
            )
            val result = unwrapResult(response)
            val serverVersion = result.optString("protocolVersion").trim()
            val resolvedVersion = serverVersion.ifBlank { McpProtocolMode.LEGACY }
            notification(
                method = "notifications/initialized",
                params = JSONObject(),
            )
            negotiatedVersion = resolvedVersion
            return resolvedVersion
        }
    }

    private fun startProcessAndLoopsLocked() {
        if (processHandle != null) return
        val handle = (processFactory ?: { startManagedShellProcess() }).invoke()
        processHandle = handle
        sink = handle.outputStream.sink().buffer()

        stderrThread = thread(
            name = "mcp-stdio-stderr-${server.id.take(8)}",
            isDaemon = true,
        ) {
            runCatching {
                handle.errorStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty()) {
                            synchronized(stderrTail) {
                                if (stderrTail.size >= MAX_STDERR_LINES) {
                                    stderrTail.removeFirst()
                                }
                                stderrTail.addLast(trimmed.take(MAX_STDERR_LINE_CHARS))
                            }
                        }
                    }
                }
            }
        }

        stdoutThread = thread(
            name = "mcp-stdio-stdout-${server.id.take(8)}",
            isDaemon = true,
        ) {
            val source = handle.inputStream.source().buffer()
            try {
                while (!closed.get()) {
                    val line = source.readBoundedUtf8Line(MAX_FRAME_BYTES) ?: break
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || !trimmed.startsWith("{")) continue
                    val json = runCatching { JSONObject(trimmed) }.getOrNull() ?: continue
                    dispatchIncomingMessage(json)
                }
                if (!closed.get()) {
                    val reason = buildExitSummary("MCP Stdio 进程已退出")
                    failAllPending(IOException(reason))
                }
            } catch (failure: Throwable) {
                if (!closed.get()) {
                    val reason = buildExitSummary(failure.message ?: "MCP Stdio 读取异常")
                    failAllPending(IOException(reason, failure))
                }
            } finally {
                runCatching { source.close() }
            }
        }
    }

    private fun dispatchIncomingMessage(json: JSONObject) {
        val hasId = json.has("id") && !json.isNull("id")
        val hasMethod = json.has("method") && !json.isNull("method")

        if (hasId && !hasMethod) {
            // 服务端对客户端请求的响应
            val idKey = json.opt("id")?.toString() ?: return
            pendingRequests.remove(idKey)?.complete(json)
            return
        }

        if (hasId && hasMethod) {
            // 服务端向客户端发起的双向请求（例如 ping 或未实现的 sampling/roots）
            val requestId = json.opt("id")
            val method = json.optString("method")
            if (method == "ping") {
                sendRawFrame(
                    JSONObject()
                        .put("jsonrpc", "2.0")
                        .put("id", requestId)
                        .put("result", JSONObject())
                )
            } else {
                sendRawFrame(
                    JSONObject()
                        .put("jsonrpc", "2.0")
                        .put("id", requestId)
                        .put(
                            "error",
                            JSONObject()
                                .put("code", -32601)
                                .put("message", "Method not found: $method"),
                        )
                )
            }
        }
        // notifications/* 直接忽略
    }

    private fun listTools(): List<McpToolDefinition> {
        val tools = mutableListOf<McpToolDefinition>()
        var cursor: String? = null
        repeat(MAX_LIST_PAGES) {
            val params = JSONObject()
            cursor?.let { params.put("cursor", it) }
            val response = request(method = "tools/list", params = params)
            val result = unwrapResult(response)
            val page = result.optJSONArray("tools") ?: JSONArray()
            for (index in 0 until page.length()) {
                if (tools.size >= MAX_DISCOVERED_TOOLS) break
                page.optJSONObject(index)?.let { raw ->
                    val parsed = parseMcpToolDefinition(raw)
                    if (parsed.name.isNotBlank()) {
                        tools += parsed
                    }
                }
            }
            cursor = result.optString("nextCursor").takeIf { it.isNotBlank() }
            if (cursor == null || tools.size >= MAX_DISCOVERED_TOOLS) {
                return tools
            }
        }
        return tools
    }

    private fun request(method: String, params: JSONObject): JSONObject {
        check(!closed.get()) { "MCP 客户端已关闭" }
        transportFailure?.let { throw it }

        val id = requestIds.getAndIncrement()
        val idKey = id.toString()
        val pending = PendingRequest()
        pendingRequests[idKey] = pending

        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", params)

        try {
            writeFrame(payload)
        } catch (failure: Throwable) {
            pendingRequests.remove(idKey)
            throw IOException(buildExitSummary(failure.message ?: "MCP Stdio 写入失败"), failure)
        }

        val completed = try {
            pending.latch.await(callTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (interrupted: InterruptedException) {
            pendingRequests.remove(idKey)
            Thread.currentThread().interrupt()
            throw IOException("MCP Stdio 请求被中断", interrupted)
        }

        if (!completed) {
            pendingRequests.remove(idKey)
            throw IOException(buildExitSummary("MCP Stdio 响应超时（${callTimeoutMs / 1000}s）：$method"))
        }

        pending.failure.get()?.let { throw it }
        val response = pending.response.get()
            ?: throw IOException(buildExitSummary("MCP Stdio 响应为空"))

        response.optJSONObject("error")?.let { error ->
            throw McpJsonRpcException(
                code = error.optInt("code"),
                safeMessage = error.optString("message").take(MAX_ERROR_CHARS),
            )
        }
        return response
    }

    private fun notification(method: String, params: JSONObject) {
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", method)
            .put("params", params)
        writeFrame(payload)
    }

    private fun sendRawFrame(payload: JSONObject) {
        runCatching { writeFrame(payload) }
    }

    private fun writeFrame(payload: JSONObject) {
        val serialized = payload.toString()
        require('\n' !in serialized && '\r' !in serialized) {
            "MCP Stdio 消息不能包含未转义换行符"
        }
        synchronized(writeLock) {
            val activeSink = sink ?: throw IOException(buildExitSummary("MCP Stdio 管道未就绪"))
            activeSink.writeUtf8(serialized)
            activeSink.writeByte('\n'.code)
            activeSink.flush()
        }
    }

    private fun failAllPending(error: Throwable) {
        transportFailure = error
        val snapshot = pendingRequests.keys.toList()
        snapshot.forEach { key ->
            pendingRequests.remove(key)?.completeWithError(error)
        }
    }

    private fun buildExitSummary(prefix: String): String {
        val stderrText = synchronized(stderrTail) {
            stderrTail.joinToString(" | ")
        }
        return if (stderrText.isBlank()) {
            prefix
        } else {
            "$prefix（stderr: ${stderrText.take(MAX_STDERR_SUMMARY_CHARS)}）"
        }
    }

    private fun unwrapResult(response: JSONObject): JSONObject =
        response.optJSONObject("result") ?: error("MCP 响应缺少 result")

    private fun startManagedShellProcess(): StdioProcessHandle {
        val commandLine = buildStdioShellCommand(server, envVars)
        val context = McpServerRepository.appContextOrNull()
        val supervisor = ShellProcessSupervisor()

        val (environment, identity, rootfsPath) = resolveExecutionTarget(context, server.stdioEnvironment)
        val sharedMounts = if (environment.isLinux) SharedFolderMounts.current() else emptyList()

        val process = supervisor.startShellProcess(
            identity = identity,
            command = commandLine,
            mergeStderr = false,
            environment = environment,
            linuxRootfsPath = rootfsPath,
            linuxSharedMounts = sharedMounts,
            pty = false,
        ) ?: run {
            supervisor.beginClosing()
            throw IOException("无法在 ${server.stdioEnvironment} 环境中启动 MCP Stdio 进程")
        }

        return object : StdioProcessHandle {
            override val inputStream: InputStream get() = process.inputStream
            override val outputStream: OutputStream get() = process.outputStream
            override val errorStream: InputStream get() = process.errorStream
            override val isAlive: Boolean get() = process.isAlive

            override fun close() {
                supervisor.beginClosing()
                supervisor.terminateAndReap(process)
                supervisor.unregisterProcess(process)
            }
        }
    }

    private fun resolveExecutionTarget(
        context: Context?,
        stdioEnv: String,
    ): Triple<TerminalEnvironment, String, String?> {
        return when (stdioEnv) {
            McpStdioEnvironment.ANDROID_ROOT -> {
                check(TerminalRuntime.rootAvailable) { "MCP Stdio 配置为 Root Shell，但当前未授予 Root 权限" }
                Triple(TerminalEnvironment.ANDROID, "root", null)
            }
            McpStdioEnvironment.ANDROID_USER -> {
                Triple(TerminalEnvironment.ANDROID, "user", null)
            }
            else -> {
                val appContext = checkNotNull(context) { "应用上下文未初始化，无法解析 Linux 容器路径" }
                val selectedDistro = LinuxEnvironmentSettingsRepository.current(appContext)
                val selectedRootfs = LinuxEnvironmentPaths.rootfsDir(appContext, selectedDistro).absolutePath
                val (distro, rootfs) = if (LinuxEnvironmentPaths.rootfsReady(selectedRootfs)) {
                    selectedDistro to selectedRootfs
                } else {
                    val fallbackDistro = LinuxDistribution.entries.firstOrNull { candidate ->
                        LinuxEnvironmentPaths.rootfsReady(
                            LinuxEnvironmentPaths.rootfsDir(appContext, candidate).absolutePath
                        )
                    } ?: throw IOException("Linux 工具环境尚未安装就绪，请先在「终端与 Linux 环境」中完成安装，或将运行环境改为 Android Shell")
                    fallbackDistro to LinuxEnvironmentPaths.rootfsDir(appContext, fallbackDistro).absolutePath
                }
                val env = distro.terminalEnvironment
                val backend = LinuxEnvironmentPaths.backendOf(rootfs)
                val identity = if (backend == LinuxExecutionBackend.PROOT) "user" else "root"
                if (identity == "root" && !TerminalRuntime.rootAvailable) {
                    throw IOException("当前 Linux 容器 (${distro.wireName}/chroot) 需要 Root 权限")
                }
                Triple(env, identity, rootfs)
            }
        }
    }

    companion object {
        const val CLIENT_VERSION = "1"
        const val DEFAULT_CALL_TIMEOUT_MS = 60_000L
        const val MAX_FRAME_BYTES = 1_048_576L
        const val MAX_ERROR_CHARS = 200
        const val MAX_STDERR_LINES = 12
        const val MAX_STDERR_LINE_CHARS = 240
        const val MAX_STDERR_SUMMARY_CHARS = 320
        const val MAX_DISCOVERED_TOOLS = 128
        const val MAX_LIST_PAGES = 8

        private val ENV_NAME_REGEX = Regex("[A-Za-z_][A-Za-z0-9_]*")

        internal fun validateStdioCommand(command: String): String {
            val trimmed = command.trim()
            require(trimmed.isNotBlank()) { "请输入启动命令（例如 npx、uvx、python3 或可执行文件路径）" }
            require('\u0000' !in trimmed) { "启动命令包含非法空字符" }
            return trimmed
        }

        internal fun buildStdioShellCommand(
            server: McpServerSetting,
            envVars: Map<String, String>,
        ): String {
            val executable = validateStdioCommand(server.command)
            val statements = mutableListOf<String>()

            val targetCwd = server.cwd.trim()
            if (targetCwd.isNotEmpty()) {
                require('\u0000' !in targetCwd) { "工作目录包含非法字符" }
                statements += "cd ${shellQuote(targetCwd)}"
            }

            envVars.forEach { (rawKey, value) ->
                val key = rawKey.trim()
                if (key.isNotEmpty()) {
                    require(ENV_NAME_REGEX.matches(key)) { "环境变量名不合法：$key" }
                    statements += "export $key=${shellQuote(value)}"
                }
            }

            val tokens = buildList {
                if (server.args.isEmpty() && executable.any(Char::isWhitespace)) {
                    // 若用户将完整命令及参数直接填在 command 字段且未填 args，则使用语义拆词
                    addAll(splitCommandLineArgs(executable))
                } else {
                    add(executable)
                    addAll(server.args)
                }
            }
            require(tokens.isNotEmpty()) { "启动命令不能为空" }
            val execLine = "exec " + tokens.joinToString(" ") { shellQuote(it) }
            statements += execLine
            return statements.joinToString(" && ")
        }

        /**
         * 支持单引号、双引号与反斜杠转义的参数分词器。
         */
        internal fun splitCommandLineArgs(raw: String): List<String> {
            val result = mutableListOf<String>()
            val current = StringBuilder()
            var inSingleQuote = false
            var inDoubleQuote = false
            var escaping = false
            var tokenStarted = false

            for (ch in raw.trim()) {
                if (escaping) {
                    current.append(ch)
                    escaping = false
                    tokenStarted = true
                    continue
                }
                when {
                    ch == '\\' && !inSingleQuote -> {
                        escaping = true
                        tokenStarted = true
                    }
                    ch == '\'' && !inDoubleQuote -> {
                        inSingleQuote = !inSingleQuote
                        tokenStarted = true
                    }
                    ch == '"' && !inSingleQuote -> {
                        inDoubleQuote = !inDoubleQuote
                        tokenStarted = true
                    }
                    ch.isWhitespace() && !inSingleQuote && !inDoubleQuote -> {
                        if (tokenStarted) {
                            result += current.toString()
                            current.setLength(0)
                            tokenStarted = false
                        }
                    }
                    else -> {
                        current.append(ch)
                        tokenStarted = true
                    }
                }
            }
            if (escaping) current.append('\\')
            if (tokenStarted) result += current.toString()
            return result
        }
    }
}

private fun BufferedSource.readBoundedUtf8Line(maxBytes: Long): String? {
    val newline = indexOf('\n'.code.toByte(), 0L, maxBytes + 1L)
    if (newline >= 0L) {
        val line = readUtf8(newline)
        skip(1L)
        return line.removeSuffix("\r")
    }
    if (request(maxBytes + 1L)) {
        throw IOException("MCP Stdio 单行帧超过大小限制")
    }
    if (exhausted()) return null
    val tail = readUtf8().removeSuffix("\r")
    return tail.ifEmpty { null }
}

internal fun parseMcpToolDefinition(source: JSONObject): McpToolDefinition {
    val name = source.optString("name").trim()
    val schema = source.optJSONObject("inputSchema")
    val annotations = source.optJSONObject("annotations")
    return McpToolDefinition(
        name = name,
        title = source.optString("title").ifBlank {
            annotations?.optString("title").orEmpty()
        }.take(160),
        description = source.optString("description").take(2_000),
        inputSchemaJson = (schema ?: JSONObject()).toString(),
        readOnlyHint = annotations?.optNullableBoolean("readOnlyHint"),
        destructiveHint = annotations?.optNullableBoolean("destructiveHint"),
        idempotentHint = annotations?.optNullableBoolean("idempotentHint"),
        openWorldHint = annotations?.optNullableBoolean("openWorldHint"),
    )
}

private fun JSONObject.optNullableBoolean(key: String): Boolean? =
    if (has(key) && !isNull(key)) optBoolean(key) else null
