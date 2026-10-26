package io.github.mangi.eta.agent.model

internal object ProviderUrls {
    fun normalizeBaseUrl(baseUrl: String): String =
        baseUrl.trim().trimEnd('/')

    fun openAiChatCompletionsUrl(baseUrl: String): String =
        appendPath(baseUrl, "chat/completions")

    fun openAiResponsesUrl(baseUrl: String): String =
        appendPath(baseUrl, "responses")

    fun openAiModelsUrl(baseUrl: String): String =
        appendPath(baseUrl, "models")

    fun anthropicMessagesUrl(baseUrl: String): String =
        appendPath(baseUrl, "v1/messages")

   fun anthropicModelsUrl(baseUrl: String): String =
       appendPath(baseUrl, "v1/models")

    fun isVertexAiEndpoint(baseUrl: String): Boolean =
        baseUrl.contains("aiplatform.googleapis.com", ignoreCase = true) ||
            baseUrl.contains("/publishers/google/models", ignoreCase = true) ||
            Regex("""/projects/[^/?#]+/locations/[^/?#]+""", RegexOption.IGNORE_CASE).containsMatchIn(baseUrl)

    fun vertexStreamGenerateContentUrl(baseUrl: String, model: String): String =
        VertexAiServiceAccountAuth.buildStreamGenerateContentUrl(baseUrl, model)

    fun geminiStreamGenerateContentUrl(baseUrl: String, model: String): String =
        appendPath(baseUrl, "v1beta/models/$model:streamGenerateContent?alt=sse")

    fun geminiInteractionsUrl(baseUrl: String): String =
        appendPath(baseUrl, "interactions")

    fun geminiInteractionModelUrl(baseUrl: String, model: String): String =
        appendPath(baseUrl, "models/$model")

    fun geminiModelsUrl(baseUrl: String): String {
        val normalized = normalizeBaseUrl(baseUrl)
        return when {
            normalized.endsWith("/v1beta") -> appendPath(normalized, "models")
            normalized == "https://generativelanguage.googleapis.com" -> appendPath(normalized, "v1beta/models")
            else -> appendPath(normalized, "models")
        }
    }

    fun geminiModelsUrl(baseUrl: String, apiKey: String): String {
        val base = geminiModelsUrl(baseUrl)
        return if (apiKey.isNotBlank()) "$base?key=$apiKey" else base
    }

    private fun appendPath(baseUrl: String, path: String): String =
        "${normalizeBaseUrl(baseUrl)}/${path.trimStart('/')}"
}
