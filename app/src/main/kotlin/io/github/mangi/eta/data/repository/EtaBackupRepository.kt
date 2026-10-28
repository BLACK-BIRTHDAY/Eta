package io.github.mangi.eta.data.repository

import android.content.Context
import androidx.room.withTransaction
import io.github.mangi.eta.data.db.ConversationContextCheckpointEntity
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.ConversationMessageEntity
import io.github.mangi.eta.data.db.ConversationStateEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.ProviderEntity
import io.github.mangi.eta.data.db.ProviderModelEntity
import io.github.mangi.eta.data.db.ProviderWithModelsSeed
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.model.WebToolsSettings
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.core.safeStackTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import java.io.InputStream
import java.io.OutputStream

/** Eta 用户数据备份；旧备份没有角色字段时保留设备上的角色库。 */
@Serializable
internal data class EtaBackupDocument(
    val format: String = FORMAT,
    val schemaVersion: Int = SCHEMA_VERSION,
    val exportedAt: Long,
    val providers: List<EtaBackupProvider> = emptyList(),
    val catalogRevision: Int = 0,
    val selectedProviderId: String? = null,
    val selectedModelId: String? = null,
    val conversations: List<ConversationEntity> = emptyList(),
    val messages: List<ConversationMessageEntity> = emptyList(),
    val contextCheckpoints: List<ConversationContextCheckpointEntity> = emptyList(),
    val conversationState: ConversationStateEntity? = null,
    val memoryMd: String = "",
    val roleplay: CharacterBackupData? = null,
    val skills: SkillBackupData? = null,
    val mcpServers: List<McpBackupServer>? = null,
    val webTools: WebToolsSettings? = null,
    val speech: SpeechSettings? = null,
    val subagent: SubagentBackupData? = null,
) {
    companion object {
        const val FORMAT = "eta-backup"
        // 4：支持 Skill 包与 MCP 设置导出；旧版会把未知原生 Provider 当作 OpenAI-compatible，必须通过备份版本阻止错误恢复。
        const val SCHEMA_VERSION = 4
    }
}

@Serializable
internal data class EtaBackupProvider(
    val provider: ProviderEntity,
    val models: List<ProviderModelEntity> = emptyList(),
)

internal data class EtaBackupSummary(
    val providerCount: Int,
    val modelCount: Int,
    val conversationCount: Int,
    val messageCount: Int,
    val memoryBytes: Int,
    val characterCount: Int = 0,
    val skillCount: Int = 0,
    val mcpServerCount: Int = 0,
)

internal class EtaBackupException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

internal object EtaBackupRepository {
    private const val EXPORT_PAGE_SIZE = 64

