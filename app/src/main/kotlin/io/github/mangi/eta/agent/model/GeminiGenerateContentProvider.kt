package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal object GeminiGenerateContentProvider : AgentProviderClient {
    private const val MAX_ERROR_CHARS = 600
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    override val id: String = "gemini_generate_content"
    override val capabilities: ProviderCapabilities =
        ProviderCapabilities(
            supportsStreaming = true,
            supportsThinking = true,
            supportsStructuredOutputs = true,
            supportsToolCalling = true,
        )

    override fun complete(
        request: ProviderRequest,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit
    ): ProviderResponse {
        val model = request.model.trim().ifBlank { "gemini-3.7-flash" }
        val baseUrl = request.config.baseUrl.trim().removeSuffix("/")
        val apiKey = request.config.apiKey.trim()

        val url = if (baseUrl.contains("models/")) {
            "$baseUrl:streamGenerateContent?alt=sse"
        } else {
            "$baseUrl/v1beta/models/$model:streamGenerateContent?alt=sse"
        }

        val requestBodyJson = buildRequestBody(request)
        val httpRequest = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .apply {
                if (apiKey.isNotBlank()) {
                    addHeader("x-goog-api-key", apiKey)
                }
            }
            .post(requestBodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        var visibleText = ""
        var reasoningText = ""
        val toolCalls = mutableListOf<JSONObject>()
        var inputTokens: Int? = null
        var outputTokens: Int? = null
        var reasoningTokens: Int? = null
        var cachedTokens: Int? = null
        var finishReason: String? = null

        request.httpClient.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                val errBody = response.body?.string().orEmpty()
                error("Gemini API 错误 (HTTP " + response.code + ")：" + errBody.compactError())
            }

            val body = response.body ?: error("Gemini 响应体为空")
            val stream = body.byteStream()

            readSseStream(stream, runController) { eventData ->
                val json = try {
                    JSONObject(eventData)
                } catch (t: Throwable) {
                    return@readSseStream
                }

                // 1. Usage metadata
                val usageMetadata = json.optJSONObject("usageMetadata")
                if (usageMetadata != null) {
                    val promptTokens = usageMetadata.optInt("promptTokenCount", -1).takeIf { it >= 0 }
                    val candidatesTokens = usageMetadata.optInt("candidatesTokenCount", -1).takeIf { it >= 0 }
                    val thoughtsTokens = usageMetadata.optInt("thoughtsTokenCount", -1).takeIf { it >= 0 }

                    if (promptTokens != null) inputTokens = promptTokens
                    if (candidatesTokens != null) outputTokens = candidatesTokens
                    if (thoughtsTokens != null) reasoningTokens = thoughtsTokens

                    onEvent(
                        ProviderEvent.Usage(
                            AgentTokenUsage(
                                inputTokens = inputTokens,
                                outputTokens = outputTokens,
                                reasoningTokens = reasoningTokens,
                                cachedTokens = cachedTokens,
                            )
                        )
                    )
                }

                // 2. Candidates
                val candidates = json.optJSONArray("candidates") ?: return@readSseStream
                if (candidates.length() == 0) return@readSseStream
                val firstCandidate = candidates.optJSONObject(0) ?: return@readSseStream

                val candidateFinishReason = firstCandidate.optString("finishReason").takeIf { it.isNotBlank() }
                if (candidateFinishReason != null) {
                    finishReason = candidateFinishReason
                }

                val content = firstCandidate.optJSONObject("content") ?: return@readSseStream
                val parts = content.optJSONArray("parts") ?: return@readSseStream

                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue

                    // Thinking delta
                    val isThought = part.optBoolean("thought", false)
                    val text = part.optString("text", "")

                    if (text.isNotEmpty()) {
                        if (isThought) {
                            reasoningText += text
                            onEvent(ProviderEvent.ReasoningDelta(delta = text))
                        } else {
                            visibleText += text
                            onEvent(ProviderEvent.VisibleTextDelta(delta = text))
                        }
                    }

                    // Function call (tool call)
                    val functionCall = part.optJSONObject("functionCall")
                    if (functionCall != null) {
                        val callName = functionCall.optString("name")
                        val callArgs = functionCall.optJSONObject("args") ?: JSONObject()
                        val callId = "call_" + System.currentTimeMillis() + "_" + i

                        val toolCallJson = JSONObject().apply {
                            put("id", callId)
                            put("name", callName)
                            put("arguments", callArgs.toString())
                        }
                        toolCalls.add(toolCallJson)

                        onEvent(
                            ProviderEvent.ToolCall(
                                id = callId,
                                name = callName,
                                content = callArgs.toString(),
                                replaceContent = true,
                            )
                        )
                    }
                }
            }
        }

        val usage = AgentTokenUsage(
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            reasoningTokens = reasoningTokens,
            cachedTokens = cachedTokens,
        )

        return ProviderResponse(
            text = visibleText,
            reasoning = reasoningText.ifBlank { null },
            toolCalls = JSONArray(toolCalls),
            usage = usage,
            finishReason = finishReason,
            rawResponse = JSONObject().apply {
                put("text", visibleText)
                put("reasoning", reasoningText)
                put("tool_calls", JSONArray(toolCalls))
                put("usage", usage.toJson())
            }
        )
    }

    private fun buildRequestBody(request: ProviderRequest): JSONObject {
        val root = JSONObject()

        // System prompt
        val systemInstructionText = request.systemPrompt.trim()
        if (systemInstructionText.isNotEmpty()) {
            val systemPart = JSONObject().put("text", systemInstructionText)
            val systemInstruction = JSONObject().put("parts", JSONArray().put(systemPart))
            root.put("systemInstruction", systemInstruction)
        }

        // Contents (history + current messages)
        val contentsArray = JSONArray()
        val history = request.history ?: JSONArray()

        for (i in 0 until history.length()) {
            val item = history.optJSONObject(i) ?: continue
            val role = item.optString("role", "user")
            val partsArray = JSONArray()

            val text = item.optString("content", "")
            if (text.isNotEmpty()) {
                partsArray.put(JSONObject().put("text", text))
            }

            val toolCallResults = item.optJSONArray("tool_results")
            if (toolCallResults != null) {
                for (j in 0 until toolCallResults.length()) {
                    val res = toolCallResults.optJSONObject(j) ?: continue
                    val callName = res.optString("name", "tool")
                    val resultText = res.optString("content", "")
                    val responseJson = JSONObject().put("result", resultText)
                    val fnResponse = JSONObject().apply {
                        put("name", callName)
                        put("response", responseJson)
                    }
                    partsArray.put(JSONObject().put("functionResponse", fnResponse))
                }
            }

            if (partsArray.length() > 0) {
                val geminiRole = if (role == "assistant" || role == "model") "model" else "user"
                contentsArray.put(JSONObject().apply {
                    put("role", geminiRole)
                    put("parts", partsArray)
                })
            }
        }

        // Current message
        val currentParts = JSONArray()
        if (request.prompt.isNotEmpty()) {
            currentParts.put(JSONObject().put("text", request.prompt))
        }

        if (currentParts.length() > 0) {
            contentsArray.put(JSONObject().apply {
                put("role", "user")
                put("parts", currentParts)
            })
        }

        root.put("contents", contentsArray)

        // Tools
        val tools = request.tools
        if (tools != null && tools.length() > 0) {
            val functionDeclarations = JSONArray()
            for (i in 0 until tools.length()) {
                val tool = tools.optJSONObject(i) ?: continue
                val name = tool.optString("name")
                val description = tool.optString("description")
                val parameters = tool.optJSONObject("parameters") ?: JSONObject()

                val declaration = JSONObject().apply {
                    put("name", name)
                    if (description.isNotEmpty()) put("description", description)
                    put("parameters", parameters)
                }
                functionDeclarations.put(declaration)
            }
            if (functionDeclarations.length() > 0) {
                root.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", functionDeclarations)))
            }
        }

        // Generation config (Thinking & temperature)
        val generationConfig = JSONObject()
        val thinkingConfig = JSONObject()

        if (request.reasoningEffort != null) {
            thinkingConfig.put("thinkingBudget", -1)
            generationConfig.put("thinkingConfig", thinkingConfig)
        }

        if (generationConfig.length() > 0) {
            root.put("generationConfig", generationConfig)
        }

        return root
    }

    private fun readSseStream(
        stream: InputStream,
        runController: AgentRunController,
        onData: (String) -> Unit
    ) {
        val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
        var line: String?
        val dataBuffer = StringBuilder()

        while (reader.readLine().also { line = it } != null) {
            if (runController.isAborted()) {
                break
            }
            val l = line ?: break
            if (l.startsWith("data:")) {
                val dataContent = l.substring(5).trim()
                if (dataContent.isNotEmpty()) {
                    if (dataBuffer.isNotEmpty()) dataBuffer.append("\n")
                    dataBuffer.append(dataContent)
                }
            } else if (l.isBlank()) {
                if (dataBuffer.isNotEmpty()) {
                    onData(dataBuffer.toString())
                    dataBuffer.clear()
                }
            }
        }
        if (dataBuffer.isNotEmpty()) {
            onData(dataBuffer.toString())
        }
    }

    private fun AgentTokenUsage.toJson(): JSONObject =
        JSONObject().also { json ->
            inputTokens?.let { json.put("input_tokens", it) }
            outputTokens?.let { json.put("output_tokens", it) }
            reasoningTokens?.let { json.put("reasoning_tokens", it) }
            cachedTokens?.let { json.put("cached_tokens", it) }
        }

    private fun String.compactError(): String =
        replace('\n', ' ')
            .replace('\r', ' ')
            .let { if (it.length > MAX_ERROR_CHARS) it.take(MAX_ERROR_CHARS) + "..." else it }
}