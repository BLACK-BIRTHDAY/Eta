package io.github.mangi.eta.agent.mcp

import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.repository.McpServerRepository

internal object McpServerManager {
    fun openClient(
        server: McpServerSetting,
        bearerToken: String? = null,
        envVars: Map<String, String> = emptyMap(),
    ): McpClient = if (server.isStdio) {
        McpStdioClient(server = server, envVars = envVars)
    } else {
        McpHttpClient(server = server, bearerToken = bearerToken)
    }

    fun discover(
        server: McpServerSetting,
        bearerToken: String?,
        envVars: Map<String, String> = emptyMap(),
    ): McpServerSetting = openClient(server, bearerToken, envVars).use { client ->
        val discovery = client.discoverTools()
        val availableNames = discovery.tools.mapTo(mutableSetOf()) { it.name }
        val refreshedAt = System.currentTimeMillis()
        server.copy(
            tools = discovery.tools,
            enabledToolNames = server.enabledToolNames.intersect(availableNames),
            lastRefreshedAt = refreshedAt,
            lastProtocolVersion = discovery.protocolVersion,
            toolsExpireAt = discovery.cacheTtlMs?.let { ttl ->
                if (ttl > Long.MAX_VALUE - refreshedAt) Long.MAX_VALUE else refreshedAt + ttl
            },
        )
    }

    suspend fun refresh(serverId: String): McpServerSetting {
        val server = requireNotNull(McpServerRepository.serverById(serverId)) {
            "MCP 服务器不存在"
        }
        val refreshed = discover(
            server = server,
            bearerToken = McpServerRepository.bearerToken(serverId),
            envVars = McpServerRepository.envVars(serverId),
        )
        McpServerRepository.update(refreshed)
        return refreshed
    }
}