    suspend fun export(context: Context, output: OutputStream): EtaBackupSummary = withContext(Dispatchers.IO) {
        var phase = "prepare_export"
        try {
            val appContext = context.applicationContext
            CharacterRepository.initialize(appContext)
            val database = EtaDatabase.get(appContext)
            val settings = SettingsDataStore.settings()
            val catalogRevision = SettingsDataStore.officialModelCatalogRevision()
            EtaBackupJsonStreams.export(appContext, output, onPhase = { phase = it }) { writer ->
                database.withTransaction {
                    val dao = database.conversationDao()
                    val providers = database.providerDao().providers().map {
                        EtaBackupProvider(it.provider.withoutOAuthSecrets(), it.models)
                    }
                    val memory = AgentMemoryRepository.snapshot().content
                    writer.value("format", String.serializer(), EtaBackupDocument.FORMAT)
                    writer.value("schemaVersion", Int.serializer(), EtaBackupDocument.SCHEMA_VERSION)
                    writer.value("exportedAt", Long.serializer(), System.currentTimeMillis())
                    writer.value("providers", ListSerializer(EtaBackupProvider.serializer()), providers)
                    writer.value("catalogRevision", Int.serializer(), catalogRevision)
                    writer.value("selectedProviderId", String.serializer().nullable, settings.selectedProviderId)
                    writer.value("selectedModelId", String.serializer().nullable, settings.selectedModelId)
                    writer.value("conversationState", ConversationStateEntity.serializer().nullable, dao.state())
                    writer.value("memoryMd", String.serializer(), memory)
                    val boundAvatars = linkedMapOf<String, String?>()
                    var conversationCount = 0
                    var messageCount = 0
                    phase = "export_conversations"
                    writer.beginArray(EtaBackupJsonStreams.CONVERSATIONS)
                    exportRows({ dao.conversationEntityRowsPage(EXPORT_PAGE_SIZE, it) }) { raw ->
                        val row = dao.restoreConversation(raw)
                        writer.record(ConversationEntity.serializer(), row)
                        CharacterBackupTransfer.avatarReference(row)?.let { (id, avatar) ->
                            if (id !in boundAvatars) boundAvatars[id] = avatar
                        }
                        conversationCount++
                    }
                    writer.endArray()
                    phase = "export_messages"
                    writer.beginArray(EtaBackupJsonStreams.MESSAGES)
                    exportRows({ dao.allMessageRowsPage(EXPORT_PAGE_SIZE, it) }) { raw ->
                        writer.record(ConversationMessageEntity.serializer(), dao.restoreMessage(raw))
                        messageCount++
                    }
                    writer.endArray()
                    phase = "export_checkpoints"
                    writer.beginArray(EtaBackupJsonStreams.CHECKPOINTS)
                    exportRows({ dao.contextCheckpointRowsPage(EXPORT_PAGE_SIZE, it) }) { raw ->
                        writer.record(ConversationContextCheckpointEntity.serializer(), dao.restoreCheckpoint(raw))
                    }
                    writer.endArray()
                    phase = "export_roleplay"
                    val roleplay = CharacterBackupTransfer.snapshot(appContext, boundAvatars)
                    writer.value("roleplay", CharacterBackupData.serializer().nullable, roleplay)
                    phase = "export_skills"
                    val skills = SkillBackupTransfer.snapshot(appContext)
                    writer.value("skills", SkillBackupData.serializer().nullable, skills)
                    phase = "export_mcp"
                    val mcpServers = McpBackupTransfer.snapshot(appContext)
                    writer.value("mcpServers", ListSerializer(McpBackupServer.serializer()).nullable, mcpServers)
                    phase = "export_settings"
                    val webTools = SettingsDataStore.webToolsSettings()
                    writer.value("webTools", WebToolsSettings.serializer().nullable, webTools)
                    val speech = SettingsDataStore.speechSettings()
                    writer.value("speech", SpeechSettings.serializer().nullable, speech)
                    val subagent = SubagentBackupTransfer.snapshot()
                    writer.value("subagent", SubagentBackupData.serializer().nullable, subagent)
                    val skillCount = if (skills.packages.isNotEmpty()) skills.packages.size else skills.registry.count { it.installState == "installed" }
                    val mcpServerCount = mcpServers.size
                    EtaBackupSummary(
                        providers.size,
                        providers.sumOf { it.models.size },
                        conversationCount,
                        messageCount,
                        memory.toByteArray(Charsets.UTF_8).size,
                        roleplay.characters.size,
                        skillCount,
                        mcpServerCount,
                    )
                }
            }
        } catch (failure: Throwable) {
            logFailure("export", phase, failure)
            throw failure
        }
    }

