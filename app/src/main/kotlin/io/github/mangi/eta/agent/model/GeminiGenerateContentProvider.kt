package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.data.model.ReasoningEffort
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import okhttp3.Headers
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
            endpoint = EndpointKind.GEMINI_GENERATE_CONTENT,
            streamingText = true,
            streamingToolCalls = true,
            imageInput = true,
            toolResultImages = true,
            strictTools = false,
            parallelToolCalls = true,
        )

    override fun complete(
        request: ProviderRequest,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit
    ): ProviderResponse {
        val config = request.config
        val url = ProviderUrls.geminiStreamGenerateContentUrl(config.baseUrl, config.model)
        val payload = buildRequestJson(config, request.messages, request.tools)

        val isOfficialGoogle = config.baseUrl.contains("generativelanguage.googleapis.com")
        val headers = Headers.Builder()
            .add("Accept", "text/event-stream")
            .apply {
                if (config.apiKey.isNotBlank()) {
                    add("x-goog-api-key", config.apiKey)
                    if (!isOfficialGoogle) {
                        add("Authorization", "Bearer ${config.apiKey}")
                    }
                }
                CustomHeaderFilter.mergeInto(this, config.customHeaders)
            }
            .build()

        val httpRequest = Request.Builder()
            .url(url)
            .headers(headers)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
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
                    error("Gemini 接口返回 HTTP ${response.code}：${errorBody.compactError()}")
                }
                val assistant = readStreamingAssistantMessage(response.body.byteStream(), runController, onEvent)
                onEvent(ProviderEvent.Completed(assistant.optString("finish_reason").ifBlank { null }))
                return ProviderResponse(assistant)
            }
        } catch (throwable: Throwable) {
            runCatching { runController.throwIfCancelled() }
                .getOrElse { interruption -> throw interruption }
            throw throwable
        } finally {
            binding.close()
        }
    }

    private fun buildRequestJson(
        config: AgentModelClient.ModelConfig,
        messages: JSONArray,
        tools: JSONArray
    ): JSONObject {
        val contents = JSONArray()
        val systemParts = mutableListOf<String>()

        // 1. Build toolNamesById from all assistant messages
        val toolNamesById = mutableMapOf<String, String>()
        for (i in 0 until messages.length()) {
            val msg = messages.optJSONObject(i) ?: continue
            val rawCalls = msg.optJSONArray("tool_calls") ?: continue
            for (j in 0 until rawCalls.length()) {
                val call = rawCalls.optJSONObject(j) ?: continue
                val id = call.optString("id")
                val fnName = call.optJSONObject("function")?.optString("name")
                if (id.isNotBlank() && !fnName.isNullOrBlank()) {
                    toolNamesById[id] = fnName
                }
            }
        }

        // 2. Group into alternating user and model turns
        var currentRole: String? = null
        var currentParts = JSONArray()

        fun flushCurrentTurn() {
            val role = currentRole ?: return
            if (currentParts.length() > 0) {
                contents.put(
                    JSONObject()
                        .put("role", role)
                        .put("parts", currentParts)
                )
            }
            currentRole = null
            currentParts = JSONArray()
        }

        fun ensureTurn(role: String) {
            if (currentRole != role) {
                flushCurrentTurn()
                currentRole = role
            }
        }

        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            val role = message.optString("role")
            when (role) {
                "system" -> {
                    val text = providerMessageText(message.opt("content"))
                    if (text.isNotBlank()) systemParts.add(text)
                }
                "user" -> {
                    ensureTurn("user")
                    val parts = convertUserContent(message.opt("content"))
                    for (p in 0 until parts.length()) {
                        currentParts.put(parts.getJSONObject(p))
                    }
                }
                "assistant" -> {
                    val parts = convertAssistantContent(message)
                    if (parts.length() > 0) {
                        ensureTurn("model")
                        for (p in 0 until parts.length()) {
                            currentParts.put(parts.getJSONObject(p))
                        }
                    }
                }
                "tool" -> {
                    ensureTurn("user")
                    val callId = message.optString("tool_call_id")
                    val name = message.optString("name").ifBlank { toolNamesById[callId] ?: "tool" }
                    val contentStr = message.optString("content")
                    val responseObj = runCatching { JSONObject(contentStr) }.getOrElse {
                        JSONObject().put("output", contentStr)
                    }
                    currentParts.put(
                        JSONObject().put(
                            "functionResponse",
                            JSONObject()
                                .put("name", name)
                                .put("response", responseObj)
                        )
                    )
                }
            }
        }
        flushCurrentTurn()

        val normalizedContents = when {
            contents.length() > 0 && contents.optJSONObject(0)?.optString("role") == "model" -> {
                JSONArray().apply {
                    put(
                        JSONObject()
                            .put("role", "user")
                            .put("parts", JSONArray().put(JSONObject().put("text", "（继续执行上下文中的任务）")))
                    )
                    for (i in 0 until contents.length()) {
                        put(contents.get(i))
                    }
                }
            }
            contents.length() == 0 -> {
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", "")))
                )
            }
            else -> contents
        }

        val generationConfig = JSONObject()
        if (config.thinkingEnabled) {
            val budget = when (config.effectiveReasoningEffort) {
                ReasoningEffort.OFF -> 0
                ReasoningEffort.DEFAULT -> -1 // Google 官方原生自适应思考预算
                ReasoningEffort.LOW -> 4096
                ReasoningEffort.MEDIUM -> 16384
                ReasoningEffort.HIGH -> 32768
                ReasoningEffort.XHIGH, ReasoningEffort.MAX -> 65535
                else -> -1
            }
            generationConfig.put("thinkingConfig", JSONObject().put("thinkingBudget", budget))
        } else {
            generationConfig.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        }
        // 若是生图模型（modelId 含 image 或包含图片输出模态）：
        if (config.model.contains("image", ignoreCase = true)) {
            generationConfig.put("responseModalities", JSONArray().put("TEXT").put("IMAGE"))
            generationConfig.put("imageConfig", JSONObject().apply {
                put("aspectRatio", "1:1")
                put("outputMimeType", "image/png")
            })
        }

        return JSONObject()
            .put("contents", normalizedContents)
            .also { root ->
                val system = systemParts.joinToString("\n\n").trim()
                if (system.isNotBlank()) {
                    root.put(
                        "systemInstruction",
                        JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system)))
                    )
                }
                convertTools(tools)?.let { root.put("tools", JSONArray().put(it)) }
                if (tools.length() > 0) {
                    root.put(
                        "toolConfig",
                        JSONObject().put("functionCallingConfig", JSONObject().put("mode", "AUTO"))
                    )
                }
                root.put("generationConfig", generationConfig)
                RequestBodyMerge.mergeCustomBody(root, config.customBody)
            }
    }

    private fun convertUserContent(content: Any?): JSONArray =
        when (content) {
            is JSONArray -> JSONArray().also { out ->
                for (index in 0 until content.length()) {
                    val item = content.optJSONObject(index) ?: continue
                    when (item.optString("type")) {
                        "text" -> item.optString("text")
                            .takeIf { it.isNotBlank() }
                            ?.let { out.put(JSONObject().put("text", it)) }
                        "image_url" -> convertImageBlock(item)?.let(out::put)
                    }
                }
                if (out.length() == 0) out.put(JSONObject().put("text", ""))
            }
            else -> JSONArray().put(JSONObject().put("text", providerMessageText(content)))
        }

    private fun convertAssistantContent(message: JSONObject): JSONArray {
        val parts = JSONArray()
        providerMessageText(message.opt("content"))
            .takeIf { it.isNotBlank() && it != "null" }
            ?.let { parts.put(JSONObject().put("text", it)) }
        val toolCalls = message.optJSONArray("tool_calls")
        if (toolCalls != null) {
            for (index in 0 until toolCalls.length()) {
                val toolCall = toolCalls.optJSONObject(index) ?: continue
                val function = toolCall.optJSONObject("function") ?: continue
                val name = function.optString("name")
                if (name.isBlank()) continue
                val args = parseJsonObject(function.optString("arguments"))
                parts.put(
                    JSONObject().put(
                        "functionCall",
                        JSONObject().put("name", name).put("args", args)
                    )
                )
            }
        }
        return parts
    }

    private fun convertImageBlock(block: JSONObject): JSONObject? {
        val imageUrl = block.optJSONObject("image_url") ?: return null
        val url = imageUrl.optString("url")
        if (!url.startsWith("data:")) return null
        val commaIndex = url.indexOf(',')
        if (commaIndex == -1) return null
        val header = url.substring(0, commaIndex)
        val data = url.substring(commaIndex + 1)
        val mimeType = header.substringAfter("data:").substringBefore(";")
        return JSONObject().put(
            "inlineData",
            JSONObject()
                .put("mimeType", mimeType)
                .put("data", data)
        )
    }

    private fun convertTools(tools: JSONArray): JSONObject? {
        if (tools.length() == 0) return null
        val declarations = JSONArray()
        for (index in 0 until tools.length()) {
            val item = tools.optJSONObject(index) ?: continue
            val function = item.optJSONObject("function") ?: continue
            val name = function.optString("name")
            if (name.isBlank()) continue
            declarations.put(
                JSONObject()
                    .put("name", name)
                    .put("description", function.optString("description"))
                    .put("parameters", function.optJSONObject("parameters") ?: JSONObject().put("type", "object"))
            )
        }
        if (declarations.length() == 0) return null
        return JSONObject().put("functionDeclarations", declarations)
    }

    private fun readStreamingAssistantMessage(
        stream: InputStream,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit
    ): JSONObject {
        val fullText = StringBuilder()
        val fullReasoning = StringBuilder()
        val toolCalls = JSONArray()
        var finishReason: String? = null
        var usage: AgentTokenUsage? = null

        var currentBlockKind: AssistantBlockKind? = null
        var blockIndex = 0

        fun closeCurrentBlock() {
            val kind = currentBlockKind ?: return
            val content = if (kind == AssistantBlockKind.THINKING) fullReasoning.toString() else fullText.toString()
            onEvent(ProviderEvent.BlockEnd(kind = kind, index = blockIndex, content = content))
            currentBlockKind = null
            blockIndex++
        }

        fun ensureBlock(kind: AssistantBlockKind) {
            if (currentBlockKind != kind) {
                closeCurrentBlock()
                currentBlockKind = kind
                onEvent(ProviderEvent.BlockStart(kind = kind, index = blockIndex))
            }
        }

        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            while (true) {
                runController.throwIfCancelled()
                val line = reader.readLine() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data.isBlank() || data == "[DONE]") continue

                val json = runCatching { JSONObject(data) }.getOrNull() ?: continue

                // Handle promptFeedback safety block
                val promptFeedback = json.optJSONObject("promptFeedback")
                if (promptFeedback != null) {
                    val blockReason = promptFeedback.optString("blockReason").ifBlank { promptFeedback.optString("block_reason") }
                    val candidates = json.optJSONArray("candidates")
                    if (blockReason.isNotBlank() && (candidates == null || candidates.length() == 0)) {
                        error("Gemini 内容安全拦截: $blockReason")
                    }
                }

                // Handle usage metadata
                val usageMetadata = json.optJSONObject("usageMetadata")
                if (usageMetadata != null) {
                    val promptTokens = usageMetadata.optInt("promptTokenCount", -1).takeIf { it >= 0 }
                    val candidatesTokens = usageMetadata.optInt("candidatesTokenCount", -1).takeIf { it >= 0 }
                    val thoughtsTokens = usageMetadata.optInt("thoughtsTokenCount", -1).takeIf { it >= 0 }
                    val cachedTokens = usageMetadata.optInt("cachedContentTokenCount", -1).takeIf { it >= 0 }
                    val totalTokens = usageMetadata.optInt("totalTokenCount", -1).takeIf { it >= 0 }
                        ?: promptTokens?.let { p -> (candidatesTokens ?: 0) + p }

                    usage = AgentTokenUsage(
                        contextTokens = totalTokens,
                        inputTokens = promptTokens,
                        outputTokens = candidatesTokens,
                        reasoningTokens = thoughtsTokens,
                        cachedTokens = cachedTokens,
                    )
                    onEvent(ProviderEvent.Usage(usage))
                }

                val candidates = json.optJSONArray("candidates") ?: continue
                if (candidates.length() == 0) continue
                val candidate = candidates.optJSONObject(0) ?: continue

                candidate.optString("finishReason").ifBlank { null }?.let { finishReason = it }

                val contentObj = candidate.optJSONObject("content") ?: continue
                val parts = contentObj.optJSONArray("parts") ?: continue

                for (p in 0 until parts.length()) {
                    val part = parts.optJSONObject(p) ?: continue
                    val text = part.optString("text")
                    val isThought = part.optBoolean("thought", false)

                    if (text.isNotEmpty()) {
                        if (isThought) {
                            ensureBlock(AssistantBlockKind.THINKING)
                            fullReasoning.append(text)
                            onEvent(ProviderEvent.BlockDelta(kind = AssistantBlockKind.THINKING, index = blockIndex, delta = text))
                        } else {
                            ensureBlock(AssistantBlockKind.TEXT)
                            fullText.append(text)
                            onEvent(ProviderEvent.BlockDelta(kind = AssistantBlockKind.TEXT, index = blockIndex, delta = text))
                        }
                    }

                    val inlineData = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data")
                    if (inlineData != null) {
                        val mimeType = inlineData.optString("mimeType").ifBlank { inlineData.optString("mime_type") }
                        val imgData = inlineData.optString("data")
                        if (mimeType.isNotBlank() && imgData.isNotBlank()) {
                            val imgMd = "\n\n![Generated Image](data:$mimeType;base64,$imgData)\n\n"
                            ensureBlock(AssistantBlockKind.TEXT)
                            fullText.append(imgMd)
                            onEvent(ProviderEvent.BlockDelta(kind = AssistantBlockKind.TEXT, index = blockIndex, delta = imgMd))
                        }
                    }

                    val functionCall = part.optJSONObject("functionCall")
                    if (functionCall != null) {
                        closeCurrentBlock()
                        val name = functionCall.optString("name")
                        val argsObj = functionCall.optJSONObject("args") ?: JSONObject()
                        val toolCallId = "call_${toolCalls.length()}_${System.currentTimeMillis()}"
                        val toolCall = JSONObject()
                            .put("id", toolCallId)
                            .put("type", "function")
                            .put(
                                "function",
                                JSONObject()
                                    .put("name", name)
                                    .put("arguments", argsObj.toString())
                            )
                        toolCalls.put(toolCall)
                        onEvent(ProviderEvent.BlockStart(kind = AssistantBlockKind.TOOL_CALL, index = blockIndex, blockId = toolCallId, name = name))
                        onEvent(ProviderEvent.BlockEnd(kind = AssistantBlockKind.TOOL_CALL, index = blockIndex, blockId = toolCallId, name = name, content = argsObj.toString()))
                        blockIndex++
                    }
                }
            }
        }

        closeCurrentBlock()

        val normalizedFinishReason = when {
            toolCalls.length() > 0 && (finishReason == null || finishReason.equals("STOP", ignoreCase = true)) -> "tool_calls"
            finishReason.equals("MAX_TOKENS", ignoreCase = true) -> "length"
            finishReason.equals("SAFETY", ignoreCase = true) -> "content_filter"
            finishReason.equals("STOP", ignoreCase = true) -> "stop"
            else -> finishReason?.lowercase()
        }

        return JSONObject()
            .put("role", "assistant")
            .put("content", fullText.toString())
            .also { assistant ->
                if (fullReasoning.isNotEmpty()) {
                    assistant.put("reasoning", fullReasoning.toString())
                    assistant.put("reasoning_content", fullReasoning.toString())
                }
                if (toolCalls.length() > 0) {
                    assistant.put("tool_calls", toolCalls)
                }
                if (usage != null) {
                    assistant.put("usage", usage.toJson())
                }
                if (!normalizedFinishReason.isNullOrBlank()) {
                    assistant.put("finish_reason", normalizedFinishReason)
                }
            }
    }

    private fun AgentTokenUsage.toJson(): JSONObject =
        JSONObject().also { json ->
            contextTokens?.let { json.put("total_tokens", it) }
            inputTokens?.let { json.put("input_tokens", it) }
            outputTokens?.let { json.put("output_tokens", it) }
            reasoningTokens?.let { json.put("reasoning_tokens", it) }
            cachedTokens?.let { json.put("cached_tokens", it) }
        }

    private fun parseJsonObject(raw: String?): JSONObject =
        raw?.trim()?.takeIf { it.startsWith("{") }?.let {
            runCatching { JSONObject(it) }.getOrNull()
        } ?: JSONObject()

    private fun String.compactError(): String =
        replace('\n', ' ')
            .replace('\r', ' ')
            .let { if (it.length > MAX_ERROR_CHARS) it.take(MAX_ERROR_CHARS) + "..." else it }
}
