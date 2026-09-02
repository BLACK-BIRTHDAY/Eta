package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.agent.runtime.AssistantBlockKind
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.UUID
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal object GeminiGenerateContentProvider : AgentProviderClient {
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    override val id: String = "gemini_generate_content"

    override val capabilities: ProviderCapabilities =
        ProviderCapabilities(
            endpoint = EndpointKind.GEMINI_GENERATE_CONTENT,
            streamingText = true,
            streamingToolCalls = true,
            imageInput = true,
            toolResultImages = false,
            strictTools = false,
            parallelToolCalls = true,
            systemPromptRole = SystemPromptRole.SYSTEM,
        )

    override fun complete(
        request: ProviderRequest,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit,
    ): ProviderResponse {
        val config = request.config
        val url = ProviderUrls.geminiStreamGenerateContentUrl(
            baseUrl = config.baseUrl,
            apiVersion = config.geminiApiVersion.ifBlank { "v1beta" },
            model = config.model,
        )

        val headers = Headers.Builder()
            .add("Content-Type", "application/json; charset=utf-8")
            .add("Accept", "text/event-stream")
            .apply {
                if (config.apiKey.isNotBlank()) {
                    add("x-goog-api-key", config.apiKey)
                }
                CustomHeaderFilter.mergeInto(this, config.customHeaders)
            }
            .build()

        val httpRequest = Request.Builder()
            .url(url)
            .headers(headers)
            .post(
                buildRequestJson(config, request.messages, request.tools)
                    .toString()
                    .toRequestBody(JSON_MEDIA_TYPE),
            )
            .build()

        val call = AgentHttpClient.client.newCall(httpRequest)
        val binding = runController.register { call.cancel() }
        try {
            runController.throwIfCancelled()
            onEvent(ProviderEvent.RequestStarted)
            call.execute().use { response ->
                onEvent(ProviderEvent.ResponseHeaders(response.code))
                runController.throwIfCancelled()
                if (!response.isSuccessful) {
                    val errorBody = response.body.string()
                    error("Gemini API error (HTTP " + response.code + "): " + errorBody.take(600))
                }
                val assistant = readStreamingAssistantMessage(
                    response.body.byteStream(),
                    runController,
                    onEvent,
                )
                onEvent(
                    ProviderEvent.Completed(
                        assistant.optString("finish_reason").ifBlank { null },
                    ),
                )
                return ProviderResponse(assistant)
            }
        } finally {
            binding.close()
        }
    }

    private fun buildRequestJson(
        config: AgentModelClient.ModelConfig,
        messages: JSONArray,
        tools: JSONArray,
    ): JSONObject {
        val systemParts = JSONArray()
        val contents = JSONArray()

        for (i in 0 until messages.length()) {
            val msg = messages.optJSONObject(i) ?: continue
            when (msg.optString("role")) {
                "system", "developer" -> {
                    val text = providerContentText(msg.opt("content"))
                    if (text.isNotBlank()) {
                        systemParts.put(JSONObject().put("text", text))
                    }
                }
                "user" -> {
                    contents.put(
                        JSONObject()
                            .put("role", "user")
                            .put("parts", convertUserParts(msg.opt("content"))),
                    )
                }
                "assistant" -> {
                    contents.put(
                        JSONObject()
                            .put("role", "model")
                            .put("parts", convertModelParts(msg)),
                    )
                }
                "tool" -> {
                    contents.put(
                        JSONObject()
                            .put("role", "user")
                            .put(
                                "parts",
                                JSONArray().put(
                                     JSONObject().put(
                                         "functionResponse",
                                         JSONObject()
                                             .put("name", msg.optString("name").ifBlank { "tool" })
                                             .put(
                                                 "response",
                                                 JSONObject().put("content", msg.optString("content")),
                                             ),
                                     ),
                                ),
                            )
                    )
                }
            }
        }

        return JSONObject().apply {
            put("contents", contents)
            if (systemParts.length() > 0) {
                put("systemInstruction", JSONObject().put("parts", systemParts))
            }
            convertTools(tools)?.let { put("tools", it) }
            RequestBodyMerge.mergeCustomBody(this, config.customBody)
            ProviderReasoning.applyGeminiRequest(this, config)
        }
    }

    private fun convertUserParts(content: Any?): JSONArray =
        when (content) {
            is JSONArray -> JSONArray().apply {
                for (i in 0 until content.length()) {
                    val item = content.optJSONObject(i) ?: continue
                    when (item.optString("type")) {
                        "text" -> put(JSONObject().put("text", item.optString("text")))
                        "image_url" -> convertImagePart(item)?.let(::put)
                    }
                }
            }
            else -> JSONArray().put(JSONObject().put("text", providerContentText(content)))
        }

    private fun convertImagePart(item: JSONObject): JSONObject? {
        val url = item.optJSONObject("image_url")?.optString("url").orEmpty()
        if (!url.startsWith("data:", ignoreCase = true)) return null
        val comma = url.indexOf(',')
        if (comma <= 5) return null
        val mimeType = url.substring(5, comma).substringBefore(';')
        val data = url.substring(comma + 1)
        return JSONObject().put(
            "inlineData",
            JSONObject().put("mimeType", mimeType).put("data", data),
        )
    }

    private fun convertModelParts(msg: JSONObject): JSONArray {
        val parts = JSONArray()
        val text = providerContentText(msg.opt("content"))
        if (text.isNotBlank() && text != "null") {
            parts.put(JSONObject().put("text", text))
        }
        val toolCalls = msg.optJSONArray("tool_calls")
        if (toolCalls != null) {
            for (i in 0 until toolCalls.length()) {
                val call = toolCalls.optJSONObject(i) ?: continue
                val fn = call.optJSONObject("function") ?: continue
                parts.put(
                    JSONObject().put(
                        "functionCall",
                        JSONObject()
                            .put("name", fn.optString("name"))
                            .put("args", parseJsonObject(fn.optString("arguments"))),
                    )
                )
            }
        }
        return parts

    }

    private fun convertTools(tools: JSONArray): JSONArray? {
        if (tools.length() == 0) return null
        val declarations = JSONArray()
        for (i in 0 until tools.length()) {
            val fn = tools.optJSONObject(i)?.optJSONObject("function") ?: continue
            declarations.put(
                JSONObject()
                    .put("name", fn.optString("name"))
                    .put("description", fn.optString("description"))
                    .put("parameters", fn.optJSONObject("parameters") ?: JSONObject().put("type", "object")),
            )
        }
        return if (declarations.length() > 0) {
            JSONArray().put(JSONObject().put("functionDeclarations", declarations))
        } else null
    }

    private fun readStreamingAssistantMessage(
        stream: InputStream,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit,
    ): JSONObject {
        val textContent = StringBuilder()
        val reasoningContent = StringBuilder()
        val toolCalls = mutableListOf<JSONObject>()
        var finishReason: String? = null
        var usage: AgentTokenUsage? = null

        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            while (true) {
                runController.throwIfCancelled()
                val line = reader.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload.isBlank() || payload == "[DONE]") continue

                val chunk = JSONObject(payload)
                val candidates = chunk.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val candidate = candidates.getJSONObject(0)
                    candidate.optString("finishReason").takeIf { it.isNotBlank() }?.let {
                        finishReason = it
                    }

                    val content = candidate.optJSONObject("content")
                    val parts = content?.optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            if (part.optBoolean("thought", false)) {
                                val thoughtText = part.optString("text")
                                if (thoughtText.isNotEmpty()) {
                                    reasoningContent.append(thoughtText)
                                    onEvent(
                                        ProviderEvent.VisibleDelta(
                                            AssistantBlockKind.REASONING,
                                            thoughtText,
                                        ),
                                    )
                                }
                            } else if (part.has("text")) {
                                val delta = part.optString("text")
                                if (delta.isNotEmpty()) {
                                    textContent.append(delta)
                                    onEvent(
                                        ProviderEvent.VisibleDelta(
                                            AssistantBlockKind.TEXT,
                                            delta,
                                        ),
                                    )
                                }
                            } else if (part.has("functionCall")) {
                                val fnCall = part.getJSONObject("functionCall")
                                val toolCallJson = JSONObject()
                                    .put("id", "call_" + UUID.randomUUID().toString().take(8))
                                   .put("type", "function")
                                   .put(
                                       "function",
                                       JSONObject()
                                           .put("name", fnCall.optString("name"))
                                          .put(
                                              "arguments",
                                              fnCall.optJSONObject("args")?.toString() ?: "{}",
                                          ),
                                   )
                                toolCalls.add(toolCallJson)
                            }
                        }
                    }
                }

                val usageMeta = chunk.optJSONObject("usageMetadata")
                if (usageMeta != null) {
                    usage = AgentTokenUsage(
                        inputTokens = usageMeta.optInt("promptTokenCount", 0),
                        outputTokens = usageMeta.optInt("candidatesTokenCount", 0),
                        totalTokens = usageMeta.optInt("totalTokenCount", 0),
                    )
                    onEvent(ProviderEvent.Usage(usage))
                }
            }
        }

        return JSONObject()
            .put("role", "assistant")
            .put("content", textContent.toString())
            .put("reasoning_content", reasoningContent.toString())
            .put("finish_reason", finishReason.orEmpty())
            .also {
                usage?.let { u -> it.put("usage", u.toJson()) }
                if (toolCalls.isNotEmpty()) {
                    it.put("tool_calls", JSONArray(toolCalls))
                }
            }
    }
}
