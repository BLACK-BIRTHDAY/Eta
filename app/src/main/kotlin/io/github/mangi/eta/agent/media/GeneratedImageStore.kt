package io.github.mangi.eta.agent.media

import io.github.mangi.eta.agent.runtime.AgentAppContext
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Base64

internal object GeneratedImageStore {
    const val MAX_GENERATED_IMAGE_BYTES = 20 * 1024 * 1024
    const val MAX_GENERATED_VIDEO_BYTES = 100 * 1024 * 1024

    private val INLINE_DATA_MEDIA_REGEX = Regex(
        """!\[([^\]]*)]\(\s*data:((?:image|video)/[a-zA-Z0-9.+-]+);base64,([A-Za-z0-9+/=\s]+)\s*\)""",
        RegexOption.IGNORE_CASE
    )

    private val LEGACY_FILE_IMAGE_REGEX = Regex(
        """!\[([^\]]*)]\(\s*(file:[^)\s]+/img-[a-f0-9]+\.jpg)\s*\)""",
        RegexOption.IGNORE_CASE
    )

    private val MARKDOWN_MEDIA_FOR_CONTEXT_REGEX = Regex(
        """!\[([^\]]*)]\(\s*(data:(?:image|video)/[^)]+|file:[^)]+)\s*\)""",
        RegexOption.IGNORE_CASE
    )

    private val BARE_DATA_MEDIA_REGEX = Regex(
        """data:(?:image|video)/[a-zA-Z0-9.+-]+;base64,[A-Za-z0-9+/=]+""",
        RegexOption.IGNORE_CASE
    )

    fun isVideoBytes(bytes: ByteArray): Boolean {
        if (bytes.size >= 12 &&
            bytes[4] == 'f'.code.toByte() &&
            bytes[5] == 't'.code.toByte() &&
            bytes[6] == 'y'.code.toByte() &&
            bytes[7] == 'p'.code.toByte()
        ) {
            return true
        }
        if (bytes.size >= 4 &&
            bytes[0] == 0x1A.toByte() &&
            bytes[1] == 0x45.toByte() &&
            bytes[2] == 0xDF.toByte() &&
            bytes[3] == 0xA3.toByte()
        ) {
            return true
        }
        return false
    }

