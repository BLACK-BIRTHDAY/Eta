package fuck.andes.agent.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import fuck.andes.data.model.McpProtocolMode
import fuck.andes.data.model.McpServerSetting
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class McpProtocolValidationTest {
    @Test
    fun modernDiscoveryUsesStatelessEnvelopeAndRoutingHeaders() {
        val requestBody = AtomicReference<String>()
        val methodHeader = AtomicReference<String>()
        val server = localServer { exchange ->
            requestBody.set(exchange.requestBody.bufferedReader().use { it.readText() })
            methodHeader.set(exchange.requestHeaders.getFirst("Mcp-Method"))
            exchange.respond(
                200,
                """{"jsonrpc":"2.0","id":1,"result":{"resultType":"complete","ttlMs":30000,"tools":[{"name":"search","inputSchema":{"type":"object"}}]}}""",
            )
        }
        try {
            val configured = McpServerSetting(id = "test", name = "Test", url = server.url())
            val discovery = McpHttpClient(configured, bearerToken = null).use { it.discoverTools() }

            assertEquals(McpProtocolMode.LATEST, discovery.protocolVersion)
            assertEquals(30_000L, discovery.cacheTtlMs)
            assertEquals(listOf("search"), discovery.tools.map { it.name })
            assertEquals("tools/list", methodHeader.get())
            val body = JSONObject(requestBody.get())
            assertEquals("tools/list", body.getString("method"))
            assertEquals(
                McpProtocolMode.LATEST,
                body.getJSONObject("params").getJSONObject("_meta")
                    .getString("io.modelcontextprotocol/protocolVersion"),
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun autoModeFallsBackToLegacySessionHandshake() {
        val requestIndex = AtomicInteger()
        val requestBodies = CopyOnWriteArrayList<String>()
        val deleteReceived = CountDownLatch(1)
        val server = localServer { exchange ->
            if (exchange.requestMethod == "DELETE") {
                deleteReceived.countDown()
                exchange.respond(200, "")
                return@localServer
            }
            requestBodies += exchange.requestBody.bufferedReader().use { it.readText() }
            when (requestIndex.getAndIncrement()) {
                0 -> exchange.respond(400, "bad protocol")
                1 -> exchange.respond(
                    200,
                    """{"jsonrpc":"2.0","id":2,"result":{"protocolVersion":"2025-11-25","capabilities":{},"serverInfo":{"name":"test","version":"1"}}}""",
                    mapOf("Mcp-Session-Id" to "session-1"),
                )
                2 -> exchange.respond(202, "")
                3 -> exchange.respond(
                    200,
                    """{"jsonrpc":"2.0","id":3,"result":{"tools":[{"name":"legacy","inputSchema":{"type":"object"}}]}}""",
                )
                else -> exchange.respond(200, "")
            }
        }
        try {
            val configured = McpServerSetting(id = "test", name = "Test", url = server.url())
            val discovery = McpHttpClient(configured, bearerToken = null).use { it.discoverTools() }

            assertEquals(McpProtocolMode.LEGACY, discovery.protocolVersion)
            assertEquals(listOf("legacy"), discovery.tools.map { it.name })
            assertEquals(null, discovery.cacheTtlMs)
            assertEquals(4, requestIndex.get())
            assertNull(JSONObject(requestBodies[1]).getJSONObject("params").optJSONObject("_meta"))
            assertNull(JSONObject(requestBodies[3]).getJSONObject("params").optJSONObject("_meta"))
            check(deleteReceived.await(2, TimeUnit.SECONDS)) { "legacy session was not released" }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun modernJsonRpcHeaderErrorDoesNotFallBackToLegacy() {
        val requestCount = AtomicInteger()
        val server = localServer { exchange ->
            requestCount.incrementAndGet()
            exchange.respond(
                400,
                """{"jsonrpc":"2.0","id":1,"error":{"code":-32020,"message":"Header mismatch"}}""",
            )
        }
        try {
            val configured = McpServerSetting(id = "test", name = "Test", url = server.url())
            val failure = runCatching {
                McpHttpClient(configured, bearerToken = null).use { it.discoverTools() }
            }.exceptionOrNull()

            assertNotNull(failure)
            assertEquals(1, requestCount.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun sseParserCombinesDataLinesAndReturnsAtFinalEvent() {
        val eventSent = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
        val server = localServer { exchange ->
            exchange.responseHeaders.set("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { output ->
                output.write(
                    """data: {"jsonrpc":"2.0",
data: "id":1,"result":{"resultType":"complete","ttlMs":1000,"tools":[]}}

""".toByteArray()
                )
                output.flush()
                eventSent.countDown()
                releaseServer.await(5, TimeUnit.SECONDS)
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val configured = McpServerSetting(id = "test", name = "Test", url = server.url())
            val result = executor.submit<McpHttpClient.Discovery> {
                McpHttpClient(configured, bearerToken = null).use { it.discoverTools() }
            }
            check(eventSent.await(2, TimeUnit.SECONDS)) { "SSE event was not sent" }
            val discovery = result.get(2, TimeUnit.SECONDS)

            assertEquals(emptyList<String>(), discovery.tools.map { it.name })
        } finally {
            releaseServer.countDown()
            executor.shutdownNow()
            server.stop(0)
        }
    }

    @Test
    fun legacyToolCallReinitializesOnceAfterSession404() {
        val initializeCount = AtomicInteger()
        val toolCallCount = AtomicInteger()
        val server = localServer { exchange ->
            if (exchange.requestMethod == "DELETE") {
                exchange.respond(200, "")
                return@localServer
            }
            val body = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
            when (body.getString("method")) {
                "tools/list" -> if (exchange.requestHeaders.getFirst("MCP-Protocol-Version") == McpProtocolMode.LATEST) {
                    exchange.respond(400, "legacy")
                } else {
                    exchange.respond(
                        200,
                        """{"jsonrpc":"2.0","id":3,"result":{"tools":[{"name":"legacy","inputSchema":{"type":"object"}}]}}""",
                    )
                }
                "initialize" -> {
                    val index = initializeCount.incrementAndGet()
                    exchange.respond(
                        200,
                        """{"jsonrpc":"2.0","id":${if (index == 1) 2 else 5},"result":{"protocolVersion":"2025-11-25","capabilities":{},"serverInfo":{"name":"test","version":"1"}}}""",
                        mapOf("Mcp-Session-Id" to "session-$index"),
                    )
                }
                "notifications/initialized" -> exchange.respond(202, "")
                "tools/call" -> if (toolCallCount.incrementAndGet() == 1) {
                    exchange.respond(404, "expired")
                } else {
                    exchange.respond(
                        200,
                        """{"jsonrpc":"2.0","id":6,"result":{"content":[{"type":"text","text":"ok"}]}}""",
                    )
                }
            }
        }
        try {
            val configured = McpServerSetting(id = "test", name = "Test", url = server.url())
            McpHttpClient(configured, bearerToken = null).use { client ->
                val tool = client.discoverTools().tools.single()
                val result = client.callTool(tool, JSONObject())

                assertEquals("ok", result.getJSONArray("content").getJSONObject(0).getString("text"))
            }
            assertEquals(2, initializeCount.get())
            assertEquals(2, toolCallCount.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun legacyToolListReinitializesOnceAfterSession404() {
        val initializeCount = AtomicInteger()
        val legacyListCount = AtomicInteger()
        val server = localServer { exchange ->
            if (exchange.requestMethod == "DELETE") {
                exchange.respond(200, "")
                return@localServer
            }
            val body = JSONObject(exchange.requestBody.bufferedReader().use { it.readText() })
            when (body.getString("method")) {
                "tools/list" -> if (exchange.requestHeaders.getFirst("MCP-Protocol-Version") == McpProtocolMode.LATEST) {
                    exchange.respond(400, "legacy")
                } else if (legacyListCount.incrementAndGet() == 1) {
                    exchange.respond(404, "expired")
                } else {
                    exchange.respond(
                        200,
                        """{"jsonrpc":"2.0","id":${body.getLong("id")},"result":{"tools":[{"name":"legacy","inputSchema":{"type":"object"}}]}}""",
                    )
                }
                "initialize" -> {
                    val index = initializeCount.incrementAndGet()
                    exchange.respond(
                        200,
                        """{"jsonrpc":"2.0","id":${body.getLong("id")},"result":{"protocolVersion":"2025-11-25","capabilities":{},"serverInfo":{"name":"test","version":"1"}}}""",
                        mapOf("Mcp-Session-Id" to "session-$index"),
                    )
                }
                "notifications/initialized" -> exchange.respond(202, "")
            }
        }
        try {
            val configured = McpServerSetting(id = "test", name = "Test", url = server.url())
            val discovery = McpHttpClient(configured, bearerToken = null).use { it.discoverTools() }

            assertEquals(listOf("legacy"), discovery.tools.map { it.name })
            assertEquals(2, initializeCount.get())
            assertEquals(2, legacyListCount.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun closeCancelsActiveRequestWithoutWaitingForNetworkTimeout() {
        val requestStarted = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
        val server = localServer { exchange ->
            requestStarted.countDown()
            releaseServer.await(5, TimeUnit.SECONDS)
            runCatching { exchange.respond(200, "{}") }
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val configured = McpServerSetting(id = "test", name = "Test", url = server.url())
            val client = McpHttpClient(configured, bearerToken = null)
            val result = executor.submit<Throwable?> {
                runCatching { client.discoverTools() }.exceptionOrNull()
            }
            check(requestStarted.await(2, TimeUnit.SECONDS)) { "request did not start" }

            val startedAt = System.nanoTime()
            client.close()
            val closeElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
            releaseServer.countDown()

            assertNotNull(result.get(2, TimeUnit.SECONDS))
            check(closeElapsedMs < 500L) { "close blocked for ${closeElapsedMs}ms" }
        } finally {
            releaseServer.countDown()
            executor.shutdownNow()
            server.stop(0)
        }
    }

    @Test
    fun endpointAcceptsUserConfiguredHttpAndHttpsUrls() {
        assertEquals("https", validateMcpEndpoint("https://example.com/mcp").scheme)
        assertEquals("http", validateMcpEndpoint("http://127.0.0.1:8080/mcp").scheme)
        assertEquals("http", validateMcpEndpoint("http://192.168.1.2:8080/mcp").scheme)
        assertEquals("user", validateMcpEndpoint("https://user@example.com/mcp").username)
        assertNotNull(runCatching { validateMcpEndpoint("not a url") }.exceptionOrNull())
    }

    @Test
    fun toolSchemaAcceptsObjectSubset() {
        val schema = JSONObject(
            """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}"""
        )

        assertNull(validateToolDefinition("search.notes", schema))
    }

    @Test
    fun toolSchemaRejectsUnsupportedCompositionAndHeaders() {
        val composed = JSONObject("""{"type":"object","oneOf":[]}""")
        val header = JSONObject("""{"type":"object","properties":{"token":{"x-mcp-header":"X-Token"}}}""")

        assertEquals("暂不支持 schema 关键字 oneOf", validateToolDefinition("search", composed))
        assertEquals("暂不支持 schema 关键字 x-mcp-header", validateToolDefinition("search", header))
    }

    private fun localServer(handler: (HttpExchange) -> Unit): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/mcp", handler)
            start()
        }

    private fun HttpServer.url(): String = "http://127.0.0.1:${address.port}/mcp"

    private fun HttpExchange.respond(
        status: Int,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ) {
        headers.forEach(responseHeaders::set)
        if (body.isNotEmpty()) responseHeaders.set("Content-Type", "application/json")
        val bytes = body.toByteArray()
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
