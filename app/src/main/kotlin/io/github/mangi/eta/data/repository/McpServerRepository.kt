package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.toDomain
import io.github.mangi.eta.data.db.toEntity
import io.github.mangi.eta.data.model.McpAuthorizationType
import io.github.mangi.eta.data.model.McpServerSetting
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal object McpServerRepository {
    @Volatile
    private lateinit var applicationContext: Context

    fun init(context: Context) {
        if (!::applicationContext.isInitialized) {
            applicationContext = context.applicationContext
        }
    }

    fun serversFlow(): Flow<List<McpServerSetting>> = dao().serversFlow().map { entities ->
        entities.map { it.toDomain() }
    }

    suspend fun servers(): List<McpServerSetting> = dao().servers().map { it.toDomain() }

    suspend fun enabledServers(): List<McpServerSetting> = servers().filter(McpServerSetting::enabled)

    suspend fun serverById(id: String): McpServerSetting? = dao().serverById(id)?.toDomain()

    fun appContextOrNull(): Context? =
        if (::applicationContext.isInitialized) applicationContext else null

    suspend fun add(
        server: McpServerSetting,
        bearerToken: String = "",
        envVars: Map<String, String> = emptyMap(),
    ): McpServerSetting {
        val cleanedEnv = envVars.entries
            .mapNotNull { (k, v) -> k.trim().takeIf { it.isNotEmpty() }?.to(v) }
            .toMap()
        val added = server.copy(
            id = server.id.ifBlank { UUID.randomUUID().toString() },
            sortOrder = (servers().maxOfOrNull { it.sortOrder } ?: -1) + 1,
            hasEnvVars = cleanedEnv.isNotEmpty(),
        )
        val secretStore = secrets()
        updateSecret(secretStore, added, bearerToken)
        secretStore.setEnvVars(added.id, cleanedEnv)
        try {
            dao().insert(added.toEntity())
        } catch (failure: Throwable) {
            runCatching { secretStore.clear(added.id) }
            throw failure
        }
        return added
    }

    suspend fun update(
        server: McpServerSetting,
        bearerToken: String? = null,
        envVars: Map<String, String>? = null,
    ) {
        val previous = requireNotNull(serverById(server.id)) { "MCP 服务器不存在" }
        val secretStore = secrets()
        val previousToken = secretStore.bearerToken(server.id)
        val previousEnv = secretStore.envVars(server.id)
        val changesSecret = bearerToken != null || server.authorizationType == McpAuthorizationType.NONE
        val cleanedEnv = envVars?.entries
            ?.mapNotNull { (k, v) -> k.trim().takeIf { it.isNotEmpty() }?.to(v) }
            ?.toMap()
        val configChanged = bearerToken != null ||
            envVars != null ||
            previous.transportType != server.transportType ||
            previous.url != server.url ||
            previous.command != server.command ||
            previous.args != server.args ||
            previous.stdioEnvironment != server.stdioEnvironment ||
            previous.cwd != server.cwd
        val updated = server.copy(
            hasEnvVars = cleanedEnv?.isNotEmpty() ?: server.hasEnvVars,
            lastProtocolVersion = if (configChanged) null else server.lastProtocolVersion,
            toolsExpireAt = if (configChanged) null else server.toolsExpireAt,
        )
        if (changesSecret) updateSecret(secretStore, updated, bearerToken.orEmpty())
        if (cleanedEnv != null) secretStore.setEnvVars(updated.id, cleanedEnv)
        try {
            require(dao().update(updated.toEntity()) == 1) { "MCP 服务器不存在" }
        } catch (failure: Throwable) {
            if (changesSecret) runCatching {
                updateSecret(secretStore, previous, previousToken.orEmpty())
            }
            if (cleanedEnv != null) runCatching {
                secretStore.setEnvVars(previous.id, previousEnv)
            }
            throw failure
        }
    }

    suspend fun setToolEnabled(serverId: String, toolName: String, enabled: Boolean) {
        val server = requireNotNull(serverById(serverId)) { "MCP 服务器不存在" }
        val names = server.enabledToolNames.toMutableSet()
        if (enabled) names += toolName else names -= toolName
        update(server.copy(enabledToolNames = names))
    }

    suspend fun delete(serverId: String) {
        val previous = requireNotNull(serverById(serverId)) { "MCP 服务器不存在" }
        val secretStore = secrets()
        val previousToken = secretStore.bearerToken(serverId)
        val previousEnv = secretStore.envVars(serverId)
        secretStore.clear(serverId)
        try {
            require(dao().delete(serverId) == 1) { "MCP 服务器不存在" }
        } catch (failure: Throwable) {
            runCatching {
                updateSecret(secretStore, previous, previousToken.orEmpty())
                secretStore.setEnvVars(previous.id, previousEnv)
            }
            throw failure
        }
    }

    fun bearerToken(serverId: String): String? = secrets().bearerToken(serverId)

    fun envVars(serverId: String): Map<String, String> = secrets().envVars(serverId)

    private fun updateSecret(
        secretStore: McpSecretStore,
        server: McpServerSetting,
        bearerToken: String,
    ) {
        if (server.authorizationType == McpAuthorizationType.BEARER) {
            secretStore.setBearerToken(server.id, bearerToken)
        } else {
            secretStore.setBearerToken(server.id, "")
        }
    }

    private fun dao() = EtaDatabase.get(context()).mcpServerDao()

    private fun secrets() = McpSecretStore(context())

    private fun context(): Context {
        check(::applicationContext.isInitialized) {
            "McpServerRepository.init(context) must be called in Application.onCreate()"
        }
        return applicationContext
    }
}
