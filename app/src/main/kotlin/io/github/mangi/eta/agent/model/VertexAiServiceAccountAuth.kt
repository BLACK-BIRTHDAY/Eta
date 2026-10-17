package io.github.mangi.eta.agent.model

import java.net.URLEncoder
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Gemini Enterprise Agent Platform (Vertex AI) 服务账号认证与端点路由支持。
 *
 * 允许直接将 Google Cloud Service Account JSON 凭证填在 Provider 的 API Key 字段中（或从文件导入），
 * 运行时使用 Android/JDK 标准库零依赖签发 RS256 JWT，并向 oauth2.googleapis.com/token 换取
 * 1 小时有效期的 OAuth2 Bearer Token（带内存缓存与提前刷新缓冲）。
 */
internal object VertexAiServiceAccountAuth {
    private val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded".toMediaType()
    private const val DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token"
    private const val REFRESH_BUFFER_MILLIS = 60_000L

    // 同时包含 cloud-platform（用于 aiplatform.googleapis.com 推理与 publisher 模型列表）
    // 与 generative-language.retriever（用于 generativelanguage.googleapis.com 模型元数据拉取）。
    val VERTEX_OAUTH_SCOPES = listOf(
        "https://www.googleapis.com/auth/cloud-platform",
        "https://www.googleapis.com/auth/generative-language.retriever",
    )

    data class ServiceAccountCredentials(
        val projectId: String,
        val clientEmail: String,
        val privateKeyPem: String,
        val privateKeyId: String? = null,
        val tokenUri: String = DEFAULT_TOKEN_URI,
        val defaultLocation: String = "global",
    )

    private data class CachedToken(
        val accessToken: String,
        val expiresAtMillis: Long,
    )

    private val tokenCache = ConcurrentHashMap<String, CachedToken>()

    /**
     * 判断传入的 apiKey 字符串是否为 JSON 对象形式的凭证。
     */
    fun looksLikeServiceAccountJson(raw: String?): Boolean {
        val trimmed = raw?.trim().orEmpty()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return false
        return runCatching { JSONObject(trimmed) }.isSuccess
    }

    /**
     * 解析并校验 Service Account JSON 凭证。
     */
    fun parseServiceAccountJson(raw: String): ServiceAccountCredentials {
        val trimmed = raw.trim()
        val json = try {
            JSONObject(trimmed)
        } catch (e: Exception) {
            error("无效的 JSON 服务账号凭证：${e.message ?: "JSON 解析失败"}")
        }
        if (json.has("web") || json.has("installed")) {
            error("检测到 OAuth 客户端 JSON 而非服务账号（Service Account）密钥 JSON，请导入包含 private_key 与 client_email 的服务账号 JSON")
        }
        val projectId = json.optString("project_id").trim()
        val clientEmail = json.optString("client_email").trim()
        val privateKey = json.optString("private_key").trim()
        val privateKeyId = json.optString("private_key_id").trim().ifBlank { null }
        val tokenUri = json.optString("token_uri").trim().ifBlank { DEFAULT_TOKEN_URI }
        val location = json.optString("location")
            .ifBlank { json.optString("region") }
            .trim()
            .ifBlank { "global" }

        if (projectId.isBlank()) {
            error("服务账号 JSON 缺少 project_id 字段")
        }
        if (clientEmail.isBlank()) {
            error("服务账号 JSON 缺少 client_email 字段")
        }
        if (privateKey.isBlank() || !privateKey.contains("PRIVATE KEY")) {
            error("服务账号 JSON 缺少有效的 private_key (PEM) 字段")
        }

        return ServiceAccountCredentials(
            projectId = projectId,
            clientEmail = clientEmail,
            privateKeyPem = privateKey,
            privateKeyId = privateKeyId,
            tokenUri = tokenUri,
            defaultLocation = location,
        )
    }

    /**
     * 根据服务账号信息生成默认的 Vertex AI / Gemini Enterprise Base URL。
     */
    fun buildDefaultVertexBaseUrl(
        projectId: String,
        location: String = "global",
    ): String {
        val loc = location.trim().ifBlank { "global" }
        val host = vertexHostForLocation(loc)
        return "https://$host/v1/projects/${projectId.trim()}/locations/$loc"
    }

