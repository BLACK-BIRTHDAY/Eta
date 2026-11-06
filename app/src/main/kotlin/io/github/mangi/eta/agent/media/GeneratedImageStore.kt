package io.github.mangi.eta.agent.media

import io.github.mangi.eta.agent.runtime.AgentAppContext
import java.io.File
import java.security.MessageDigest
import java.util.Base64

internal object GeneratedImageStore {
    const val MAX_GENERATED_IMAGE_BYTES = 20 * 1024 * 1024

    private val INLINE_DATA_IMAGE_REGEX = Regex(
        """!\[([^\]]*)]\(\s*data:(image/[a-zA-Z0-9.+-]+);base64,([A-Za-z0-9+/=\s]+)\s*\)""",
        RegexOption.IGNORE_CASE
    )

    private val MARKDOWN_IMAGE_FOR_CONTEXT_REGEX = Regex(
        """!\[([^\]]*)]\(\s*(?:data:image/[^)]+|file:[^)]+)\s*\)""",
        RegexOption.IGNORE_CASE
    )

    private val BARE_DATA_IMAGE_REGEX = Regex(
        """data:image/[a-zA-Z0-9.+-]+;base64,[A-Za-z0-9+/=]+""",
        RegexOption.IGNORE_CASE
    )

    fun persistBase64(mimeType: String, base64Data: String, rootDir: File? = null): String? {
        val bytes = runCatching {
            Base64.getMimeDecoder().decode(base64Data.trim())
        }.getOrNull() ?: return null

        if (bytes.isEmpty() || bytes.size > MAX_GENERATED_IMAGE_BYTES) {
            return null
        }

        return runCatching {
            val dir = (rootDir
                ?: AgentAppContext.resolve()?.filesDir?.let { File(it, "generated_images") }
                ?: File(System.getProperty("java.io.tmpdir"), "eta-generated-images")
            ).apply { mkdirs() }

            val hashBytes = MessageDigest.getInstance("SHA-256").digest(bytes)
            val hash = hashBytes.joinToString("") { "%02x".format(it) }.take(24)

            val lowerMime = mimeType.lowercase()
            val ext = when {
                lowerMime.contains("png") -> "png"
                lowerMime.contains("webp") -> "webp"
                lowerMime.contains("gif") -> "gif"
                else -> "jpg"
            }

            val file = File(dir, "img-$hash.$ext")
            if (!file.exists() || file.length() != bytes.size.toLong()) {
                file.writeBytes(bytes)
            }
            file.toURI().toString()
        }.getOrNull()
    }

    fun migrateInlineDataUrlsToFiles(markdown: String, rootDir: File? = null): String {
        if (!markdown.contains("data:image/", ignoreCase = true)) {
            return markdown
        }
        return INLINE_DATA_IMAGE_REGEX.replace(markdown) { match ->
            val alt = match.groupValues[1]
            val mime = match.groupValues[2]
            val b64 = match.groupValues[3]
            val uri = persistBase64(mime, b64, rootDir)
            if (uri != null) {
                "![$alt]($uri)"
            } else {
                "[Generated Image]"
            }
        }
    }

    fun sanitizeMarkdownImagesForContext(markdown: String): String {
        if (!markdown.contains("![") && !markdown.contains("data:image/", ignoreCase = true)) {
            return markdown
        }
        val sanitized = MARKDOWN_IMAGE_FOR_CONTEXT_REGEX.replace(markdown) { match ->
            val alt = match.groupValues[1]
            val label = alt.trim()
            if (label.isEmpty() || label.equals("Generated Image", ignoreCase = true)) {
                "[Generated Image]"
            } else {
                "[Generated Image: $label]"
            }
        }
        if (!sanitized.contains("data:image/", ignoreCase = true)) {
            return sanitized
        }
        return BARE_DATA_IMAGE_REGEX.replace(sanitized, "[Generated Image]")
    }
}
