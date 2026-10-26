package io.github.mangi.eta.agent.model

import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.CustomBody
import io.github.mangi.eta.data.model.GeminiEndpointMode
import io.github.mangi.eta.data.model.ProviderTypes
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiGenerateContentProviderTest {

    @Test
    fun providerClientFactoryDispatchesDualProtocolCorrectly() {
        val baseConfig = AgentModelClient.ModelConfig(
            providerType = ProviderTypes.GEMINI,
            baseUrl = "https://generativelanguage.googleapis.com",
            apiKey = "test-api-key",
            model = "gemini-3.8-flash",
            systemPrompt = "",
        )

        // 1. Default GENERATE_CONTENT routes to GeminiGenerateContentProvider
        val clientDefault = ProviderClientFactory.getClient(baseConfig)
        assertEquals(GeminiGenerateContentProvider, clientDefault)

        // 2. Explicit GENERATE_CONTENT routes to GeminiGenerateContentProvider
        val clientGenContent = ProviderClientFactory.getClient(
            baseConfig.copy(geminiEndpointMode = GeminiEndpointMode.GENERATE_CONTENT)
        )
        assertEquals(GeminiGenerateContentProvider, clientGenContent)

        // 3. INTERACTIONS mode routes to GeminiInteractionsProvider
        val clientInteractions = ProviderClientFactory.getClient(
            baseConfig.copy(geminiEndpointMode = GeminiEndpointMode.INTERACTIONS)
        )
        assertEquals(GeminiInteractionsProvider, clientInteractions)

        // 4. INTERACTIONS mode with Service Account JSON forces GeminiGenerateContentProvider
        val clientSa = ProviderClientFactory.getClient(
            baseConfig.copy(
                geminiEndpointMode = GeminiEndpointMode.INTERACTIONS,
                apiKey = """{"type":"service_account","project_id":"p","private_key":"k","client_email":"e"}""",
            )
        )
        assertEquals(GeminiGenerateContentProvider, clientSa)

        // 5. INTERACTIONS mode with Vertex AI URL forces GeminiGenerateContentProvider
        val clientVertex = ProviderClientFactory.getClient(
            baseConfig.copy(
                geminiEndpointMode = GeminiEndpointMode.INTERACTIONS,
                baseUrl = "https://us-central1-aiplatform.googleapis.com",
            )
        )
        assertEquals(GeminiGenerateContentProvider, clientVertex)
    }

    @Test
    fun streamingParsesGroundingMetadataIntoSearchSuggestionsAndCitations() {
        val groundingChunk = JSONObject().put(
            "candidates",
            JSONArray().put(
                JSONObject()
                    .put(
                        "content",
                        JSONObject().put(
                            "parts",
                            JSONArray().put(
                                JSONObject().put("text", "Google 是一家跨国科技公司。")
                            )
                        )
                    )
                    .put(
                        "groundingMetadata",
                        JSONObject()
                            .put("webSearchQueries", JSONArray().put("Google 简介"))
                            .put(
                                "searchEntryPoint",
                                JSONObject().put("renderedContent", "<style>.chip{}</style><div>搜索建议</div>")
                            )
                            .put(
                                "groundingChunks",
                                JSONArray().put(
                                    JSONObject().put(
                                        "web",
                                        JSONObject()
                                            .put("uri", "https://www.google.com")
                                            .put("title", "Google 官网")
                                    )
                                )
                            )
                            .put(
                                "groundingSupports",
                                JSONArray().put(
                                    JSONObject()
                                        .put("groundingChunkIndices", JSONArray().put(0))
                                        .put(
                                            "segment",
                                            JSONObject()
                                                .put("startIndex", 0)
                                                .put("endIndex", 6)
                                        )
                                )
                            )
                    )
                    .put("finishReason", "STOP")
            )
        )

        val events = mutableListOf<ProviderEvent>()
        withSseServer(
            writeBody = { output ->
                output.write("data: $groundingChunk\n\n".toByteArray())
                output.write("data: [DONE]\n\n".toByteArray())
                output.flush()
            }
        ) { baseUrl ->
            val request = ProviderRequest(
                config = AgentModelClient.ModelConfig(
                    providerSourceType = "gemini",
                    baseUrl = baseUrl,
                    apiKey = "test-key",
                    model = "gemini-3.8-flash",
                    systemPrompt = "",
                    hostedWebSearchEnabled = true,
                ),
                messages = JSONArray().put(JSONObject().put("role", "user").put("content", "介绍 Google")),
                tools = JSONArray()
            )

            val response = GeminiGenerateContentProvider.complete(request, AgentRunController(), events::add)
            val assistant = response.assistantMessage

            // 1. SearchSuggestions event is emitted
            val suggestions = events.filterIsInstance<ProviderEvent.SearchSuggestions>()
            assertEquals(1, suggestions.size)
            assertEquals("<style>.chip{}</style><div>搜索建议</div>", suggestions[0].html)

            // 2. Pending suggestions attached to assistant
            val pending = GeminiEphemeralState.pendingSuggestions(assistant)
            assertEquals(1, pending.length())
            assertEquals("<style>.chip{}</style><div>搜索建议</div>", pending.getString(0))

            // 3. Citations formatted into assistant content
            val content = assistant.getString("content")
            assertTrue("content should include citation link: $content", content.contains("[[1]](<https://www.google.com>)"))
        }
    }

    @Test
    fun streamingCapturesThoughtSignatureAcrossChunksAndAttachesToToolCall() {
        val testSig = "EpoGCpcGAXLI2nxRealCryptographicSignatureFromGoogle"
        withSseServer(
            writeBody = { output ->
                // Chunk 1: 思考流及签名
                output.write(
                    geminiSseChunk(
                        JSONObject().put(
                            "content",
                            JSONObject().put(
                                "parts",
                                JSONArray().put(
                                    JSONObject()
                                        .put("thought", true)
                                        .put("text", "正在深度权衡...")
                                        .put("thoughtSignature", testSig)
                                )
                            )
                        )
                    ).toByteArray()
                )
                output.flush()

                // Chunk 2: 意图简报普通文本（无签名）
                output.write(
                    geminiSseChunk(
                        JSONObject().put(
                            "content",
                            JSONObject().put(
                                "parts",
                                JSONArray().put(
                                    JSONObject().put("text", "正在为您检索相关信息...")
                                )
                            )
                        )
                    ).toByteArray()
                )
                output.flush()

                // Chunk 3: 工具调用（自身 chunk 无签名）
                output.write(
                    geminiSseChunk(
                        JSONObject().put(
                            "content",
                            JSONObject().put(
                                "parts",
                                JSONArray().put(
                                    JSONObject().put(
                                        "functionCall",
                                        JSONObject()
                                            .put("name", "skills_read")
                                            .put("args", JSONObject().put("skillId", "agent-reach"))
                                    )
                                )
                            )
                        ),
                        finishReason = "STOP"
                    ).toByteArray()
                )
                output.flush()
                output.write("data: [DONE]\n\n".toByteArray())
            }
        ) { baseUrl ->
            val request = providerRequest(baseUrl)
            val response = GeminiGenerateContentProvider.complete(request, AgentRunController()) {}
            val assistant = response.assistantMessage

            // 验证顶层与 tool_call 均成功捕获跨 chunk 传递的真实签名
            assertEquals(testSig, assistant.optString("thoughtSignature"))
            val toolCalls = assistant.getJSONArray("tool_calls")
            assertEquals(1, toolCalls.length())
            val firstCall = toolCalls.getJSONObject(0)
            assertEquals(testSig, firstCall.optString("thoughtSignature"))
            assertEquals("skills_read", firstCall.getJSONObject("function").getString("name"))
        }
    }

    @Test
    fun convertAssistantContentPutsSignatureOnAnchorAndDoesNotUseFakeSentinel() {
        val testSig = "EpoGCpcGAXLI2nxRealCryptographicSignatureFromGoogle"
        val message = JSONObject()
            .put("role", "assistant")
            .put("content", "正在为您检索相关信息...")
            .put("reasoning", "内部权衡过程")
            .put("thoughtSignature", testSig)
            .put(
                "tool_calls",
                JSONArray().put(
                    JSONObject()
                        .put("id", "call_123")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", "skills_read")
                                .put("arguments", "{\"skillId\":\"agent-reach\"}")
                        )
                        .put("thoughtSignature", testSig)
                )
            )

        // 通过反射调用 private 的 convertAssistantContent
        val method = GeminiGenerateContentProvider::class.java.getDeclaredMethod(
            "convertAssistantContent",
            JSONObject::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        val parts = method.invoke(GeminiGenerateContentProvider, message, null, false) as JSONArray

        // 验证 parts 结构：
        // 1. 思考块：thought=true, text="内部权衡过程", 无签名（对齐官方）
        // 2. 权威锚点：首个非思考 Part 必须是 functionCall，且 thoughtSignature=testSig
        // 3. 动作意图文本块：text="正在为您检索相关信息..."
        assertEquals(3, parts.length())

        val thoughtPart = parts.getJSONObject(0)
        assertTrue(thoughtPart.optBoolean("thought"))
        assertEquals("内部权衡过程", thoughtPart.getString("text"))
        assertFalse(thoughtPart.has("thoughtSignature"))

        val fnPart = parts.getJSONObject(1)
        assertTrue(fnPart.has("functionCall"))
        assertEquals(testSig, fnPart.getString("thoughtSignature"))

        val textPart = parts.getJSONObject(2)
        assertEquals("正在为您检索相关信息...", textPart.getString("text"))
        assertFalse(textPart.has("thoughtSignature"))

        // 验证绝不包含任何 skip_thought_signature_validator
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            assertFalse(p.optString("thoughtSignature") == "skip_thought_signature_validator")
            assertFalse(p.optString("thought_signature") == "skip_thought_signature_validator")
        }
    }

    @Test
    fun convertAssistantContentWithoutSignatureNeverInjectsBlockedSentinel() {
        val message = JSONObject()
            .put("role", "assistant")
            .put("content", "简单回答")
            .put(
                "tool_calls",
                JSONArray().put(
                    JSONObject()
                        .put("id", "call_456")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", "get_current_context")
                                .put("arguments", "{}")
                        )
                )
            )

        val method = GeminiGenerateContentProvider::class.java.getDeclaredMethod(
            "convertAssistantContent",
            JSONObject::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        val parts = method.invoke(GeminiGenerateContentProvider, message, null, false) as JSONArray

        assertEquals(2, parts.length())
        val fnPart = parts.getJSONObject(0)
        assertFalse(fnPart.has("thoughtSignature"))
        assertFalse(fnPart.has("thought_signature"))
    }

    @Test
    fun multiTurnToolCallingInheritsSessionSignatureWhenSubsequentRoundHasNoSignature() {
        val sessionSig = "EpoGCpcGAXLI2nxSessionLevelCryptographicSignature"
        // 模拟第 2 轮工具调用消息（自身没有 thoughtSignature）
        val round2Message = JSONObject()
            .put("role", "assistant")
            .put(
                "tool_calls",
                JSONArray().put(
                    JSONObject()
                        .put("id", "call_789")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", "skills_read_resource")
                                .put("arguments", "{\"relativePath\":\"references/dev.md\"}")
                        )
                )
            )

        val method = GeminiGenerateContentProvider::class.java.getDeclaredMethod(
            "convertAssistantContent",
            JSONObject::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType,
        )
        method.isAccessible = true
        val parts = method.invoke(GeminiGenerateContentProvider, round2Message, sessionSig, false) as JSONArray

        assertEquals(1, parts.length())
        val fnPart = parts.getJSONObject(0)
        assertTrue(fnPart.has("functionCall"))
        assertEquals("skills_read_resource", fnPart.getJSONObject("functionCall").getString("name"))
        // 关键：无缝继承前序轮次的有效签名，杜绝 400
        assertEquals(sessionSig, fnPart.getString("thoughtSignature"))
    }

    @Test
    fun buildRequestJsonProjectsAndPreservesCombinatorToolSchemas() {
        val tools = JSONArray("""[
            {
                "type": "function",
                "function": {
                    "name": "fetch_url",
                    "description": "Fetch URL content",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "url": { "type": "string" },
                            "document_id": { "type": "string" },
                            "offset_chars": { "type": "integer" }
                        },
                        "oneOf": [
                            { "required": ["url"] },
                            { "required": ["document_id"] }
                        ]
                    }
                }
            }
        ]""")

        val baseConfig = AgentModelClient.ModelConfig(
            providerSourceType = "gemini",
            baseUrl = "https://generativelanguage.googleapis.com",
            apiKey = "test-key",
            model = "gemini-3.8-flash",
            systemPrompt = "",
        )

        val request = GeminiGenerateContentProvider.buildRequestJson(
            config = baseConfig,
            tools = tools,
        )

        val toolsArr = request.getJSONArray("tools")
        val fnDecls = toolsArr.getJSONObject(0).getJSONArray("functionDeclarations")
        assertEquals(1, fnDecls.length())
        val params = fnDecls.getJSONObject(0).getJSONObject("parameters")
        assertTrue("parameters should contain projected oneOf", params.has("oneOf"))
        val oneOf = params.getJSONArray("oneOf")
        assertEquals(2, oneOf.length())
        // GeminiToolSchema.project should have copied "url" property definition into branch 0
        val branch0 = oneOf.getJSONObject(0)
        assertTrue(branch0.has("properties"))
        assertEquals("string", branch0.getJSONObject("properties").getJSONObject("url").getString("type"))
        assertEquals("url", branch0.getJSONArray("required").getString(0))
        // And "document_id" into branch 1
        val branch1 = oneOf.getJSONObject(1)
        assertTrue(branch1.has("properties"))
        assertEquals("string", branch1.getJSONObject("properties").getJSONObject("document_id").getString("type"))
    }

    @Test
    fun buildRequestJsonIncludesGoogleSearchWhenHostedWebSearchEnabled() {
        val baseConfig = AgentModelClient.ModelConfig(
            providerSourceType = "gemini",
            baseUrl = "https://generativelanguage.googleapis.com",
            apiKey = "test-key",
            model = "gemini-3.8-flash",
            systemPrompt = "",
            hostedWebSearchEnabled = true,
        )

        val requestWithoutTools = GeminiGenerateContentProvider.buildRequestJson(
            config = baseConfig,
            tools = JSONArray(),
        )
        val toolsArr = requestWithoutTools.getJSONArray("tools")
        assertEquals(1, toolsArr.length())
        assertTrue(toolsArr.getJSONObject(0).has("google_search"))

        // When function tools are also present, both functionDeclarations and google_search should be in tools
        val tools = JSONArray("""[
            {
                "type": "function",
                "function": {
                    "name": "test_tool",
                    "parameters": { "type": "object" }
                }
            }
        ]""")
        val requestWithTools = GeminiGenerateContentProvider.buildRequestJson(
            config = baseConfig,
            tools = tools,
        )
        val toolsArr2 = requestWithTools.getJSONArray("tools")
        assertEquals(2, toolsArr2.length())
        assertTrue(toolsArr2.getJSONObject(0).has("functionDeclarations"))
        assertTrue(toolsArr2.getJSONObject(1).has("google_search"))
    }

    @Test
    fun configuredMaxOutputTokensSetsGenerationConfigMaxOutputTokens() {
        val baseConfig = AgentModelClient.ModelConfig(
            providerSourceType = "gemini",
            baseUrl = "https://generativelanguage.googleapis.com",
            apiKey = "test-key",
            model = "gemini-3.8-flash-tiered",
            systemPrompt = "",
        )

        // 1. Configured maxOutputTokens sets maxOutputTokens in generationConfig
        val req1 = GeminiGenerateContentProvider.buildRequestJson(
            config = baseConfig.copy(maxOutputTokens = 4096),
        )
        val genConfig1 = req1.getJSONObject("generationConfig")
        assertEquals(4096, genConfig1.getInt("maxOutputTokens"))

        // 2. Null maxOutputTokens omits maxOutputTokens
        val req2 = GeminiGenerateContentProvider.buildRequestJson(
            config = baseConfig.copy(maxOutputTokens = null),
        )
        val genConfig2 = req2.getJSONObject("generationConfig")
        assertFalse(genConfig2.has("maxOutputTokens"))

        // 3. Non-positive maxOutputTokens omits maxOutputTokens
        val req3 = GeminiGenerateContentProvider.buildRequestJson(
            config = baseConfig.copy(maxOutputTokens = 0),
        )
        val genConfig3 = req3.getJSONObject("generationConfig")
        assertFalse(genConfig3.has("maxOutputTokens"))

        // 4. Custom body overrides configured maxOutputTokens
        val req4 = GeminiGenerateContentProvider.buildRequestJson(
            config = baseConfig.copy(
                maxOutputTokens = 4096,
                customBody = listOf(CustomBody("generationConfig", JsonObject(mapOf("maxOutputTokens" to JsonPrimitive(8192))))),
            ),
        )
        val genConfig4 = req4.getJSONObject("generationConfig")
        assertEquals(8192, genConfig4.getInt("maxOutputTokens"))
    }

    @Test
    fun ignoreThoughtSignaturesStripsSignaturesOnNonVertexAndUsesSentinelOnVertex() {
        val testSig = "EpoGCpcGAXLI2nxRealCryptographicSignatureFromGoogle"
        val message = JSONObject()
            .put("role", "assistant")
            .put("content", "意图说明")
            .put("thoughtSignature", testSig)
            .put(
                "tool_calls",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("id", "call_1")
                            .put("function", JSONObject().put("name", "fn1").put("arguments", "{}"))
                            .put("thoughtSignature", testSig)
                    )
                    .put(
                        JSONObject()
                            .put("id", "call_2")
                            .put("function", JSONObject().put("name", "fn2").put("arguments", "{}"))
                            .put("thoughtSignature", testSig)
                    )
            )

        // 1. 非 Vertex 且 ignoreThoughtSignatures = true：
        // 彻底剔除所有签名，绝不注入 skip_thought_signature_validator
        val nonVertexParts = GeminiGenerateContentProvider.convertAssistantContent(
            message = message,
            sessionFallbackSig = testSig,
            isVertexEndpoint = false,
            ignoreThoughtSignatures = true,
            model = "gemini-3.8-flash-tiered",
        )
        assertEquals(3, nonVertexParts.length()) // fn1, fn2, text
        assertFalse(nonVertexParts.getJSONObject(0).has("thoughtSignature"))
        assertFalse(nonVertexParts.getJSONObject(1).has("thoughtSignature"))
        assertFalse(nonVertexParts.getJSONObject(2).has("thoughtSignature"))

        // 2. Vertex 且 ignoreThoughtSignatures = true：
        // 仅在首个 functionCall 上挂 Google 官方豁免哨兵 skip_thought_signature_validator，后续 functionCall 与 textPart 均不带签名
        val vertexParts = GeminiGenerateContentProvider.convertAssistantContent(
            message = message,
            sessionFallbackSig = testSig,
            isVertexEndpoint = true,
            ignoreThoughtSignatures = true,
            model = "gemini-4-argon",
        )
        assertEquals(3, vertexParts.length())
        assertEquals("skip_thought_signature_validator", vertexParts.getJSONObject(0).getString("thoughtSignature"))
        assertFalse(vertexParts.getJSONObject(1).has("thoughtSignature"))
        assertFalse(vertexParts.getJSONObject(2).has("thoughtSignature"))
    }

    @Test
    fun signedToolContextDetectsPendingResponseForGenerateContent() {
        val testSig = "EpoGCpcGAXLI2nxRealCryptographicSignatureFromGoogle"
        val assistant = JSONObject()
            .put("role", "assistant")
            .put("content", "意图说明")
            .put("thoughtSignature", testSig)
            .put(
                "tool_calls",
                JSONArray().put(
                    JSONObject()
                        .put("id", "call_1")
                        .put("type", "function")
                        .put("function", JSONObject().put("name", "fn1").put("arguments", "{}"))
                        .put("thoughtSignature", testSig)
                )
            )
        val messages = JSONArray()
            .put(JSONObject().put("role", "user").put("content", "查资料"))
            .put(assistant)

        assertTrue(SignedToolContext.hasPendingResponse(messages))

        messages.put(JSONObject().put("role", "tool").put("tool_call_id", "call_1").put("content", "工具结果"))
        assertTrue(SignedToolContext.hasPendingResponse(messages))

        messages.put(JSONObject().put("role", "assistant").put("content", "已完成"))
        assertFalse(SignedToolContext.hasPendingResponse(messages))
    }

    private fun geminiSseChunk(candidate0: JSONObject, finishReason: String? = null): String {
        val c = JSONObject(candidate0.toString())
        if (finishReason != null) c.put("finishReason", finishReason)
        val root = JSONObject().put("candidates", JSONArray().put(c))
        return "data: $root\n\n"
    }

    private fun withSseServer(
        writeBody: (OutputStream) -> Unit,
        block: (String) -> Unit
    ) {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor
        server.createContext("/") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use(writeBody)
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun providerRequest(baseUrl: String): ProviderRequest =
        ProviderRequest(
            config = AgentModelClient.ModelConfig(
                providerSourceType = "gemini",
                baseUrl = baseUrl,
                apiKey = "test-key",
                model = "gemini-3.8-flash-tiered",
                systemPrompt = "",
                thinkingEnabled = true,
                reasoningEffort = io.github.mangi.eta.data.model.ReasoningEffort.DEFAULT,
            ),
            messages = JSONArray().put(JSONObject().put("role", "user").put("content", "测试")),
            tools = JSONArray()
        )
}