    private fun isWebmBytes(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x1A.toByte() &&
            bytes[1] == 0x45.toByte() &&
            bytes[2] == 0xDF.toByte() &&
            bytes[3] == 0xA3.toByte()

    fun isVideoMedia(mimeType: String, uri: String? = null): Boolean {
        val lowerMime = mimeType.lowercase()
        if (lowerMime.startsWith("video/")) return true
        val lowerUri = uri?.lowercase().orEmpty()
        return lowerUri.endsWith(".mp4") || lowerUri.endsWith(".webm") || lowerUri.endsWith(".mov")
    }

    fun persistBase64(mimeType: String, base64Data: String, rootDir: File? = null): String? {
        val bytes = runCatching {
            Base64.getMimeDecoder().decode(base64Data.trim())
        }.getOrNull() ?: return null

        val lowerMime = mimeType.lowercase()
        val isVideo = lowerMime.startsWith("video/") || isVideoBytes(bytes)
        val maxBytes = if (isVideo) MAX_GENERATED_VIDEO_BYTES else MAX_GENERATED_IMAGE_BYTES
        if (bytes.isEmpty() || bytes.size > maxBytes) {
            return null
        }

        return runCatching {
            val dir = (rootDir
                ?: AgentAppContext.resolve()?.filesDir?.let { File(it, "generated_images") }
                ?: File(System.getProperty("java.io.tmpdir"), "eta-generated-images")
            ).apply { mkdirs() }

            val hashBytes = MessageDigest.getInstance("SHA-256").digest(bytes)
            val hash = hashBytes.joinToString("") { "%02x".format(it) }.take(24)

            val (prefix, ext) = if (isVideo) {
                val videoExt = when {
                    lowerMime.contains("webm") || isWebmBytes(bytes) -> "webm"
                    lowerMime.contains("quicktime") -> "mov"
                    else -> "mp4"
                }
                "vid" to videoExt
            } else {
                val imageExt = when {
                    lowerMime.contains("png") -> "png"
                    lowerMime.contains("webp") -> "webp"
                    lowerMime.contains("gif") -> "gif"
                    else -> "jpg"
                }
                "img" to imageExt
            }

            val file = File(dir, "$prefix-$hash.$ext")
            if (!file.exists() || file.length() != bytes.size.toLong()) {
                file.writeBytes(bytes)
            }
            file.toURI().toString()
        }.getOrNull()
    }

    fun migrateInlineDataUrlsToFiles(markdown: String, rootDir: File? = null): String {
        var result = markdown
        if (result.contains("data:image/", ignoreCase = true) || result.contains("data:video/", ignoreCase = true)) {
            result = INLINE_DATA_MEDIA_REGEX.replace(result) { match ->
                val alt = match.groupValues[1]
                val mime = match.groupValues[2]
                val b64 = match.groupValues[3]
                val uri = persistBase64(mime, b64, rootDir)
                val isVideo = isVideoMedia(mime, uri)
                val defaultLabel = if (isVideo) "Generated Video" else "Generated Image"
                if (uri != null) {
                    val effectiveAlt = if (alt.isBlank() || (isVideo && alt.equals("Generated Image", ignoreCase = true))) {
                        defaultLabel
                    } else {
                        alt
                    }
                    "![$effectiveAlt]($uri)"
                } else {
                    "[$defaultLabel]"
                }
            }
        }
        if (result.contains("img-", ignoreCase = true) && result.contains(".jpg", ignoreCase = true)) {
            result = LEGACY_FILE_IMAGE_REGEX.replace(result) { match ->
                val alt = match.groupValues[1]
                val fileUriStr = match.groupValues[2]
                val repairedUri = repairLegacyVideoFileIfNeeded(fileUriStr)
                if (repairedUri != null) {
                    val effectiveAlt = if (alt.isBlank() || alt.equals("Generated Image", ignoreCase = true)) {
                        "Generated Video"
                    } else {
                        alt
                    }
                    "![$effectiveAlt]($repairedUri)"
                } else {
                    match.value
                }
            }
        }
        return result
    }

    private fun repairLegacyVideoFileIfNeeded(fileUriStr: String): String? = runCatching {
        val file = File(URI(fileUriStr)).takeIf(File::isFile) ?: return null
        val header = ByteArray(16)
        val read = file.inputStream().use { it.read(header) }
        if (read < 12) return null
        val headerSlice = header.copyOf(read)
        if (!isVideoBytes(headerSlice)) return null
        val ext = if (isWebmBytes(headerSlice)) "webm" else "mp4"
        val newName = file.nameWithoutExtension.replaceFirst(Regex("^img-"), "vid-") + ".$ext"
        val targetFile = File(file.parentFile, newName)
        if (!targetFile.exists()) {
            if (!file.renameTo(targetFile)) {
                file.copyTo(targetFile, overwrite = true)
            }
        }
        targetFile.toURI().toString()
    }.getOrNull()

    fun sanitizeMarkdownImagesForContext(markdown: String): String {
        if (!markdown.contains("![") &&
            !markdown.contains("data:image/", ignoreCase = true) &&
            !markdown.contains("data:video/", ignoreCase = true)
        ) {
            return markdown
        }
        val sanitized = MARKDOWN_MEDIA_FOR_CONTEXT_REGEX.replace(markdown) { match ->
            val alt = match.groupValues[1]
            val target = match.groupValues[2].lowercase()
            val label = alt.trim()
            val isVideo = label.equals("Generated Video", ignoreCase = true) ||
                target.startsWith("data:video/") ||
                target.endsWith(".mp4") ||
                target.endsWith(".webm") ||
                target.endsWith(".mov")
            val kind = if (isVideo) "Generated Video" else "Generated Image"
            if (label.isEmpty() ||
                label.equals("Generated Image", ignoreCase = true) ||
                label.equals("Generated Video", ignoreCase = true)
            ) {
                "[$kind]"
            } else {
                "[$kind: $label]"
            }
        }
        if (!sanitized.contains("data:image/", ignoreCase = true) &&
            !sanitized.contains("data:video/", ignoreCase = true)
        ) {
            return sanitized
        }
        return BARE_DATA_MEDIA_REGEX.replace(sanitized) { match ->
            if (match.value.startsWith("data:video/", ignoreCase = true)) {
                "[Generated Video]"
            } else {
                "[Generated Image]"
            }
        }
    }
}
