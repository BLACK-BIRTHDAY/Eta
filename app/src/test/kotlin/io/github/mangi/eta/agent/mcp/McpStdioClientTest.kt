package io.github.mangi.eta.agent.mcp

import io.github.mangi.eta.data.model.McpProtocolMode
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpStdioEnvironment
import io.github.mangi.eta.data.model.McpTransportType
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpStdioClientTest {
    @Test
    fun stdioClientHandshakesDiscoversAndCallsToolsOverStreams() {
        val clientToServer = PipedOutputStream()
        val serverInput = PipedInputStream(clientToServer, 64 * 1024)
        val serverToClient = PipedOutputStream()
        val clientInput = PipedInputStream(serverToClient, 64 * 1024)
        val closedFlag = AtomicBoolean(false)
        val receivedMethods = CopyOnWriteArrayList<String>()

        val serverThread = thread(name = "fake-stdio-mcp-server", isDaemon = true) {
            val writer = serverToClient.bufferedWriter(Charsets.UTF_8)
            serverInput.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    if (line.isBlank()) return@forEach
                    val msg = JSONObject(line)
                    val method = msg.optString("method")
                    receivedMethods += method
                    when (method) {
                        "initialize" -> {
                            val id = msg.getLong("id")
                            // 模拟子进程在 stdout 打印非 JSON 杂音，验证客户端抗干扰能力
                            writer.write("Starting MCP server v1.0...\n")
                            writer.flush()
                            val reply = JSONObject()
                                .put("jsonrpc", "2.0")
                                .put("id", id)
                                .put(
                                    "result",
                                    JSONObject()
                                        .put("protocolVersion", "2024-11-05")
                                        .put("capabilities", JSONObject())
                                        .put("serverInfo", JSONObject().put("name", "mock-stdio").put("version", "1.0")),
                                )
                            writer.write(reply.toString() + "\n")
                            writer.flush()
                        }

                        "notifications/initialized" -> {
                            // 服务端主动发一个 ping 请求，测试客户端自动响应
                            val ping = JSONObject()
                                .put("jsonrpc", "2.0")
                                .put("id", "srv-ping-1")
                                .put("method", "ping")
                            writer.write(ping.toString() + "\n")
                            writer.flush()
                        }

                        "tools/list" -> {
                            val id = msg.getLong("id")
                            val reply = JSONObject(
                                """{"jsonrpc":"2.0","id":$id,"result":{"tools":[{"name":"read_file","title":"Read File","description":"Read file from disk","inputSchema":{"type":"object","properties":{"path":{"type":"string"}}},"annotations":{"readOnlyHint":true}}]}}"""
                            )
                            writer.write(reply.toString() + "\n")
                            writer.flush()
                        }

                        "tools/call" -> {
                            val id = msg.getLong("id")
                            val params = msg.getJSONObject("params")
                            val path = params.getJSONObject("arguments").optString("path")
                            val reply = JSONObject(
                                """{"jsonrpc":"2.0","id":$id,"result":{"content":[{"type":"text","text":"content of $path"}]}}"""
                            )
                            writer.write(reply.toString() + "\n")
                            writer.flush()
                        }
                    }
                }
            }
        }

        val setting = McpServerSetting(
            id = "stdio-1",
            name = "Filesystem",
            transportType = McpTransportType.STDIO,
            command = "npx",
            args = listOf("-y", "@modelcontextprotocol/server-filesystem", "/workspace"),
            stdioEnvironment = McpStdioEnvironment.LINUX,
        )

        val handle = object : McpStdioClient.StdioProcessHandle {
            override val inputStream: InputStream = clientInput
            override val outputStream: OutputStream = clientToServer
            override val errorStream: InputStream = ByteArrayInputStream(ByteArray(0))
            override val isAlive: Boolean get() = !closedFlag.get()
            override fun close() {
                closedFlag.set(true)
                runCatching { clientToServer.close() }
                runCatching { serverToClient.close() }
                runCatching { clientInput.close() }
                runCatching { serverInput.close() }
            }
        }

        McpStdioClient(
            server = setting,
            envVars = mapOf("NODE_ENV" to "production"),
            processFactory = { handle },
        ).use { client ->
            val discovery = client.discoverTools()
            assertEquals("2024-11-05", discovery.protocolVersion)
            assertEquals(1, discovery.tools.size)
            val tool = discovery.tools.single()
            assertEquals("read_file", tool.name)
            assertEquals("Read File", tool.title)
            assertEquals(true, tool.readOnlyHint)

            val callResult = client.callTool(tool, JSONObject().put("path", "/workspace/README.md"))
            val text = callResult.getJSONArray("content").getJSONObject(0).getString("text")
            assertEquals("content of /workspace/README.md", text)
        }

        assertTrue(closedFlag.get())
        serverThread.join(1_000)
        assertTrue(receivedMethods.containsAll(listOf("initialize", "notifications/initialized", "tools/list", "tools/call")))
    }

    @Test
    fun buildStdioShellCommandQuotesArgumentsAndExportsEnvSafely() {
        val setting = McpServerSetting(
            id = "s1",
            name = "Git",
            transportType = McpTransportType.STDIO,
            command = "uvx",
            args = listOf("mcp-server-git", "--repository", "/workspace/my repo"),
            cwd = "/workspace",
        )
        val cmd = McpStdioClient.buildStdioShellCommand(
            server = setting,
            envVars = mapOf("GITHUB_TOKEN" to "ghp_123'456", "DEBUG" to "1"),
        )

        assertEquals(
            "cd '/workspace' && export GITHUB_TOKEN='ghp_123'\\''456' && export DEBUG='1' && exec 'uvx' 'mcp-server-git' '--repository' '/workspace/my repo'",
            cmd,
        )
    }

    @Test
    fun splitCommandLineArgsHandlesQuotesAndEscapes() {
        val args = McpStdioClient.splitCommandLineArgs(
            """-y @modelcontextprotocol/server-everything "/path/with spaces/dir" 'single quoted' escaped\ arg"""
        )
        assertEquals(
            listOf(
                "-y",
                "@modelcontextprotocol/server-everything",
                "/path/with spaces/dir",
                "single quoted",
                "escaped arg",
            ),
            args,
        )
    }

    @Test
    fun jsonParserSupportsStandardMcpServersAndSingleServerDefinitions() {
        val raw = """
            {
              "mcpServers": {
                "filesystem": {
                  "command": "npx",
                  "args": ["-y", "@modelcontextprotocol/server-filesystem", "/workspace"],
                  "env": {
                    "NODE_OPTIONS": "--max-old-space-size=512"
                  }
                },
                "remote-api": {
                  "url": "https://example.com/mcp",
                  "headers": {
                    "Authorization": "Bearer secret-jwt"
                  }
                }
              }
            }
        """.trimIndent()

        val drafts = McpConfigJsonParser.parse(raw, defaultStdioEnvironment = McpStdioEnvironment.ANDROID_ROOT)
        assertEquals(2, drafts.size)

        val stdioDraft = drafts[0]
        assertEquals("filesystem", stdioDraft.setting.name)
        assertEquals(McpTransportType.STDIO, stdioDraft.setting.transportType)
        assertEquals("npx", stdioDraft.setting.command)
        assertEquals(listOf("-y", "@modelcontextprotocol/server-filesystem", "/workspace"), stdioDraft.setting.args)
        assertEquals(McpStdioEnvironment.ANDROID_ROOT, stdioDraft.setting.stdioEnvironment)
        assertEquals(mapOf("NODE_OPTIONS" to "--max-old-space-size=512"), stdioDraft.envVars)

        val httpDraft = drafts[1]
        assertEquals("remote-api", httpDraft.setting.name)
        assertEquals(McpTransportType.HTTP, httpDraft.setting.transportType)
        assertEquals("https://example.com/mcp", httpDraft.setting.url)
        assertEquals("secret-jwt", httpDraft.bearerToken)
        assertEquals(McpProtocolMode.AUTO, httpDraft.setting.protocolMode)
    }
}