    fun vertexHostForLocation(location: String): String {
        val loc = location.trim().lowercase()
        return when {
            loc == "global" || loc == "us" -> "aiplatform.googleapis.com"
            loc == "eu" -> "aiplatform.eu.rep.googleapis.com"
            else -> "$loc-aiplatform.googleapis.com"
        }
    }

    /**
     * 从 Base URL 中提取用户指定的 location/region（若有），否则使用默认值。
     */
    fun resolveLocation(baseUrl: String, defaultLocation: String = "global"): String {
        val normalized = baseUrl.trim()
        Regex("""/locations/([^/?#]+)""", RegexOption.IGNORE_CASE)
            .find(normalized)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        Regex("""^https?://([a-z0-9-]+)-aiplatform\.googleapis\.com""", RegexOption.IGNORE_CASE)
            .find(normalized)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        if (normalized.contains("aiplatform.eu.rep.googleapis.com", ignoreCase = true)) {
            return "eu"
        }

        return defaultLocation.ifBlank { "global" }
    }

    /**
     * 从 Base URL 中提取用户显式指定的 projectId，否则回退到服务账号中的 project_id。
     */
    fun resolveProjectId(baseUrl: String, fallbackProjectId: String): String {
        Regex("""/projects/([^/?#]+)""", RegexOption.IGNORE_CASE)
            .find(baseUrl.trim())
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return fallbackProjectId
    }

    /**
     * 规范化 Vertex AI 的模型 ID（如去除 publishers/google/models/ 或 models/ 前缀）。
     */
    fun normalizeVertexModelId(model: String): String {
        val trimmed = model.trim()
            .removePrefix("models/")
            .replace(Regex("""^projects/[^/]+/locations/[^/]+/publishers/google/models/""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^publishers/google/models/""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^google/""", RegexOption.IGNORE_CASE), "")
        return trimmed.ifBlank { model.trim() }
    }

    /**
     * 构造 Vertex AI / Gemini Enterprise 的 streamGenerateContent 完整 URL。
     */
    fun buildStreamGenerateContentUrl(
        baseUrl: String,
        model: String,
        credentials: ServiceAccountCredentials? = null,
    ): String {
        val canonicalModel = normalizeVertexModelId(model)
        val trimmedBase = baseUrl.trim().trimEnd('/')

        // 若用户在自定义 Base URL 中已经写到了 publishers/google/models
        if (trimmedBase.contains("/publishers/google/models", ignoreCase = true)) {
            return "$trimmedBase/$canonicalModel:streamGenerateContent?alt=sse"
        }

        // 若用户在 Base URL 中已经包含 /projects/{project}/locations/{location}
        val projectInUrl = Regex("""/projects/([^/?#]+)/locations/([^/?#]+)""", RegexOption.IGNORE_CASE)
            .find(trimmedBase)
        if (projectInUrl != null) {
            val prefix = trimmedBase.substring(0, projectInUrl.range.last + 1)
            return "$prefix/publishers/google/models/$canonicalModel:streamGenerateContent?alt=sse"
        }

        // 使用服务账号凭证的 project_id 与解析出的 location 组装标准 Vertex AI URL
        if (credentials != null) {
            val project = resolveProjectId(trimmedBase, credentials.projectId)
            val location = resolveLocation(trimmedBase, credentials.defaultLocation)
            // 如果用户填的是自定义反代域名（非 googleapis.com），保留用户的 scheme + host
            val isGoogleHost = trimmedBase.contains("googleapis.com", ignoreCase = true)
            val hostPrefix = if (isGoogleHost || trimmedBase.isBlank()) {
                "https://${vertexHostForLocation(location)}"
            } else {
                trimmedBase
            }
            val apiVersion = if (trimmedBase.endsWith("/v1beta1", ignoreCase = true)) "v1beta1" else "v1"
            val cleanHostPrefix = hostPrefix
                .removeSuffix("/v1")
                .removeSuffix("/v1beta")
                .removeSuffix("/v1beta1")
            return "$cleanHostPrefix/$apiVersion/projects/$project/locations/$location/publishers/google/models/$canonicalModel:streamGenerateContent?alt=sse"
        }

        // Express Key 或无 Project ID 的回退路径
        return "$trimmedBase/v1/publishers/google/models/$canonicalModel:streamGenerateContent?alt=sse"
    }