    suspend fun import(
        context: Context,
        input: InputStream,
        onCommitted: () -> Unit = {},
        forgetCredentials: (String) -> Unit = ProviderCredentialRepository::forget,
    ): EtaBackupSummary = withContext(Dispatchers.IO) {
        var phase = "stage_import"
        try {
            val appContext = context.applicationContext
            EtaBackupJsonStreams.stage(appContext, input) { phase = it }.use { staged ->
                phase = "validate_import"
                val summary = EtaBackupValidation.validate(staged)
                val document = staged.header
                val database = EtaDatabase.get(appContext)
                val previousMemory = AgentMemoryRepository.snapshot().content
                val previousRoleMemories = document.roleplay?.assets.orEmpty().associate {
                    it.characterId to CharacterMemoryRepository.snapshot(appContext, it.characterId).content
                }
                val previousMcpIds = if (document.mcpServers != null) {
                    database.mcpServerDao().servers().mapTo(mutableSetOf()) { it.id }
                } else {
                    emptySet()
                }
                val previousSecrets = if (document.mcpServers != null) {
                    McpBackupTransfer.captureSecrets(appContext, previousMcpIds)
                } else {
                    emptyMap()
                }
                phase = "restore_assets"
                val avatarPaths = document.roleplay?.let { CharacterBackupTransfer.restoreAssets(appContext, it) }.orEmpty()
                document.subagent?.let { SubagentBackupTransfer.restoreCustomProfiles(it) }
                var stagedSkillSwap: StagedSkillSwap? = null
                val cancellation = currentCoroutineContext()
                val oauthProviders = document.providers.map { it.provider }
                    .filter { ProviderAuthModes.isOAuth(it.authMode) }
                val credentialIds = document.providers.mapTo(linkedSetOf()) { it.provider.id }
                // 显式检查原任务取消，使 Room 提交到凭据清理之间不留下被取消打断的空隙。
                withContext(NonCancellable) {
                    var memoryWriteStarted = false
                    try {
                        phase = "stage_skills"
                        stagedSkillSwap = document.skills?.let {
                            SkillBackupTransfer.stageAndSwapDirectories(appContext, it)
                        }
                        database.withTransaction {
                            cancellation.ensureActive()
                            phase = "restore_providers"
                            credentialIds += database.providerDao().providers().map { it.provider.id }
                            database.providerDao().replaceAll(document.providers.map {
                                ProviderWithModelsSeed(it.provider.awaitingCredentialCleanup(), it.models)
                            })
                            val dao = database.conversationDao()
                            phase = "clear_conversations"
                            dao.deleteMessages()
                            dao.deleteContextCheckpoints()
                            dao.deleteConversations()
                            dao.deleteState()
                            phase = "restore_conversations"
                            staged.conversations { row ->
                                cancellation.ensureActive()
                                dao.insertConversations(listOf(CharacterBackupTransfer.remapConversation(row, avatarPaths)))
                            }
                            phase = "restore_messages"
                            staged.messages {
                                cancellation.ensureActive()
                                dao.insertMessages(listOf(it))
                            }
                            phase = "restore_checkpoints"
                            staged.checkpoints {
                                cancellation.ensureActive()
                                dao.insertContextCheckpoints(listOf(it))
                            }
                            document.conversationState?.let { dao.insertState(it) }
                            phase = "restore_roleplay"
                            document.roleplay?.let { roleplay ->
                                database.characterDao().replaceAll(
                                    roleplay.characters.map { it.copy(avatarPath = avatarPaths[it.id]) }, roleplay.persona,
                                )
                            }
                            if (document.mcpServers != null) {
                                phase = "restore_mcp_servers"
                                database.mcpServerDao().replaceAll(document.mcpServers.map { it.server })
                            }
                            if (document.skills != null) {
                                phase = "restore_skill_registry"
                                database.skillDao().replaceRegistry(document.skills.registry)
                            }
                            phase = "restore_memory"
                            cancellation.ensureActive()
                            memoryWriteStarted = true
                            AgentMemoryRepository.replaceAll(document.memoryMd)
                            document.roleplay?.let { CharacterBackupTransfer.restoreMemories(appContext, it) }
                            if (document.mcpServers != null) {
                                phase = "restore_mcp_secrets"
                                McpBackupTransfer.restoreSecrets(appContext, previousMcpIds, document.mcpServers)
                            }
                            cancellation.ensureActive()
                        }
                        stagedSkillSwap?.commit()
                    } catch (failure: Throwable) {
                        if (document.mcpServers != null) {
                            val currentMcpIds = document.mcpServers.map { it.server.id }.toSet()
                            McpBackupTransfer.rollbackSecrets(appContext, currentMcpIds, previousSecrets, failure)
                        }
                        stagedSkillSwap?.rollback(failure)
                        if (memoryWriteStarted) {
                            try { AgentMemoryRepository.replaceAll(previousMemory) } catch (restoreFailure: Throwable) {
                                failure.addSuppressed(restoreFailure)
                            }
                            previousRoleMemories.forEach { (id, content) ->
                                try { CharacterMemoryRepository.replaceAll(appContext, id, content) } catch (restoreFailure: Throwable) {
                                    failure.addSuppressed(restoreFailure)
                                }
                            }
                        }
                        CharacterBackupTransfer.discardAssets(avatarPaths, failure)
                        throw failure
                    } finally {
                        stagedSkillSwap?.close()
                    }
                    onCommitted()
                    try {
                        phase = "clear_provider_credentials"
                        credentialIds.forEach(forgetCredentials)
                        phase = "activate_provider_connections"
                        database.withTransaction {
                            oauthProviders.forEach { provider ->
                                check(database.providerDao().activateRestoredOAuthProvider(
                                    provider.id, provider.authMode, provider.isEnabled,
                                ) == 1)
                            }
                        }
                    } catch (failure: Exception) {
                        throw IllegalStateException(
                            "数据已导入，但订阅登录清理未完成。订阅连接保持停用，请重新选择登录方式并登录。",
                            failure,
                        )
                    }
                    try {
                        phase = "restore_settings"
                        SettingsDataStore.setSelection(document.selectedProviderId, document.selectedModelId)
                        SettingsDataStore.setOfficialModelCatalogRevision(document.catalogRevision)
                        document.webTools?.let { SettingsDataStore.setWebToolsSettings(it) }
                        document.speech?.let { SettingsDataStore.setSpeechSettings(it) }
                        if (document.subagent != null) {
                            SettingsDataStore.setSubagentSettings(document.subagent.settings)
                            Prefs.initLocal(appContext)
                            Prefs.localAgentPreferences()?.edit()
                                ?.putBoolean(Prefs.Keys.AGENT_SUBAGENT_ENABLED, document.subagent.enabled)
                                ?.commit()
                        }
                        ProviderRepository.ensureBuiltInsMerged()
                        ProviderRepository.repairSelection()
                    } catch (failure: Exception) {
                        throw IllegalStateException("数据已导入，但默认模型设置未完全恢复，请重新选择服务商和模型。", failure)
                    }
                }
                summary
            }
        } catch (failure: Throwable) {
            logFailure("import", phase, failure)
            throw failure
        }
    }

