package io.github.mangi.eta.data.model

import kotlinx.serialization.Serializable

internal object McpProtocolMode {
    const val AUTO = "auto"
    const val LATEST = "2026-07-28"
    const val LEGACY = "2025-11-25"
}

internal object McpTransportType {
    const val HTTP = "http"
    const val STDIO = "stdio"
}

internal object McpStdioEnvironment {
    const val LINUX = "linux"
    const val ANDROID_ROOT = "android_root"
    const val ANDROID_USER = "android_user"
}

internal object McpAuthorizationType {
    const val NONE = "none"
    const val BEARER = "bearer"
}

@Serializable
internal data class McpToolDefinition(
    val name: String,
    val title: String = "",
    val description: String = "",
    val inputSchemaJson: String,
    val readOnlyHint: Boolean? = null,
    val destructiveHint: Boolean? = null,
    val idempotentHint: Boolean? = null,
    val openWorldHint: Boolean? = null,
)

@Serializable
internal data class McpServerSetting(
    val id: String,
    val name: String,
    val url: String = "",
    val transportType: String = McpTransportType.HTTP,
    val command: String = "",
    val args: List<String> = emptyList(),
    val stdioEnvironment: String = McpStdioEnvironment.LINUX,
    val cwd: String = "",
    val hasEnvVars: Boolean = false,
    val enabled: Boolean = true,
    val protocolMode: String = McpProtocolMode.AUTO,
    val authorizationType: String = McpAuthorizationType.NONE,
    val tools: List<McpToolDefinition> = emptyList(),
    val enabledToolNames: Set<String> = emptySet(),
    val createdAt: Long = System.currentTimeMillis(),
    val sortOrder: Int = 0,
    val lastRefreshedAt: Long? = null,
    val lastProtocolVersion: String? = null,
    val toolsExpireAt: Long? = null,
) {
    val isStdio: Boolean
        get() = transportType == McpTransportType.STDIO

    val endpointSummary: String
        get() = if (isStdio) {
            buildString {
                append(command.trim())
                if (args.isNotEmpty()) {
                    append(' ')
                    append(args.joinToString(" "))
                }
            }.trim().ifBlank { "stdio" }
        } else {
            url
        }

    val activeTools: List<McpToolDefinition>
        get() = if (!enabled) {
            emptyList()
        } else {
            tools.filter { it.name in enabledToolNames }
        }
}
