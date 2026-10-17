package io.github.mangi.eta.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpStdioEnvironment
import io.github.mangi.eta.data.model.McpToolDefinition
import io.github.mangi.eta.data.model.McpTransportType
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

@Entity(tableName = "mcp_servers")
internal data class McpServerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean,
    @ColumnInfo(name = "protocol_mode") val protocolMode: String,
    @ColumnInfo(name = "authorization_type") val authorizationType: String,
    @ColumnInfo(name = "tools_json") val toolsJson: String,
    @ColumnInfo(name = "enabled_tool_names_json") val enabledToolNamesJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "last_refreshed_at") val lastRefreshedAt: Long?,
    @ColumnInfo(name = "last_protocol_version") val lastProtocolVersion: String?,
    @ColumnInfo(name = "tools_expire_at") val toolsExpireAt: Long?,
    @ColumnInfo(name = "transport_type") val transportType: String = McpTransportType.HTTP,
    val command: String = "",
    @ColumnInfo(name = "args_json") val argsJson: String = "[]",
    @ColumnInfo(name = "stdio_environment") val stdioEnvironment: String = McpStdioEnvironment.LINUX,
    val cwd: String = "",
    @ColumnInfo(name = "has_env_vars") val hasEnvVars: Boolean = false,
)

private val mcpJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal fun McpServerSetting.toEntity(): McpServerEntity = McpServerEntity(
    id = id,
    name = name,
    url = url,
    enabled = enabled,
    protocolMode = protocolMode,
    authorizationType = authorizationType,
    toolsJson = mcpJson.encodeToString(ListSerializer(McpToolDefinition.serializer()), tools),
    enabledToolNamesJson = mcpJson.encodeToString(SetSerializer(String.serializer()), enabledToolNames),
    createdAt = createdAt,
    sortOrder = sortOrder,
    lastRefreshedAt = lastRefreshedAt,
    lastProtocolVersion = lastProtocolVersion,
    toolsExpireAt = toolsExpireAt,
    transportType = transportType,
    command = command,
    argsJson = mcpJson.encodeToString(ListSerializer(String.serializer()), args),
    stdioEnvironment = stdioEnvironment,
    cwd = cwd,
    hasEnvVars = hasEnvVars,
)

internal fun McpServerEntity.toDomain(): McpServerSetting = McpServerSetting(
    id = id,
    name = name,
    url = url,
    transportType = transportType.ifBlank { McpTransportType.HTTP },
    command = command,
    args = runCatching {
        mcpJson.decodeFromString(ListSerializer(String.serializer()), argsJson)
    }.getOrDefault(emptyList()),
    stdioEnvironment = stdioEnvironment.ifBlank { McpStdioEnvironment.LINUX },
    cwd = cwd,
    hasEnvVars = hasEnvVars,
    enabled = enabled,
    protocolMode = protocolMode,
    authorizationType = authorizationType,
    tools = runCatching {
        mcpJson.decodeFromString(ListSerializer(McpToolDefinition.serializer()), toolsJson)
    }.getOrDefault(emptyList()),
    enabledToolNames = runCatching {
        mcpJson.decodeFromString(SetSerializer(String.serializer()), enabledToolNamesJson)
    }.getOrDefault(emptySet()),
    createdAt = createdAt,
    sortOrder = sortOrder,
    lastRefreshedAt = lastRefreshedAt,
    lastProtocolVersion = lastProtocolVersion,
    toolsExpireAt = toolsExpireAt,
)