    suspend fun inspect(context: Context, input: InputStream): EtaBackupSummary = withContext(Dispatchers.IO) {
        EtaBackupJsonStreams.stage(context.applicationContext, input).use { EtaBackupValidation.validate(it) }
    }

    private suspend fun <T> exportRows(load: suspend (Int) -> List<T>, write: suspend (T) -> Unit) {
        var offset = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val page = load(offset)
            for (row in page) {
                currentCoroutineContext().ensureActive()
                write(row)
            }
            if (page.size < EXPORT_PAGE_SIZE) return
            offset += page.size
        }
    }

    private fun ProviderEntity.withoutOAuthSecrets(): ProviderEntity =
        if (authMode == ProviderAuthModes.API_KEY) this else copy(apiKey = "")

    private fun ProviderEntity.awaitingCredentialCleanup(): ProviderEntity =
        if (ProviderAuthModes.isOAuth(authMode)) {
            // 即使提交后进程中断，导入的相同 ID 也不能接上设备中尚未清除的旧授权。
            copy(authMode = ProviderAuthModes.API_KEY, apiKey = "", isEnabled = false)
        } else withoutOAuthSecrets()

    private fun logFailure(operation: String, phase: String, failure: Throwable) {
        if (failure is CancellationException) return
        AndroidAgentLogger.error("Agent backup failed: operation=$operation phase=$phase " +
            "type=${failure.safeLogType()}\n${failure.safeStackTrace()}")
    }
}
