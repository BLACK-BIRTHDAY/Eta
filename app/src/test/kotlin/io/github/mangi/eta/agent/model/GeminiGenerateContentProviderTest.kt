package io.github.mangi.eta.agent.model

import com.sun.net.httpserver.HttpServer
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiGenerateContentProviderTest {

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
            String::class.java
        )
        method.isAccessible = true
        val parts = method.invoke(GeminiGenerateContentProvider, message, null) as JSONArray

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
            String::class.java
        )
        method.isAccessible = true
        val parts = method.invoke(GeminiGenerateContentProvider, message, null) as JSONArray

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
            String::class.java
        )
        method.isAccessible = true
        val parts = method.invoke(GeminiGenerateContentProvider, round2Message, sessionSig) as JSONArray

        assertEquals(1, parts.length())
        val fnPart = parts.getJSONObject(0)
        assertTrue(fnPart.has("functionCall"))
        assertEquals("skills_read_resource", fnPart.getJSONObject("functionCall").getString("name"))
        // 关键：无缝继承前序轮次的有效签名，杜绝 400
        assertEquals(sessionSig, fnPart.getString("thoughtSignature"))
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