    /**
     * 签署 RS256 JWT（供单元测试及 OAuth2 令牌交换使用）。
     */
    internal fun createSignedJwt(
        credentials: ServiceAccountCredentials,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
    ): String {
        val headerJson = JSONObject().apply {
            put("alg", "RS256")
            put("typ", "JWT")
            if (!credentials.privateKeyId.isNullOrBlank()) {
                put("kid", credentials.privateKeyId)
            }
        }
        val payloadJson = JSONObject().apply {
            put("iss", credentials.clientEmail)
            put("sub", credentials.clientEmail)
            put("aud", credentials.tokenUri)
            put("iat", nowEpochSeconds)
            put("exp", nowEpochSeconds + 3600L)
            put("scope", VERTEX_OAUTH_SCOPES.joinToString(" "))
        }

        val encoder = Base64.getUrlEncoder().withoutPadding()
        val headerB64 = encoder.encodeToString(headerJson.toString().toByteArray(Charsets.UTF_8))
        val payloadB64 = encoder.encodeToString(payloadJson.toString().toByteArray(Charsets.UTF_8))
        val signingInput = "$headerB64.$payloadB64"

        val privateKey = parsePkcs8PrivateKey(credentials.privateKeyPem)
        val signatureBytes = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update(signingInput.toByteArray(Charsets.UTF_8))
            sign()
        }
        val signatureB64 = encoder.encodeToString(signatureBytes)
        return "$signingInput.$signatureB64"
    }

    private fun parsePkcs8PrivateKey(pem: String) =
        runCatching {
            val normalizedPem = pem
                .replace("\\n", "\n")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace(Regex("\\s+"), "")
            val keyBytes = Base64.getDecoder().decode(normalizedPem)
            val keySpec = PKCS8EncodedKeySpec(keyBytes)
            KeyFactory.getInstance("RSA").generatePrivate(keySpec)
        }.getOrElse { e ->
            error("解析服务账号 RSA 私钥失败：${e.message ?: e.javaClass.simpleName}")
        }

    /**
     * 获取有效的 OAuth2 Access Token（命中缓存且距过期 >60s 时直接返回）。
     */
    fun getAccessToken(
        credentials: ServiceAccountCredentials,
        nowMillis: Long = System.currentTimeMillis(),
    ): String {
        val cacheKey = "${credentials.clientEmail}|${credentials.privateKeyId.orEmpty()}|${credentials.tokenUri}"
        val cached = tokenCache[cacheKey]
        if (cached != null && nowMillis < cached.expiresAtMillis - REFRESH_BUFFER_MILLIS) {
            return cached.accessToken
        }

        val assertion = createSignedJwt(credentials, nowMillis / 1000L)
        val formBody = buildString {
            append("grant_type=")
            append(URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", "UTF-8"))
            append("&assertion=")
            append(URLEncoder.encode(assertion, "UTF-8"))
        }

        val request = Request.Builder()
            .url(credentials.tokenUri)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .post(formBody.toRequestBody(FORM_MEDIA_TYPE))
            .build()

        AgentHttpClient.client.newCall(request).execute().use { response ->
            val bodyStr = response.body.string()
            if (!response.isSuccessful) {
                error("Vertex AI 服务账号换取访问令牌失败 (HTTP ${response.code}): ${bodyStr.take(400)}")
            }
            val tokenJson = runCatching { JSONObject(bodyStr) }.getOrNull()
                ?: error("Vertex AI 令牌响应不是合法 JSON")
            val accessToken = tokenJson.optString("access_token").trim()
            if (accessToken.isBlank()) {
                error("Vertex AI 令牌响应缺少 access_token 字段")
            }
            val expiresInSeconds = tokenJson.optLong("expires_in", 3600L).coerceAtLeast(60L)
            tokenCache[cacheKey] = CachedToken(
                accessToken = accessToken,
                expiresAtMillis = nowMillis + expiresInSeconds * 1000L,
            )
            return accessToken
        }
    }

    internal fun clearCacheForTests() {
        tokenCache.clear()
    }
}
