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

   fun geminiStreamGenerateContentUrl(baseUrl: String, model: String): String =
       appendPath(baseUrl, "v1beta/models/$model:streamGenerateContent?alt=sse")

   fun geminiModelsUrl(baseUrl: String): String =
       appendPath(baseUrl, "v1beta/models")
   fun geminiModelsUrl(baseUrl: String, apiKey: String): String {
       val base = appendPath(baseUrl, "v1beta/models")
       return if (apiKey.isNotBlank()) "$base?key=$apiKey" else base
   }


   private fun appendPath(baseUrl: String, path: String): String =
        "${normalizeBaseUrl(baseUrl)}/${path.trimStart('/')}"
}
