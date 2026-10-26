package io.github.mangi.eta.agent.model

import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.repository.RemoteModelFetcher
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VertexAiServiceAccountAuthTest {

    @Before
    fun setUp() {
        VertexAiServiceAccountAuth.clearCacheForTests()
    }

    @After
    fun tearDown() {
        VertexAiServiceAccountAuth.clearCacheForTests()
    }

    @Test
    fun parsesValidServiceAccountJsonAndBuildsSignedRs256Jwt() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pkcs8Pem = buildString {
            append("-----BEGIN PRIVATE KEY-----\n")
            append(Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.private.encoded))
            append("\n-----END PRIVATE KEY-----\n")
        }
        val saJson = JSONObject()
            .put("type", "service_account")
            .put("project_id", "vertex-enterprise-proj")
            .put("private_key_id", "key-id-999")
            .put("private_key", pkcs8Pem)
            .put("client_email", "agent@vertex-enterprise-proj.iam.gserviceaccount.com")
            .put("token_uri", "https://oauth2.googleapis.com/token")
            .toString()

        assertTrue(VertexAiServiceAccountAuth.looksLikeServiceAccountJson(saJson))
        assertFalse(VertexAiServiceAccountAuth.looksLikeServiceAccountJson("AIzaSyFakeApiKey"))

        val creds = VertexAiServiceAccountAuth.parseServiceAccountJson(saJson)
        assertEquals("vertex-enterprise-proj", creds.projectId)
        assertEquals("agent@vertex-enterprise-proj.iam.gserviceaccount.com", creds.clientEmail)
        assertEquals("key-id-999", creds.privateKeyId)

        val jwt = VertexAiServiceAccountAuth.createSignedJwt(creds, nowEpochSeconds = 1700000000L)
        val parts = jwt.split(".")
        assertEquals(3, parts.size)

        val header = JSONObject(String(Base64.getUrlDecoder().decode(parts[0]), Charsets.UTF_8))
        val payload = JSONObject(String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8))
        assertEquals("RS256", header.getString("alg"))
        assertEquals("key-id-999", header.getString("kid"))
        assertEquals("agent@vertex-enterprise-proj.iam.gserviceaccount.com", payload.getString("iss"))
        assertEquals("https://oauth2.googleapis.com/token", payload.getString("aud"))
        assertEquals(1700000000L, payload.getLong("iat"))
        assertEquals(1700003600L, payload.getLong("exp"))
        assertTrue(payload.getString("scope").contains("cloud-platform"))

        // 验证 RSA 签名可被对应公钥验签通过
        val sigBytes = Base64.getUrlDecoder().decode(parts[2])
        val verifier = Signature.getInstance("SHA256withRSA").apply {
            initVerify(keyPair.public)
            update("${parts[0]}.${parts[1]}".toByteArray(Charsets.UTF_8))
        }
        assertTrue(verifier.verify(sigBytes))
    }

    @Test
    fun buildsVertexStreamGenerateContentUrlsForGlobalRegionalAndEuEndpoints() {
        val creds = VertexAiServiceAccountAuth.ServiceAccountCredentials(
            projectId = "my-gcp-project",
            clientEmail = "sa@my-gcp-project.iam.gserviceaccount.com",
            privateKeyPem = "-----BEGIN PRIVATE KEY-----fake-----END PRIVATE KEY-----",
        )

        // 1. 默认或预设 Base URL 自动组装为 global Vertex URL
        assertEquals(
            "https://aiplatform.googleapis.com/v1/projects/my-gcp-project/locations/global/publishers/google/models/gemini-3.8-flash-tiered:streamGenerateContent?alt=sse",
            VertexAiServiceAccountAuth.buildStreamGenerateContentUrl(
                baseUrl = "https://generativelanguage.googleapis.com",
                model = "gemini-3.8-flash-tiered",
                credentials = creds,
            )
        )

        // 2. 包含具体区域的 Base URL
        assertEquals(
            "https://aiplatform.googleapis.com/v1/projects/my-gcp-project/locations/us-central1/publishers/google/models/gemini-2.5-pro:streamGenerateContent?alt=sse",
            VertexAiServiceAccountAuth.buildStreamGenerateContentUrl(
                baseUrl = "https://aiplatform.googleapis.com/v1/projects/my-gcp-project/locations/us-central1",
                model = "publishers/google/models/gemini-2.5-pro",
                credentials = creds,
            )
        )

        // 3. EU 多区域主机自动解析
        assertEquals(
            "https://aiplatform.eu.rep.googleapis.com/v1/projects/my-gcp-project/locations/eu/publishers/google/models/gemini-2.5-flash:streamGenerateContent?alt=sse",
            VertexAiServiceAccountAuth.buildStreamGenerateContentUrl(
                baseUrl = "https://aiplatform.eu.rep.googleapis.com",
                model = "models/gemini-2.5-flash",
                credentials = creds,
            )
        )
    }

    @Test
    fun exchangesJwtForTokenCachesItAndCallsVertexStreamGenerateContent() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pkcs8Pem = buildString {
            append("-----BEGIN PRIVATE KEY-----\n")
            append(Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.private.encoded))
            append("\n-----END PRIVATE KEY-----\n")
        }

        val tokenCalls = AtomicInteger(0)
        val capturedInferencePath = AtomicReference<String>()
        val capturedAuthHeader = AtomicReference<String>()
        val capturedProjectHeader = AtomicReference<String>()

        val server = HttpServer.create(InetSocketAddress(0), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor

        server.createContext("/token") { exchange ->
            tokenCalls.incrementAndGet()
            val responseBytes = """{"access_token":"ya29.mock-vertex-token","expires_in":3600}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, responseBytes.size.toLong())
            exchange.responseBody.use { it.write(responseBytes) }
        }

        server.createContext("/") { exchange ->
            capturedInferencePath.set("${exchange.requestURI.path}?${exchange.requestURI.query}")
            capturedAuthHeader.set(exchange.requestHeaders.getFirst("Authorization"))
            capturedProjectHeader.set(exchange.requestHeaders.getFirst("x-goog-user-project"))
            val sse = "data: " + JSONObject().put(
                "candidates",
                JSONArray().put(
                    JSONObject()
                        .put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "来自 Vertex AI 的回复"))))
                        .put("finishReason", "STOP")
                )
            ).toString() + "\n\ndata: [DONE]\n\n"
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write(sse.toByteArray()) }
        }

        server.start()
        try {
            val port = server.address.port
            val baseUrl = "http://127.0.0.1:$port"
            val saJson = JSONObject()
                .put("type", "service_account")
                .put("project_id", "eta-vertex-test")
                .put("private_key_id", "kid-1")
                .put("private_key", pkcs8Pem)
                .put("client_email", "eta@eta-vertex-test.iam.gserviceaccount.com")
                .put("token_uri", "$baseUrl/token")
                .toString()

            val request = ProviderRequest(
                config = AgentModelClient.ModelConfig(
                    providerSourceType = "gemini",
                    baseUrl = baseUrl,
                    apiKey = saJson,
                    model = "gemini-3.8-flash-tiered",
                    systemPrompt = "",
                    thinkingEnabled = false,
                ),
                messages = JSONArray().put(JSONObject().put("role", "user").put("content", "你好")),
                tools = JSONArray(),
            )

            val response1 = GeminiGenerateContentProvider.complete(request, AgentRunController()) {}
            val response2 = GeminiGenerateContentProvider.complete(request, AgentRunController()) {}

            assertEquals("来自 Vertex AI 的回复", response1.assistantMessage.getString("content"))
            assertEquals("来自 Vertex AI 的回复", response2.assistantMessage.getString("content"))
            // 两次推理应当复用同一个缓存的 OAuth2 令牌
            assertEquals(1, tokenCalls.get())
            assertEquals("Bearer ya29.mock-vertex-token", capturedAuthHeader.get())
            assertEquals("eta-vertex-test", capturedProjectHeader.get())
            assertEquals(
                "/v1/projects/eta-vertex-test/locations/global/publishers/google/models/gemini-3.8-flash-tiered:streamGenerateContent?alt=sse",
                capturedInferencePath.get(),
            )
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    @Test
    fun parsesVertexPublisherModelsCatalogFormat() {
        val publisherJson = """
            {
              "publisherModels": [
                {
                  "name": "publishers/google/models/gemini-4-argon",
                  "displayName": "Gemini 4 Argon"
                },
                {
                  "name": "publishers/google/models/gemini-3.8-flash-tiered",
                  "displayName": "Gemini 3.8 Flash Tiered"
                },
                {
                  "name": "publishers/google/models/gemini-3.1-flash-image",
                  "displayName": "Gemini 3.1 Flash Image"
                },
                {
                  "name": "publishers/google/models/gemini-nano-banana-2.1",
                  "displayName": "Gemini Nano Banana 2.1"
                }
              ]
            }
        """.trimIndent()

        val models = RemoteModelFetcher.parseGeminiModels(publisherJson)
        assertEquals(
            listOf("gemini-4-argon", "gemini-3.8-flash-tiered", "gemini-3.1-flash-image", "gemini-nano-banana-2.1"),
            models.map { it.modelId }
        )
        assertTrue(models[0].toolCall == true)
        assertTrue(models[1].toolCall == true)
        assertFalse(models[2].toolCall == true)
        assertFalse(models[3].toolCall == true)
    }

    @Test
    fun vertexModernGeminiUsesThinkingLevelInsteadOfThinkingBudget() {
        val method = GeminiGenerateContentProvider::class.java.getDeclaredMethod(
            "buildRequestJson",
            AgentModelClient.ModelConfig::class.java,
            JSONArray::class.java,
            JSONArray::class.java,
            String::class.java,
        )
        method.isAccessible = true

        // 1. Default 档位：仅发送 includeThoughts=true，不发送 thinkingBudget 或 thinkingLevel
        val defaultConfig = AgentModelClient.ModelConfig(
            providerSourceType = "gemini",
            baseUrl = "https://aiplatform.googleapis.com/v1/projects/p1/locations/global",
            apiKey = "test",
            model = "gemini-4-argon",
            systemPrompt = "",
            thinkingEnabled = true,
            reasoningEffort = io.github.mangi.eta.data.model.ReasoningEffort.DEFAULT,
        )
        val defaultPayload = method.invoke(GeminiGenerateContentProvider, defaultConfig, JSONArray(), JSONArray(), null) as JSONObject
        val defaultThinking = defaultPayload.getJSONObject("generationConfig").getJSONObject("thinkingConfig")
        assertTrue(defaultThinking.getBoolean("includeThoughts"))
        assertFalse(defaultThinking.has("thinkingBudget"))
        assertFalse(defaultThinking.has("thinkingLevel"))

        // 2. High 档位：发送 includeThoughts=true, thinkingLevel="HIGH"，不发送 thinkingBudget
        val highConfig = defaultConfig.copy(reasoningEffort = io.github.mangi.eta.data.model.ReasoningEffort.HIGH)
        val highPayload = method.invoke(GeminiGenerateContentProvider, highConfig, JSONArray(), JSONArray(), null) as JSONObject
        val highThinking = highPayload.getJSONObject("generationConfig").getJSONObject("thinkingConfig")
        assertTrue(highThinking.getBoolean("includeThoughts"))
        assertEquals("HIGH", highThinking.getString("thinkingLevel"))
        assertFalse(highThinking.has("thinkingBudget"))

        // 3. Off 档位：仅发送 includeThoughts=false，绝不发送 thinkingBudget: 0
        val offConfig = defaultConfig.copy(
            thinkingEnabled = false,
            reasoningEffort = io.github.mangi.eta.data.model.ReasoningEffort.OFF,
        )
        val offPayload = method.invoke(GeminiGenerateContentProvider, offConfig, JSONArray(), JSONArray(), null) as JSONObject
        val offThinking = offPayload.getJSONObject("generationConfig").getJSONObject("thinkingConfig")
        assertFalse(offThinking.getBoolean("includeThoughts"))
        assertFalse(offThinking.has("thinkingBudget"))

        // 4. Vertex AI 生图模型不发送 outputMimeType 与 thinkingConfig
        val imgConfig = defaultConfig.copy(model = "gemini-nano-banana-2.1")
        val imgPayload = method.invoke(GeminiGenerateContentProvider, imgConfig, JSONArray(), JSONArray(), null) as JSONObject
        val imgGenConfig = imgPayload.getJSONObject("generationConfig")
        assertFalse(imgGenConfig.has("thinkingConfig"))
        assertFalse(imgGenConfig.getJSONObject("imageConfig").has("outputMimeType"))
    }

    @Test
    fun fullAgentToolCatalogIsSanitizedOfUnsupportedVertexSchemaKeys() {
        val tools = AgentToolCatalog.build(
            terminalTools = true,
            browserTools = true,
            deviceDirectTools = true,
            deviceSensitiveReadTools = true,
            deviceSensitiveActionTools = true,
            skillGitHubDiscovery = true,
            skillGitHubInstall = true,
            memoryTools = true,
            memoryWritable = true,
            capabilities = io.github.mangi.eta.agent.tool.AgentToolCapabilities(rootAvailable = true),
            localWebSearch = true,
        )
        tools.put(AgentConversationToolCatalog.schema())

        val method = GeminiGenerateContentProvider::class.java.getDeclaredMethod(
            "buildRequestJson",
            AgentModelClient.ModelConfig::class.java,
            JSONArray::class.java,
            JSONArray::class.java,
            String::class.java,
        )
        method.isAccessible = true
        val config = AgentModelClient.ModelConfig(
            providerSourceType = "gemini",
            baseUrl = "https://aiplatform.googleapis.com/v1/projects/p1/locations/global",
            apiKey = "test",
            model = "gemini-4-argon",
            systemPrompt = "你是 Eta",
            thinkingEnabled = true,
            reasoningEffort = io.github.mangi.eta.data.model.ReasoningEffort.DEFAULT,
        )
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "你好"))
        val payload = method.invoke(GeminiGenerateContentProvider, config, messages, tools, null) as JSONObject
        val payloadStr = payload.toString()

        // 确保 Vertex AI 不识别的 uniqueItems、additionalProperties、$schema 已全部剥离
        assertFalse(payloadStr.contains("\"uniqueItems\""))
        assertFalse(payloadStr.contains("\"additionalProperties\""))
        assertFalse(payloadStr.contains("\"\$schema\""))
    }

    @Test
    fun thoughtSignaturePersistsAcrossConversationCodecAndInterruptedToolTurnInsertsBridgeModelTurn() {
        val testSig = "AY89a18WEl8owN8ZBMbHkMgwRNdvTGGbnBLgXqhu4k0"
        val rawAssistant = JSONObject()
            .put("role", "assistant")
            .put("content", "正在读取文件...")
            .put("thoughtSignature", testSig)
            .put(
                "tool_calls",
                JSONArray().put(
                    JSONObject()
                        .put("id", "call_1")
                        .put("type", "function")
                        .put("thoughtSignature", testSig)
                        .put("function", JSONObject().put("name", "terminal").put("arguments", "{\"command\":\"ls\"}"))
                )
            )
        val parsedCalls = AgentConversationCodec.parseToolCalls(rawAssistant)
        assertEquals(testSig, parsedCalls.single().thoughtSignature)

        val historyObj = AgentConversationCodec.assistantHistoryMessage(rawAssistant, parsedCalls)
        val encoded = AgentConversationCodec.encodeTranscriptForStorage(
            listOf(AgentConversationCodec.fromJsonObject(historyObj))
        )
        val decoded = AgentConversationCodec.decodeTranscript(encoded).single()
        assertEquals(testSig, decoded.thoughtSignature)
        val restoredJson = AgentConversationCodec.toJsonObject(decoded)
        assertEquals(testSig, restoredJson.optString("thoughtSignature"))
        assertEquals(testSig, restoredJson.getJSONArray("tool_calls").getJSONObject(0).optString("thoughtSignature"))

        // 验证当历史以 tool (functionResponse) 结尾且紧跟新的 user 消息（如超时中断后发送“继续”）时，
        // 不会将 functionResponse 与 user text 混在同一 turn 导致 Vertex AI 报 400 "Requests ending with a model turn are not supported."
        val messages = JSONArray()
            .put(JSONObject().put("role", "user").put("content", "读取项目"))
            .put(restoredJson)
            .put(JSONObject().put("role", "tool").put("tool_call_id", "call_1").put("content", "{\"ok\":true}"))
            .put(JSONObject().put("role", "user").put("content", "继续"))

        val method = GeminiGenerateContentProvider::class.java.getDeclaredMethod(
            "buildRequestJson",
            AgentModelClient.ModelConfig::class.java,
            JSONArray::class.java,
            JSONArray::class.java,
            String::class.java,
        )
        method.isAccessible = true
        val config = AgentModelClient.ModelConfig(
            providerSourceType = "gemini",
            baseUrl = "https://aiplatform.googleapis.com/v1/projects/p1/locations/global",
            apiKey = "test",
            model = "gemini-4-argon",
            systemPrompt = "",
            thinkingEnabled = true,
            reasoningEffort = io.github.mangi.eta.data.model.ReasoningEffort.DEFAULT,
        )
        val payload = method.invoke(GeminiGenerateContentProvider, config, messages, JSONArray(), null) as JSONObject
        val contents = payload.getJSONArray("contents")
        // 预期共有 5 个 turn：user("读取项目") -> model(functionCall) -> user(functionResponse) -> model(过渡说明) -> user("继续")
        assertEquals(5, contents.length())
        assertEquals("user", contents.getJSONObject(2).getString("role"))
        assertTrue(contents.getJSONObject(2).getJSONArray("parts").getJSONObject(0).has("functionResponse"))
        assertEquals("model", contents.getJSONObject(3).getString("role"))
        assertEquals("user", contents.getJSONObject(4).getString("role"))
        assertEquals("继续", contents.getJSONObject(4).getJSONArray("parts").getJSONObject(0).getString("text"))
    }

    @Test
    fun switchingFromGemini3ToVertexGemini4ReplacesIncompatibleSignatureWithSentinel() {
        val oldSig = "EtoQCtcQAWkUfROKjl6gXBNF"
        val vertexSig = "AY89a18WEl8owN8ZBMbHkMgwRNdvTGGbnBLgXqhu4k0"

        // 1. 验证签名兼容性判定逻辑
        assertFalse(GeminiGenerateContentProvider.isCompatibleThoughtSignature(oldSig, isVertexEndpoint = true, model = "gemini-4-argon"))
        assertTrue(GeminiGenerateContentProvider.isCompatibleThoughtSignature(vertexSig, isVertexEndpoint = true, model = "gemini-4-argon"))
        assertTrue(GeminiGenerateContentProvider.isCompatibleThoughtSignature(oldSig, isVertexEndpoint = false, model = "gemini-3.8-flash-tiered"))
        assertFalse(GeminiGenerateContentProvider.isCompatibleThoughtSignature(vertexSig, isVertexEndpoint = false, model = "gemini-3.8-flash-tiered"))

        // 2. 模拟由 gemini-3.8-flash-tiered 生成的历史消息，切换至 Vertex AI gemini-4-argon
        val assistantMsg = JSONObject()
            .put("role", "assistant")
            .put("content", "正在为您检索相关信息...")
            .put("thoughtSignature", oldSig)
            .put(
                "tool_calls",
                JSONArray().put(
                    JSONObject()
                        .put("id", "call_search")
                        .put("type", "function")
                        .put("thoughtSignature", oldSig)
                        .put("function", JSONObject().put("name", "local_search").put("arguments", "{\"query\":\"test\"}"))
                )
            )
        val toolMsg = JSONObject()
            .put("role", "tool")
            .put("tool_call_id", "call_search")
            .put("content", "{\"result\":\"ok\"}")
        val messages = JSONArray()
            .put(JSONObject().put("role", "user").put("content", "查一下资料"))
            .put(assistantMsg)
            .put(toolMsg)

        val config = AgentModelClient.ModelConfig(
            providerSourceType = "gemini",
            baseUrl = "https://aiplatform.googleapis.com/v1/projects/my-proj/locations/global",
            apiKey = "test-key",
            model = "gemini-4-argon",
            systemPrompt = "",
            thinkingEnabled = true,
            reasoningEffort = io.github.mangi.eta.data.model.ReasoningEffort.DEFAULT,
        )

        val payload = GeminiGenerateContentProvider.buildRequestJson(config, messages)
        val payloadStr = payload.toString()

        // 确保旧模型的非兼容签名未泄漏至请求体中
        assertFalse(payloadStr.contains(oldSig))

        // 确保首个 functionCall 被自动替换为 Google Vertex 官方规范豁免哨兵 skip_thought_signature_validator
        val contents = payload.getJSONArray("contents")
        assertEquals(3, contents.length())
        val modelTurn = contents.getJSONObject(1)
        assertEquals("model", modelTurn.getString("role"))
        val parts = modelTurn.getJSONArray("parts")
        val fnPart = parts.getJSONObject(0)
        assertTrue(fnPart.has("functionCall"))
        assertEquals("skip_thought_signature_validator", fnPart.getString("thoughtSignature"))

        // 意图文本块不携带旧签名
        if (parts.length() > 1) {
            val textPart = parts.getJSONObject(1)
            assertFalse(textPart.has("thoughtSignature"))
        }
    }

    @Test
    fun retriesWithIgnoreThoughtSignaturesWhenEndpointReturnsHttp400InvalidThoughtSignature() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        val executor = Executors.newCachedThreadPool()
        server.executor = executor

        val requestCount = AtomicInteger(0)
        val capturedBodies = java.util.concurrent.CopyOnWriteArrayList<JSONObject>()

        server.createContext("/") { exchange ->
            val attempt = requestCount.incrementAndGet()
            val bodyStr = exchange.requestBody.reader(Charsets.UTF_8).readText()
            if (bodyStr.isNotBlank()) {
                capturedBodies.add(JSONObject(bodyStr))
            }

            if (attempt == 1) {
                val errorJson = JSONObject()
                    .put(
                        "error",
                        JSONObject()
                            .put("code", 400)
                            .put("message", "Invalid thought signature.")
                            .put("status", "INVALID_ARGUMENT")
                    )
                    .toString()
                    .toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(400, errorJson.size.toLong())
                exchange.responseBody.use { it.write(errorJson) }
            } else {
                val sse = ("data: " + JSONObject().put(
                    "candidates",
                    JSONArray().put(
                        JSONObject()
                            .put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "重试成功后的回复"))))
                            .put("finishReason", "STOP")
                    )
                ).toString() + "\n\ndata: [DONE]\n\n").toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { it.write(sse) }
            }
        }

        server.start()
        try {
            val port = server.address.port
            val baseUrl = "http://127.0.0.1:$port/publishers/google/models"

            val staleSig = "AY89a1MismatchedSignatureFromDifferentKeyOrEndpoint"
            val assistantMsg = JSONObject()
                .put("role", "assistant")
                .put("content", "调用工具中")
                .put("thoughtSignature", staleSig)
                .put(
                    "tool_calls",
                    JSONArray().put(
                        JSONObject()
                            .put("id", "call_1")
                            .put("type", "function")
                            .put("thoughtSignature", staleSig)
                            .put("function", JSONObject().put("name", "status").put("arguments", "{}"))
                    )
                )
            val toolMsg = JSONObject()
                .put("role", "tool")
                .put("tool_call_id", "call_1")
                .put("content", "{\"status\":\"ok\"}")

            val request = ProviderRequest(
                config = AgentModelClient.ModelConfig(
                    providerSourceType = "gemini",
                    baseUrl = baseUrl,
                    apiKey = "test-key",
                    model = "gemini-4-argon",
                    systemPrompt = "",
                    thinkingEnabled = true,
                    reasoningEffort = io.github.mangi.eta.data.model.ReasoningEffort.DEFAULT,
                ),
                messages = JSONArray()
                    .put(JSONObject().put("role", "user").put("content", "检查状态"))
                    .put(assistantMsg)
                    .put(toolMsg),
                tools = JSONArray(),
            )

            val response = GeminiGenerateContentProvider.complete(request, AgentRunController()) {}

            // 1. 验证完成自动重试并成功获取回复
            assertEquals("重试成功后的回复", response.assistantMessage.getString("content"))
            assertEquals(2, requestCount.get())

            // 2. 验证第二次请求被以 ignoreThoughtSignatures = true 重新构建：
            // 首个请求带有 staleSig，第二请求被自动降级为官方豁免哨兵 skip_thought_signature_validator
            val firstBody = capturedBodies[0].toString()
            val secondBody = capturedBodies[1].toString()
            assertTrue(firstBody.contains(staleSig))
            assertFalse(secondBody.contains(staleSig))
            assertTrue(secondBody.contains("skip_thought_signature_validator"))
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
