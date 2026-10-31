package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.ConversationContextCheckpointEntity
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.ConversationMessageEntity
import io.github.mangi.eta.data.db.ConversationStateEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.McpServerEntity
import io.github.mangi.eta.data.db.SkillRegistryEntity
import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.model.CustomWebSearchConfig
import io.github.mangi.eta.data.model.DeepSeekWebConfig
import io.github.mangi.eta.data.model.GeminiTtsConfig
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.data.model.WebToolBackend
import io.github.mangi.eta.data.model.WebToolsSettings
import io.github.mangi.eta.data.model.McpAuthorizationType
import io.github.mangi.eta.data.model.McpProtocolMode
import io.github.mangi.eta.data.model.McpStdioEnvironment
import io.github.mangi.eta.data.model.McpTransportType
import io.github.mangi.eta.data.model.ModelSource
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.withApiKey
import io.github.mangi.eta.data.provider.BuiltinProviders
import io.github.mangi.eta.data.provider.OfficialModelCatalog
import io.github.mangi.eta.agent.roleplay.CharacterCardCodec
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.agent.roleplay.RoleplayMessageLink
import io.github.mangi.eta.agent.roleplay.RoleplayMessageState
import io.github.mangi.eta.agent.roleplay.RoleplayReplyRevision
import io.github.mangi.eta.agent.roleplay.UserPersona
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.agent.subagent.SubagentProfileCatalog
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.model.SubagentSettings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EtaBackupRepositoryTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        File(context.filesDir, "skills").deleteRecursively()
        context.getSharedPreferences("eta_mcp_secrets", 0).edit().clear().commit()
        TerminalRuntime.initialize(context)
        Prefs.initLocal(context)
        SubagentProfileCatalog.defaultAgentsDir().deleteRecursively()
        Prefs.localAgentPreferences()?.edit()?.putBoolean(Prefs.Keys.AGENT_SUBAGENT_ENABLED, true)?.commit()
        SettingsDataStore.init(context)
        runBlocking {
            SettingsDataStore.setSelection(null, null)
            SettingsDataStore.setOfficialModelCatalogRevision(0)
            SettingsDataStore.setWebToolsSettings(WebToolsSettings())
            SettingsDataStore.setSpeechSettings(SpeechSettings())
            SettingsDataStore.setSubagentSettings(SubagentSettings())
        }
        ProviderRepository.init(context)
        AgentMemoryRepository.init(context)
    }

    @Test
    fun oauthBackupPreservesConnectionModeButCannotRestoreSecretThroughApiKey() = runBlocking {
        ProviderRepository.addProvider(OpenAiCompatibleProviderSetting(
            id = "oauth-backup", name = "订阅", baseUrl = "https://api.openai.com/v1",
            authMode = ProviderAuthModes.OPENAI_CHATGPT,
        ))
        val dao = EtaDatabase.get(context).providerDao()
        val stored = requireNotNull(dao.providerById("oauth-backup")).provider
        dao.updateProvider(stored.copy(apiKey = "fixture-secret-in-legacy-column"))
        val output = ByteArrayOutputStream()
        EtaBackupRepository.export(context, output)
        val raw = output.toString(Charsets.UTF_8.name())
        assertFalse(raw.contains("fixture-secret-in-legacy-column"))
        val exported = Json.decodeFromString<EtaBackupDocument>(raw)
        assertEquals(ProviderAuthModes.OPENAI_CHATGPT,
            exported.providers.single { it.provider.id == "oauth-backup" }.provider.authMode)

        val imported = EtaBackupDocument(exportedAt = 0, providers = listOf(
            EtaBackupProvider(stored.copy(apiKey = "fixture-secret-from-backup")),
        ))
        EtaBackupRepository.import(context, ByteArrayInputStream(Json.encodeToString(imported).toByteArray()))
        val restored = requireNotNull(dao.providerById("oauth-backup")).provider
        assertEquals(ProviderAuthModes.OPENAI_CHATGPT, restored.authMode)
        assertEquals("", restored.apiKey)
    }

    @Test
    fun oauthRestoreActivatesConnectionAfterCleanupAndRemembersItsModelWhileRequiringLogin() = runBlocking {
        val provider = OpenAiCompatibleProviderSetting(
            id = "oauth-restore", name = "原连接", baseUrl = "https://api.openai.com/v1",
            authMode = ProviderAuthModes.OPENAI_CHATGPT,
            models = listOf(Model(id = "oauth-restored-model", modelId = "fixture-model", displayName = "测试模型")),
        )
        ProviderRepository.addProvider(provider)
        val dao = EtaDatabase.get(context).providerDao()
        val stored = requireNotNull(dao.providerById(provider.id))
        val document = EtaBackupDocument(
            exportedAt = 0,
            providers = listOf(EtaBackupProvider(stored.provider.copy(name = "导入连接"), stored.models)),
            selectedProviderId = provider.id,
            selectedModelId = provider.models.single().id,
        )
        var committed = false
        val credentials = mutableMapOf(provider.id to "fixture-existing-credential")
        EtaBackupRepository.import(
            context, ByteArrayInputStream(Json.encodeToString(document).toByteArray()),
            onCommitted = { committed = true },
            forgetCredentials = { id ->
                assertTrue(committed)
                val pending = runBlocking { requireNotNull(dao.providerById(provider.id)).provider }
                assertEquals("导入连接", pending.name)
                assertEquals(ProviderAuthModes.API_KEY, pending.authMode)
                assertFalse(pending.isEnabled)
                assertEquals("", pending.apiKey)
                credentials.remove(id)
            },
        )

        assertTrue(credentials.isEmpty())
        val restored = requireNotNull(dao.providerById(provider.id)).provider
        assertEquals(ProviderAuthModes.OPENAI_CHATGPT, restored.authMode)
        assertTrue(restored.isEnabled)
        assertEquals(provider.models.single().id, SettingsDataStore.selectedModelIdForProvider(provider.id))
        assertEquals(provider.id, SettingsDataStore.settings().selectedProviderId)
        assertEquals(provider.models.single().id, SettingsDataStore.settings().selectedModelId)
    }

    @Test
    fun cleanupFailureReportsCommittedDataAndLeavesImportedOAuthConnectionsUnableToAuthenticate() = runBlocking {
        val provider = OpenAiCompatibleProviderSetting(
            id = "oauth-cleanup-failure", name = "原连接", baseUrl = "https://api.openai.com/v1",
            authMode = ProviderAuthModes.OPENAI_CHATGPT,
        )
        ProviderRepository.addProvider(provider)
        AgentMemoryRepository.replaceAll("旧记忆")
        val dao = EtaDatabase.get(context).providerDao()
        val stored = requireNotNull(dao.providerById(provider.id))
        val document = EtaBackupDocument(exportedAt = 0, memoryMd = "导入记忆", providers = listOf(
            EtaBackupProvider(stored.provider.copy(name = "导入连接")),
        ))
        var committed = false
        val failure = runCatching {
            EtaBackupRepository.import(
                context, ByteArrayInputStream(Json.encodeToString(document).toByteArray()),
                onCommitted = { committed = true },
                forgetCredentials = { throw java.io.IOException("fixture cleanup failure") },
            )
        }.exceptionOrNull()

        assertTrue(committed)
        assertTrue(failure is IllegalStateException)
        assertTrue(failure?.message.orEmpty().contains("数据已导入"))
        assertTrue(failure?.message.orEmpty().contains("保持停用"))
        val restored = requireNotNull(dao.providerById(provider.id)).provider
        assertEquals("导入连接", restored.name)
        assertEquals(ProviderAuthModes.API_KEY, restored.authMode)
        assertFalse(restored.isEnabled)
        assertEquals("", restored.apiKey)
        assertEquals("导入记忆", AgentMemoryRepository.snapshot().content)
    }

    @Test
    fun cancellationAfterCommitStillFinishesCredentialCleanupAndConnectionActivation() = runBlocking {
        val provider = OpenAiCompatibleProviderSetting(
            id = "oauth-commit-cancel", name = "订阅", baseUrl = "https://api.openai.com/v1",
            authMode = ProviderAuthModes.OPENAI_CHATGPT,
        )
        ProviderRepository.addProvider(provider)
        val dao = EtaDatabase.get(context).providerDao()
        val stored = requireNotNull(dao.providerById(provider.id))
        val document = EtaBackupDocument(exportedAt = 0, providers = listOf(EtaBackupProvider(stored.provider)))
        var committed = false
        val cleared = mutableListOf<String>()
        val operation = launch {
            val importJob = coroutineContext.job
            EtaBackupRepository.import(
                context, ByteArrayInputStream(Json.encodeToString(document).toByteArray()),
                onCommitted = { committed = true; importJob.cancel() },
                forgetCredentials = { cleared += it },
            )
        }
        operation.join()

        assertTrue(operation.isCancelled)
        assertTrue(committed)
        assertTrue(provider.id in cleared)
        assertEquals(ProviderAuthModes.OPENAI_CHATGPT, requireNotNull(dao.providerById(provider.id)).provider.authMode)
    }

    @Test
    fun exportAndImportRestoresProvidersConversationsAndMemory() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged()
        val provider = ProviderRepository.allProviders().first().withApiKey("sk-backup-test")
        ProviderRepository.updateProvider(provider)
        SettingsDataStore.setSelection(provider.id, provider.models.first().id)
        AgentMemoryRepository.replaceAll("# 核心记忆\n喜欢 Kotlin")

        val conversation = ConversationEntity(
            id = "conversation-backup",
            title = "备份会话",
            thinkingEnabled = true,
            createdAt = 1L,
            updatedAt = 2L,
        )
        EtaDatabase.get(context).conversationDao().replaceAll(
            conversations = listOf(conversation),
            messages = listOf(
                ConversationMessageEntity(
                    id = "message-backup",
                    conversationId = conversation.id,
                    sortIndex = 0,
                    type = "user",
                    content = "保留这条消息",
                ),
            ),
            contextCheckpoints = listOf(
                ConversationContextCheckpointEntity(
                    conversationId = conversation.id,
                    historyJson = "[]",
                ),
            ),
            state = ConversationStateEntity(selectedConversationId = conversation.id),
        )

        val output = ByteArrayOutputStream()
        val exported = EtaBackupRepository.export(context, output)
        assertEquals(1, exported.conversationCount)
        assertTrue(exported.providerCount > 0)
        assertEquals("# 核心记忆\n喜欢 Kotlin", AgentMemoryRepository.snapshot().content)

        ProviderRepository.updateProvider(provider.withApiKey("changed"))
        AgentMemoryRepository.replaceAll("changed")
        EtaDatabase.get(context).conversationDao().replaceAll(
            conversations = emptyList(),
            messages = emptyList(),
            contextCheckpoints = emptyList(),
            state = null,
        )

        val imported = EtaBackupRepository.import(
            context,
            ByteArrayInputStream(output.toByteArray()),
        )
        assertEquals(1, imported.conversationCount)
        assertEquals("# 核心记忆\n喜欢 Kotlin", AgentMemoryRepository.snapshot().content)
        assertEquals(
            "保留这条消息",
            EtaDatabase.get(context).conversationDao().messages().single().content,
        )
        val restoredSettings = SettingsDataStore.settings()
        assertEquals(provider.id, restoredSettings.selectedProviderId)
        assertEquals(provider.models.first().id, restoredSettings.selectedModelId)
        assertEquals("sk-backup-test", ProviderRepository.providerById(provider.id)?.apiKey)
        assertEquals(ModelSource.CATALOG, ProviderRepository.providerById(provider.id)?.models?.first()?.source)
    }

    @Test(expected = EtaBackupException::class)
    fun rejectsUnknownBackupFormatBeforeChangingData(): Unit = runBlocking {
        EtaBackupRepository.inspect(context,
            ByteArrayInputStream("{\"format\":\"other\",\"schemaVersion\":1,\"exportedAt\":0}".toByteArray())
        )
    }

    @Test
    fun roleplayBackupRestoresCardsPersonaAvatarMemoryAndLargeSessionMetadata() = runBlocking {
        CharacterRepository.initialize(context)
        val profile = CharacterRepository.create(CharacterCardCodec.create("角色").withEdits(description = "设定".repeat(40_000)))
        CharacterRepository.writeAvatar(profile.id, Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGMwdgn9DwADRgHM73L1cQAAAABJRU5ErkJggg==",
        )).let { CharacterRepository.save(profile.copy(avatarPath = it)) }
        CharacterRepository.savePersona(UserPersona("旅人", "来自远方"))
        CharacterMemoryRepository.replaceAll(context, profile.id, "# 剧情\n到过山中")
        val binding = CharacterRepository.binding(profile.id)
        val revisions = Json.encodeToString(RoleplayMessageState(
            links = mapOf("role-message" to RoleplayMessageLink("role-message")),
            revisions = mapOf("role-message" to RoleplayReplyRevision("原版", listOf("原版", "版本".repeat(40_000)), 1)),
        ))
        EtaDatabase.get(context).conversationDao().insertConversations(listOf(
            ConversationEntity("role-session", "剧情", false, roleplayJson = Json.encodeToString(binding),
                revisionsJson = revisions, createdAt = 1, updatedAt = 1),
        ))
        EtaDatabase.get(context).conversationDao().insertMessages(listOf(
            ConversationMessageEntity("role-message", "role-session", 0, "assistant", "版本".repeat(40_000)),
        ))
        val output = ByteArrayOutputStream()
        assertEquals(1, EtaBackupRepository.export(context, output).characterCount)
        CharacterRepository.save(profile.copy(card = profile.card.withEdits(description = "改动")))
        CharacterMemoryRepository.replaceAll(context, profile.id, "改动")
        CharacterRepository.savePersona(UserPersona("其他", ""))
        EtaBackupRepository.import(context, ByteArrayInputStream(output.toByteArray()))
        assertEquals("设定".repeat(40_000), CharacterRepository.get(profile.id)?.card?.description)
        assertEquals("旅人", CharacterRepository.persona().name)
        assertEquals("# 剧情\n到过山中", CharacterMemoryRepository.snapshot(context, profile.id).content)
        val row = EtaDatabase.get(context).conversationDao().conversationEntities().single()
        assertEquals(revisions, row.revisionsJson)
        val restoredBinding = Json.decodeFromString<RoleplayBinding>(row.roleplayJson)
        assertTrue(java.io.File(restoredBinding.avatarPath!!).isFile)
        assertEquals(CharacterRepository.get(profile.id)?.avatarPath, restoredBinding.avatarPath)
    }

    @Test
    fun versionOneBackupRemainsReadable() = runBlocking {
        val summary = EtaBackupRepository.inspect(context, ByteArrayInputStream(
            """{"format":"eta-backup","schemaVersion":1,"exportedAt":0}""".toByteArray(),
        ))
        assertEquals(0, summary.characterCount)
    }

    @Test
    fun oldBackupReceivesOnlyModelsAddedAfterItsCatalogRevision() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged()
        val providers = EtaDatabase.get(context).providerDao().providers().map { row ->
            val models = if (row.provider.id == BuiltinProviders.OPENAI_ID) {
                row.models.filterNot { it.modelId == "gpt-6.1-sol" }
            } else {
                row.models
            }
            EtaBackupProvider(row.provider, models)
        }
        val oldBackup = EtaBackupDocument(exportedAt = 0, providers = providers)
        EtaBackupRepository.import(context, ByteArrayInputStream(Json.encodeToString(oldBackup).toByteArray()))

        val restored = ProviderRepository.providerById(BuiltinProviders.OPENAI_ID)!!.models
        assertTrue(restored.any { it.modelId == "gpt-6-astra" })
        assertTrue(restored.none { it.modelId == "gpt-5.6-terra" })
        assertEquals(OfficialModelCatalog.CURRENT_REVISION, SettingsDataStore.officialModelCatalogRevision())
        assertEquals(ProviderAuthModes.API_KEY, ProviderRepository.providerById(BuiltinProviders.OPENAI_ID)?.authMode)
    }

    @Test
    fun currentBackupKeepsDeletionOfNewCatalogModel() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged()
        val provider = ProviderRepository.providerById(BuiltinProviders.OPENAI_ID)!!
        val removed = provider.models.first { it.modelId == "gpt-6.1-sol" }
        ModelRepository.deleteModel(provider.id, removed.id)
        val output = ByteArrayOutputStream()
        EtaBackupRepository.export(context, output)
        val document = Json.decodeFromString<EtaBackupDocument>(output.toString(Charsets.UTF_8.name()))
        assertEquals(OfficialModelCatalog.CURRENT_REVISION, document.catalogRevision)

        EtaBackupRepository.import(context, ByteArrayInputStream(output.toByteArray()))

        assertTrue(ProviderRepository.providerById(provider.id)!!.models.none { it.modelId == removed.modelId })
        assertEquals(OfficialModelCatalog.CURRENT_REVISION, SettingsDataStore.officialModelCatalogRevision())
    }

    @Test
    fun memoryWriteFailureRollsBackDatabaseAndEarlierMemoryFiles() = runBlocking {
        CharacterRepository.initialize(context)
        ProviderRepository.addProvider(OpenAiCompatibleProviderSetting(
            id = "existing-provider", name = "原订阅", baseUrl = "https://api.openai.com/v1",
            authMode = ProviderAuthModes.OPENAI_CHATGPT,
        ))
        val first = CharacterRepository.create(CharacterCardCodec.create("原角色"))
        val blocked = CharacterRepository.create(CharacterCardCodec.create("阻塞角色"))
        CharacterMemoryRepository.replaceAll(context, first.id, "原剧情记忆")
        AgentMemoryRepository.replaceAll("原现实记忆")
        val database = EtaDatabase.get(context)
        val records = database.characterDao().characters().map { row ->
            if (row.id == first.id) row.copy(cardJson = CharacterCardCodec.encodeJson(first.card.withEdits(name = "备份角色"))) else row
        }
        val document = EtaBackupDocument(exportedAt = 0, memoryMd = "新的现实记忆",
            roleplay = CharacterBackupData(characters = records, assets = listOf(
                CharacterBackupAsset(first.id, memoryMd = "新的剧情记忆"),
                CharacterBackupAsset(blocked.id, memoryMd = "写入失败"),
            )),
        )
        val blocker = java.io.File(context.filesDir, "roleplay/${blocked.id}/memory")
        check(blocker.parentFile!!.mkdirs() || blocker.parentFile!!.isDirectory)
        blocker.writeText("阻止创建目录")
        val credentials = mutableMapOf("existing-provider" to "fixture-existing-credential")
        var committed = false
        try {
            var failed = false
            try {
                EtaBackupRepository.import(
                    context, ByteArrayInputStream(Json.encodeToString(document).toByteArray()),
                    onCommitted = { committed = true },
                    forgetCredentials = { credentials.remove(it) },
                )
            } catch (_: AgentMemoryException) {
                failed = true
            }
            assertTrue(failed)
            assertFalse(committed)
            assertEquals(mapOf("existing-provider" to "fixture-existing-credential"), credentials)
            assertEquals("原角色", CharacterRepository.get(first.id)?.card?.name)
            assertEquals("原剧情记忆", CharacterMemoryRepository.snapshot(context, first.id).content)
            assertEquals("原现实记忆", AgentMemoryRepository.snapshot().content)
        } finally {
            check(blocker.delete())
        }
    }

    @Test
    fun invalidRevisionBackupIsRejectedBeforeExistingConversationsAreChanged() = runBlocking {
        val dao = EtaDatabase.get(context).conversationDao()
        val original = ConversationEntity("original", "原会话", false, createdAt = 1, updatedAt = 1)
        dao.insertConversations(listOf(original))
        val invalidStates = listOf(
            "not-json",
            Json.encodeToString(RoleplayMessageState(
                links = mapOf("reply" to RoleplayMessageLink("reply")),
                revisions = mapOf("reply" to RoleplayReplyRevision("原版", listOf("原版"), 9)),
            )),
            Json.encodeToString(RoleplayMessageState(pendingRewrites = mapOf("run" to "outside-conversation"))),
        )
        invalidStates.forEach { revisions ->
            val document = EtaBackupDocument(exportedAt = 0,
                conversations = listOf(ConversationEntity("new", "导入会话", false, revisionsJson = revisions, createdAt = 1, updatedAt = 1)),
                messages = listOf(ConversationMessageEntity("reply", "new", 0, "assistant", "原版")),
            )
            var failed = false
            try {
                EtaBackupRepository.import(context, ByteArrayInputStream(Json.encodeToString(document).toByteArray()))
            } catch (_: EtaBackupException) {
                failed = true
            }
            assertTrue(failed)
            assertEquals(listOf("original"), dao.conversationEntities().map { it.id })
        }
    }

    @Test
    fun exportAndImportRestoresMcpServersAndSecrets() = runBlocking {
        val database = EtaDatabase.get(context)
        val secretStore = McpSecretStore(context)
        val httpTools = """[{"name":"web_search","description":"Search the web","inputSchema":{"type":"object"}}]"""
        val httpEnabledTools = """["web_search"]"""
        val httpServer = McpServerEntity(
            id = "http-server",
            name = "Test HTTP Server",
            url = "https://example.com/mcp",
            enabled = true,
            protocolMode = McpProtocolMode.AUTO,
            authorizationType = McpAuthorizationType.BEARER,
            toolsJson = httpTools,
            enabledToolNamesJson = httpEnabledTools,
            createdAt = 1000L,
            sortOrder = 0,
            lastRefreshedAt = null,
            lastProtocolVersion = null,
            toolsExpireAt = null,
            transportType = McpTransportType.HTTP,
        )
        val stdioTools = """[{"name":"calculate","description":"Perform calculations","inputSchema":{"type":"object"}}]"""
        val stdioEnabledTools = """["calculate"]"""
        val stdioServer = McpServerEntity(
            id = "stdio-server",
            name = "Test STDIO Server",
            url = "",
            enabled = true,
            protocolMode = McpProtocolMode.LATEST,
            authorizationType = McpAuthorizationType.NONE,
            toolsJson = stdioTools,
            enabledToolNamesJson = stdioEnabledTools,
            createdAt = 2000L,
            sortOrder = 1,
            lastRefreshedAt = null,
            lastProtocolVersion = null,
            toolsExpireAt = null,
            transportType = McpTransportType.STDIO,
            command = "python3",
            argsJson = "[\"-m\",\"test\"]",
            stdioEnvironment = McpStdioEnvironment.LINUX,
            cwd = "/tmp",
            hasEnvVars = true,
        )
        database.mcpServerDao().replaceAll(listOf(httpServer, stdioServer))
        secretStore.setBearerToken("http-server", "secret-token-xyz")
        secretStore.setEnvVars("stdio-server", mapOf("MY_API_KEY" to "k-123", "MY_ENV" to "prod"))

        val output = ByteArrayOutputStream()
        val summary = EtaBackupRepository.export(context, output)
        assertEquals(2, summary.mcpServerCount)

        // Clear local MCP servers and secrets
        database.mcpServerDao().deleteAll()
        secretStore.clear("http-server")
        secretStore.clear("stdio-server")
        assertTrue(database.mcpServerDao().servers().isEmpty())
        assertTrue(secretStore.bearerToken("http-server") == null)
        assertTrue(secretStore.envVars("stdio-server").isEmpty())

        val imported = EtaBackupRepository.import(context, ByteArrayInputStream(output.toByteArray()))
        assertEquals(2, imported.mcpServerCount)

        val restoredServers = database.mcpServerDao().servers()
        assertEquals(2, restoredServers.size)
        val restoredHttp = restoredServers.first { it.id == "http-server" }
        assertEquals("Test HTTP Server", restoredHttp.name)
        assertEquals("https://example.com/mcp", restoredHttp.url)
        assertEquals(McpAuthorizationType.BEARER, restoredHttp.authorizationType)
        assertEquals(httpTools, restoredHttp.toolsJson)
        assertEquals(httpEnabledTools, restoredHttp.enabledToolNamesJson)
        assertEquals("secret-token-xyz", secretStore.bearerToken("http-server"))

        val restoredStdio = restoredServers.first { it.id == "stdio-server" }
        assertEquals("Test STDIO Server", restoredStdio.name)
        assertEquals("python3", restoredStdio.command)
        assertEquals("[\"-m\",\"test\"]", restoredStdio.argsJson)
        assertEquals(stdioTools, restoredStdio.toolsJson)
        assertEquals(stdioEnabledTools, restoredStdio.enabledToolNamesJson)
        assertEquals(mapOf("MY_API_KEY" to "k-123", "MY_ENV" to "prod"), secretStore.envVars("stdio-server"))
    }

    @Test
    fun exportAndImportRestoresSkillsAndPackages() = runBlocking {
        val database = EtaDatabase.get(context)
        val skillsRoot = SkillRuntime.skillsRoot(context)

        // 1. User skill with scripts and references
        val userSkillDir = File(skillsRoot, "custom-calc")
        userSkillDir.mkdirs()
        File(userSkillDir, "SKILL.md").writeText("---\nname: custom-calc\ndescription: A calculation skill\n---\n# Custom Calc")
        val scriptsDir = File(userSkillDir, "scripts")
        scriptsDir.mkdirs()
        File(scriptsDir, "calc.py").writeText("print(42)")
        val referencesDir = File(userSkillDir, "references")
        referencesDir.mkdirs()
        File(referencesDir, "guide.md").writeText("# User Guide\nHow to use custom-calc")

        val userRegistry = SkillRegistryEntity(
            skillId = "custom-calc",
            enabled = true,
            source = "user",
            installState = "installed",
        )

        // 2. Overridden builtin skill
        val overriddenDir = File(skillsRoot, "builtin-weather")
        overriddenDir.mkdirs()
        File(overriddenDir, "SKILL.md").writeText("---\nname: builtin-weather\ndescription: Custom weather prompt\n---\n# Overridden Weather")
        val dataDir = File(overriddenDir, "data")
        dataDir.mkdirs()
        File(dataDir, "ERRORS.md").writeText("# Learned Error Patterns")

        val overriddenRegistry = SkillRegistryEntity(
            skillId = "builtin-weather",
            enabled = true,
            source = "builtin",
            installState = "installed",
        )

        // 3. Disabled builtin skill
        val disabledDir = File(skillsRoot, "builtin-translator")
        disabledDir.mkdirs()
        File(disabledDir, "SKILL.md").writeText("---\nname: builtin-translator\ndescription: Translation skill\n---\n# Disabled Translator")

        val disabledRegistry = SkillRegistryEntity(
            skillId = "builtin-translator",
            enabled = false,
            source = "builtin",
            installState = "installed",
        )

        // 4. Removed builtin skill (no directory on disk)
        val removedBuiltin = SkillRegistryEntity(
            skillId = "removed-skill",
            enabled = false,
            source = "builtin",
            installState = "removed_builtin",
        )

        database.skillDao().replaceRegistry(listOf(userRegistry, overriddenRegistry, disabledRegistry, removedBuiltin))

        val output = ByteArrayOutputStream()
        val summary = EtaBackupRepository.export(context, output)
        assertTrue(summary.skillCount >= 3)

        // Clear local skills directory and registry
        userSkillDir.deleteRecursively()
        overriddenDir.deleteRecursively()
        disabledDir.deleteRecursively()
        database.skillDao().deleteRegistryEntries()
        SkillRuntime.createIndexService(context).listSkillsForManagement(forceRefresh = true)

        val imported = EtaBackupRepository.import(context, ByteArrayInputStream(output.toByteArray()))
        assertTrue(imported.skillCount >= 3)

        val restoredRegistry = database.skillDao().registryEntries()
        assertTrue(restoredRegistry.any { it.skillId == "custom-calc" && it.enabled && it.source == "user" && it.installState == "installed" })
        assertTrue(restoredRegistry.any { it.skillId == "builtin-weather" && it.enabled && it.source == "builtin" && it.installState == "installed" })
        assertTrue(restoredRegistry.any { it.skillId == "builtin-translator" && !it.enabled && it.source == "builtin" && it.installState == "installed" })
        assertTrue(restoredRegistry.any { it.skillId == "removed-skill" && !it.enabled && it.source == "builtin" && it.installState == "removed_builtin" })

        // Check user skill files (SKILL.md, scripts, references)
        val restoredSkillMd = File(skillsRoot, "custom-calc/SKILL.md")
        assertTrue(restoredSkillMd.isFile)
        assertTrue(restoredSkillMd.readText().contains("custom-calc"))

        val restoredScript = File(skillsRoot, "custom-calc/scripts/calc.py")
        assertTrue(restoredScript.isFile)
        assertEquals("print(42)", restoredScript.readText())

        val restoredReference = File(skillsRoot, "custom-calc/references/guide.md")
        assertTrue(restoredReference.isFile)
        assertEquals("# User Guide\nHow to use custom-calc", restoredReference.readText())

        // Check overridden builtin files (SKILL.md and data/ERRORS.md)
        val restoredOverriddenMd = File(skillsRoot, "builtin-weather/SKILL.md")
        assertTrue(restoredOverriddenMd.isFile)
        assertTrue(restoredOverriddenMd.readText().contains("Overridden Weather"))

        val restoredErrorsMd = File(skillsRoot, "builtin-weather/data/ERRORS.md")
        assertTrue(restoredErrorsMd.isFile)
        assertEquals("# Learned Error Patterns", restoredErrorsMd.readText())

        // Check disabled builtin files
        val restoredDisabledMd = File(skillsRoot, "builtin-translator/SKILL.md")
        assertTrue(restoredDisabledMd.isFile)
        assertTrue(restoredDisabledMd.readText().contains("Disabled Translator"))

        // Check removed builtin has no directory restored
        val removedDir = File(skillsRoot, "removed-skill")
        assertFalse(removedDir.exists())
    }

    @Test
    fun legacyBackupPreservesExistingMcpServersAndSkills() = runBlocking {
        val database = EtaDatabase.get(context)
        val secretStore = McpSecretStore(context)
        val existingServer = McpServerEntity(
            id = "existing-server",
            name = "Existing MCP Server",
            url = "https://example.com/existing",
            enabled = true,
            protocolMode = McpProtocolMode.AUTO,
            authorizationType = McpAuthorizationType.BEARER,
            toolsJson = "[]",
            enabledToolNamesJson = "[]",
            createdAt = 1000L,
            sortOrder = 0,
            lastRefreshedAt = null,
            lastProtocolVersion = null,
            toolsExpireAt = null,
            transportType = McpTransportType.HTTP,
        )
        database.mcpServerDao().replaceAll(listOf(existingServer))
        secretStore.setBearerToken("existing-server", "existing-secret")
        secretStore.setEnvVars("existing-server", mapOf("EXISTING_KEY" to "existing-val"))

        val skillsRoot = SkillRuntime.skillsRoot(context)
        val existingSkillDir = File(skillsRoot, "existing-skill")
        existingSkillDir.mkdirs()
        File(existingSkillDir, "SKILL.md").writeText("---\nname: existing-skill\ndescription: Keep me\n---\n# Existing Skill")
        val existingRegistry = SkillRegistryEntity(
            skillId = "existing-skill",
            enabled = true,
            source = "user",
            installState = "installed",
        )
        database.skillDao().replaceRegistry(listOf(existingRegistry))

        // Test legacy payloads: v1, v2, and v3 backups where skills and mcpServers are null
        val legacyPayloads = listOf(
            """{"format":"eta-backup","schemaVersion":1,"exportedAt":500}""",
            """{"format":"eta-backup","schemaVersion":2,"exportedAt":800}""",
            """{"format":"eta-backup","schemaVersion":3,"exportedAt":1000,"conversations":[{"id":"c1","title":"Title","thinkingEnabled":false,"createdAt":1,"updatedAt":1}]}""",
        )

        for (payload in legacyPayloads) {
            val summary = EtaBackupRepository.import(context, ByteArrayInputStream(payload.toByteArray()))
            assertEquals(0, summary.mcpServerCount)
            assertEquals(0, summary.skillCount)

            // Verify existing MCP server and secrets are preserved
            val currentServers = database.mcpServerDao().servers()
            assertEquals(listOf("existing-server"), currentServers.map { it.id })
            assertEquals("existing-secret", secretStore.bearerToken("existing-server"))
            assertEquals(mapOf("EXISTING_KEY" to "existing-val"), secretStore.envVars("existing-server"))

            // Verify existing skill and registry are preserved
            val currentRegistry = database.skillDao().registryEntries()
            assertEquals(listOf("existing-skill"), currentRegistry.map { it.skillId })
            assertTrue(File(skillsRoot, "existing-skill/SKILL.md").isFile)
        }
    }

    @Test
    fun invalidMcpOrSkillBackupIsRejectedBeforeModifyingData() = runBlocking {
        val database = EtaDatabase.get(context)
        val secretStore = McpSecretStore(context)
        val skillsRoot = SkillRuntime.skillsRoot(context)

        val initialServer = McpServerEntity(
            id = "safe-server",
            name = "Safe Server",
            url = "https://safe.com",
            enabled = true,
            protocolMode = McpProtocolMode.AUTO,
            authorizationType = McpAuthorizationType.BEARER,
            toolsJson = "[]",
            enabledToolNamesJson = "[]",
            createdAt = 100L,
            sortOrder = 0,
            lastRefreshedAt = null,
            lastProtocolVersion = null,
            toolsExpireAt = null,
            transportType = McpTransportType.HTTP,
        )
        database.mcpServerDao().replaceAll(listOf(initialServer))
        secretStore.setBearerToken("safe-server", "safe-token")
        secretStore.setEnvVars("safe-server", mapOf("SAFE_ENV" to "1"))

        val safeSkillDir = File(skillsRoot, "safe-skill")
        safeSkillDir.mkdirs()
        File(safeSkillDir, "SKILL.md").writeText("---\nname: safe-skill\ndescription: Safe\n---\n# Safe Skill")
        val safeRegistry = SkillRegistryEntity("safe-skill", true, "user", "installed")
        database.skillDao().replaceRegistry(listOf(safeRegistry))

        val invalidBackups = listOf(
            // Unsafe skill relative path with ..
            EtaBackupDocument(
                exportedAt = 0,
                skills = SkillBackupData(
                    registry = listOf(SkillRegistryEntity("skill-a", true, "user", "installed")),
                    packages = listOf(
                        SkillBackupPackage(
                            skillId = "skill-a",
                            files = listOf(
                                SkillBackupFile("SKILL.md", Base64.getEncoder().encodeToString("---\nname: skill-a\ndescription: desc\n---\n".toByteArray())),
                                SkillBackupFile("../escape.txt", Base64.getEncoder().encodeToString("malicious".toByteArray())),
                            ),
                        ),
                    ),
                ),
            ),
            // Unsafe skill relative path with backslash
            EtaBackupDocument(
                exportedAt = 0,
                skills = SkillBackupData(
                    registry = listOf(SkillRegistryEntity("skill-bs", true, "user", "installed")),
                    packages = listOf(
                        SkillBackupPackage(
                            skillId = "skill-bs",
                            files = listOf(
                                SkillBackupFile("SKILL.md", Base64.getEncoder().encodeToString("---\nname: skill-bs\ndescription: desc\n---\n".toByteArray())),
                                SkillBackupFile("sub\\file.txt", Base64.getEncoder().encodeToString("malicious".toByteArray())),
                            ),
                        ),
                    ),
                ),
            ),
            // Missing SKILL.md
            EtaBackupDocument(
                exportedAt = 0,
                skills = SkillBackupData(
                    registry = listOf(SkillRegistryEntity("skill-b", true, "user", "installed")),
                    packages = listOf(
                        SkillBackupPackage(
                            skillId = "skill-b",
                            files = listOf(
                                SkillBackupFile("other.txt", Base64.getEncoder().encodeToString("data".toByteArray())),
                            ),
                        ),
                    ),
                ),
            ),
            // Invalid skill ID with uppercase / underscore
            EtaBackupDocument(
                exportedAt = 0,
                skills = SkillBackupData(
                    registry = listOf(SkillRegistryEntity("Invalid_Skill_ID", true, "user", "installed")),
                    packages = listOf(
                        SkillBackupPackage(
                            skillId = "Invalid_Skill_ID",
                            files = listOf(
                                SkillBackupFile("SKILL.md", Base64.getEncoder().encodeToString("---\nname: Invalid_Skill_ID\ndescription: desc\n---\n".toByteArray())),
                            ),
                        ),
                    ),
                ),
            ),
            // Invalid skill source
            EtaBackupDocument(
                exportedAt = 0,
                skills = SkillBackupData(
                    registry = listOf(SkillRegistryEntity("skill-src", true, "third_party", "installed")),
                ),
            ),
            // User skill with removed_builtin state
            EtaBackupDocument(
                exportedAt = 0,
                skills = SkillBackupData(
                    registry = listOf(SkillRegistryEntity("skill-user-rm", true, "user", "removed_builtin")),
                ),
            ),
            // SKILL.md without valid name
            EtaBackupDocument(
                exportedAt = 0,
                skills = SkillBackupData(
                    registry = listOf(SkillRegistryEntity("skill-noname", true, "user", "installed")),
                    packages = listOf(
                        SkillBackupPackage(
                            skillId = "skill-noname",
                            files = listOf(
                                SkillBackupFile("SKILL.md", Base64.getEncoder().encodeToString("No frontmatter here".toByteArray())),
                            ),
                        ),
                    ),
                ),
            ),
            // Duplicate package skill IDs
            EtaBackupDocument(
                exportedAt = 0,
                skills = SkillBackupData(
                    registry = listOf(SkillRegistryEntity("skill-dup", true, "user", "installed")),
                    packages = listOf(
                        SkillBackupPackage(
                            skillId = "skill-dup",
                            files = listOf(
                                SkillBackupFile("SKILL.md", Base64.getEncoder().encodeToString("---\nname: skill-dup\ndescription: desc\n---\n".toByteArray())),
                            ),
                        ),
                        SkillBackupPackage(
                            skillId = "skill-dup",
                            files = listOf(
                                SkillBackupFile("SKILL.md", Base64.getEncoder().encodeToString("---\nname: skill-dup\ndescription: desc\n---\n".toByteArray())),
                            ),
                        ),
                    ),
                ),
            ),
            // Invalid MCP HTTP URL
            EtaBackupDocument(
                exportedAt = 0,
                mcpServers = listOf(
                    McpBackupServer(
                        server = initialServer.copy(id = "bad-url", url = "not-a-valid-url"),
                    ),
                ),
            ),
            // Invalid envVar name in MCP
            EtaBackupDocument(
                exportedAt = 0,
                mcpServers = listOf(
                    McpBackupServer(
                        server = initialServer.copy(id = "bad-env"),
                        envVars = mapOf("123INVALID" to "value"),
                    ),
                ),
            ),
            // Invalid stdio command with null character
            EtaBackupDocument(
                exportedAt = 0,
                mcpServers = listOf(
                    McpBackupServer(
                        server = initialServer.copy(
                            id = "bad-stdio-cmd",
                            transportType = McpTransportType.STDIO,
                            command = "python\u00003",
                        ),
                    ),
                ),
            ),
            // Malformed toolsJson
            EtaBackupDocument(
                exportedAt = 0,
                mcpServers = listOf(
                    McpBackupServer(
                        server = initialServer.copy(
                            id = "bad-tools-json",
                            toolsJson = "not-a-valid-json-array",
                        ),
                    ),
                ),
            ),
            // Duplicate MCP server ID
            EtaBackupDocument(
                exportedAt = 0,
                mcpServers = listOf(
                    McpBackupServer(server = initialServer.copy(id = "dup-mcp")),
                    McpBackupServer(server = initialServer.copy(id = "dup-mcp")),
                ),
            ),
        )

        invalidBackups.forEach { invalidDoc ->
            var failed = false
            try {
                EtaBackupRepository.import(context, ByteArrayInputStream(Json.encodeToString(invalidDoc).toByteArray()))
            } catch (_: EtaBackupException) {
                failed = true
            }
            assertTrue(failed)
            // Ensure data was untouched
            assertEquals(listOf("safe-server"), database.mcpServerDao().servers().map { it.id })
            assertEquals("safe-token", secretStore.bearerToken("safe-server"))
            assertEquals(mapOf("SAFE_ENV" to "1"), secretStore.envVars("safe-server"))
            assertEquals(listOf("safe-skill"), database.skillDao().registryEntries().map { it.skillId })
            assertTrue(File(skillsRoot, "safe-skill/SKILL.md").isFile)
        }
    }

    @Test
    fun mcpAndSkillRollbackOnFailure() = runBlocking {
        val database = EtaDatabase.get(context)
        val secretStore = McpSecretStore(context)
        val skillsRoot = SkillRuntime.skillsRoot(context)

        // Existing state
        val originalServer = McpServerEntity(
            id = "original-server",
            name = "Original Server",
            url = "https://original.com",
            enabled = true,
            protocolMode = McpProtocolMode.AUTO,
            authorizationType = McpAuthorizationType.BEARER,
            toolsJson = "[]",
            enabledToolNamesJson = "[]",
            createdAt = 100L,
            sortOrder = 0,
            lastRefreshedAt = null,
            lastProtocolVersion = null,
            toolsExpireAt = null,
            transportType = McpTransportType.HTTP,
        )
        database.mcpServerDao().replaceAll(listOf(originalServer))
        secretStore.setBearerToken("original-server", "original-token")
        secretStore.setEnvVars("original-server", mapOf("ORIG_KEY" to "orig-val"))

        val originalSkillDir = File(skillsRoot, "original-skill")
        originalSkillDir.mkdirs()
        File(originalSkillDir, "SKILL.md").writeText("---\nname: original-skill\ndescription: Orig\n---\n# Orig")
        val originalRegistry = SkillRegistryEntity("original-skill", true, "user", "installed")
        database.skillDao().replaceRegistry(listOf(originalRegistry))

        CharacterRepository.initialize(context)
        val blocked = CharacterRepository.create(CharacterCardCodec.create("阻塞角色"))
        val blocker = File(context.filesDir, "roleplay/${blocked.id}/memory")
        check(blocker.parentFile!!.mkdirs() || blocker.parentFile!!.isDirectory)
        blocker.writeText("阻止创建目录")

        val newServer = McpServerEntity(
            id = "new-server",
            name = "New Server",
            url = "https://new.com",
            enabled = true,
            protocolMode = McpProtocolMode.AUTO,
            authorizationType = McpAuthorizationType.BEARER,
            toolsJson = "[]",
            enabledToolNamesJson = "[]",
            createdAt = 200L,
            sortOrder = 0,
            lastRefreshedAt = null,
            lastProtocolVersion = null,
            toolsExpireAt = null,
            transportType = McpTransportType.HTTP,
        )
        val document = EtaBackupDocument(
            exportedAt = 0,
            mcpServers = listOf(
                McpBackupServer(
                    newServer,
                    bearerToken = "new-token",
                    envVars = mapOf("NEW_KEY" to "new-val"),
                )
            ),
            skills = SkillBackupData(
                registry = listOf(SkillRegistryEntity("new-skill", true, "user", "installed")),
                packages = listOf(
                    SkillBackupPackage(
                        skillId = "new-skill",
                        files = listOf(
                            SkillBackupFile("SKILL.md", Base64.getEncoder().encodeToString("---\nname: new-skill\ndescription: New\n---\n# New".toByteArray())),
                        ),
                    ),
                ),
            ),
            roleplay = CharacterBackupData(
                characters = listOf(database.characterDao().characters().first { it.id == blocked.id }),
                assets = listOf(CharacterBackupAsset(blocked.id, memoryMd = "写入失败")),
            ),
        )

        try {
            var failed = false
            try {
                EtaBackupRepository.import(context, ByteArrayInputStream(Json.encodeToString(document).toByteArray()))
            } catch (_: AgentMemoryException) {
                failed = true
            }
            assertTrue(failed)

            // Verify MCP servers and secrets are rolled back
            assertEquals(listOf("original-server"), database.mcpServerDao().servers().map { it.id })
            assertEquals("original-token", secretStore.bearerToken("original-server"))
            assertEquals(mapOf("ORIG_KEY" to "orig-val"), secretStore.envVars("original-server"))
            assertTrue(secretStore.bearerToken("new-server") == null)
            assertTrue(secretStore.envVars("new-server").isEmpty())

            // Verify skills and registry are rolled back
            val currentRegistryIds = database.skillDao().registryEntries().map { it.skillId }
            assertTrue("original-skill" in currentRegistryIds)
            assertFalse("new-skill" in currentRegistryIds)
            assertTrue(File(skillsRoot, "original-skill/SKILL.md").isFile)
            assertFalse(File(skillsRoot, "new-skill/SKILL.md").exists())

            // Verify avatar assets were discarded on rollback
            val avatarFiles = File(context.filesDir, "roleplay/avatars").listFiles()?.filter { it.isFile }.orEmpty()
            assertTrue(avatarFiles.isEmpty())
        } finally {
            check(blocker.delete())
        }
    }

    @Test
    fun stageAndSwapDirectoriesHandlesRollbackAndRestoresSkillsRoot() = runBlocking {
        val skillsRoot = SkillRuntime.skillsRoot(context)
        val originalSkillDir = File(skillsRoot, "live-skill")
        originalSkillDir.mkdirs()
        File(originalSkillDir, "SKILL.md").writeText("---\nname: live-skill\ndescription: Live\n---\n# Live")

        val data = SkillBackupData(
            registry = listOf(SkillRegistryEntity("incoming-skill", true, "user", "installed")),
            packages = listOf(
                SkillBackupPackage(
                    skillId = "incoming-skill",
                    files = listOf(
                        SkillBackupFile("SKILL.md", Base64.getEncoder().encodeToString("---\nname: incoming-skill\ndescription: In\n---\n# In".toByteArray())),
                    ),
                ),
            ),
        )
        val staged = SkillBackupTransfer.stageAndSwapDirectories(context, data)
        assertTrue(File(skillsRoot, "incoming-skill/SKILL.md").isFile)
        assertFalse(File(skillsRoot, "live-skill/SKILL.md").exists())

        staged.rollback(RuntimeException("Simulated failure"))

        assertTrue(File(skillsRoot, "live-skill/SKILL.md").isFile)
        assertFalse(File(skillsRoot, "incoming-skill/SKILL.md").exists())
    }

    @Test
    fun exportAndImportRestoresWebToolsAndSpeechSettings() = runBlocking {
        val customWebTools = WebToolsSettings(
            searchBackend = WebToolBackend.CUSTOM,
            fetchBackend = WebToolBackend.DEEPSEEK,
            deepSeek = DeepSeekWebConfig(baseUrl = "https://custom.deepseek.example.com"),
            customSearch = CustomWebSearchConfig(url = "https://search.example.com/api"),
        )
        val customSpeech = SpeechSettings(
            asr = AsrProvider.DOUBAO,
            tts = TtsProvider.GEMINI,
            autoSpeak = true,
            chatAutoSpeak = true,
            geminiTts = GeminiTtsConfig(voice = "Puck"),
            refineTranscript = true,
        )

        SettingsDataStore.setWebToolsSettings(customWebTools)
        SettingsDataStore.setSpeechSettings(customSpeech)

        val output = ByteArrayOutputStream()
        EtaBackupRepository.export(context, output)

        // Reset settings
        SettingsDataStore.setWebToolsSettings(WebToolsSettings())
        SettingsDataStore.setSpeechSettings(SpeechSettings())
        assertEquals(WebToolsSettings(), SettingsDataStore.webToolsSettings())
        assertEquals(SpeechSettings(), SettingsDataStore.speechSettings())

        val imported = EtaBackupRepository.import(context, ByteArrayInputStream(output.toByteArray()))
        assertTrue(imported.providerCount >= 0)

        assertEquals(customWebTools, SettingsDataStore.webToolsSettings())
        assertEquals(customSpeech, SettingsDataStore.speechSettings())
    }

    @Test
    fun exportAndImportRestoresSubagentSettingsAndCustomProfiles() = runBlocking {
        Prefs.initLocal(context)
        Prefs.localAgentPreferences()?.edit()?.putBoolean(Prefs.Keys.AGENT_SUBAGENT_ENABLED, false)?.commit()
        val customSettings = SubagentSettings(modelId = "custom-subagent-model", reasoningEffort = "high")
        SettingsDataStore.setSubagentSettings(customSettings)

        val agentsDir = SubagentProfileCatalog.defaultAgentsDir()
        agentsDir.mkdirs()
        val customFile = File(agentsDir, "bill-analyzer.md")
        customFile.writeText("---\nname: bill-analyzer\ndescription: 账单分析\nwhenToUse: 统计账单\ntools: [summarize_bills]\n---\n角色设定正文")

        val output = ByteArrayOutputStream()
        val summary = EtaBackupRepository.export(context, output)
        assertTrue(summary.providerCount >= 0)

        // Mutate local state
        Prefs.localAgentPreferences()?.edit()?.putBoolean(Prefs.Keys.AGENT_SUBAGENT_ENABLED, true)?.commit()
        SettingsDataStore.setSubagentSettings(SubagentSettings())
        customFile.delete()
        val tempFile = File(agentsDir, "temporary-agent.md")
        tempFile.writeText("---\nname: temporary-agent\ndescription: 临时代理\n---\n临时正文")
        assertTrue(tempFile.isFile)

        val imported = EtaBackupRepository.import(context, ByteArrayInputStream(output.toByteArray()))
        assertTrue(imported.providerCount >= 0)

        // Verify subagent enabled state and settings restored
        assertFalse(Prefs.isEnabled(Prefs.Keys.AGENT_SUBAGENT_ENABLED))
        assertEquals(customSettings, SettingsDataStore.subagentSettings())

        // Verify custom profile restored and temporary profile deleted
        assertTrue(customFile.isFile)
        assertTrue(customFile.readText().contains("账单分析"))
        assertFalse(tempFile.exists())
        val customProfiles = SubagentProfileCatalog.listCustom(agentsDir)
        assertEquals(listOf("bill-analyzer"), customProfiles.map { it.name })
    }

    @Test
    fun legacyBackupWithoutSubagentPreservesExistingSubagentState() = runBlocking {
        Prefs.initLocal(context)
        Prefs.localAgentPreferences()?.edit()?.putBoolean(Prefs.Keys.AGENT_SUBAGENT_ENABLED, false)?.commit()
        val customSettings = SubagentSettings(modelId = "existing-model", reasoningEffort = "medium")
        SettingsDataStore.setSubagentSettings(customSettings)

        val agentsDir = SubagentProfileCatalog.defaultAgentsDir()
        agentsDir.mkdirs()
        val existingFile = File(agentsDir, "existing-agent.md")
        existingFile.writeText("---\nname: existing-agent\ndescription: 保留已有代理\n---\n已有正文")

        val legacyJson = """{"format":"eta-backup","schemaVersion":4,"exportedAt":1000}"""
        val imported = EtaBackupRepository.import(context, ByteArrayInputStream(legacyJson.toByteArray()))
        assertTrue(imported.providerCount >= 0)

        // Preserved because legacy backup had subagent = null
        assertFalse(Prefs.isEnabled(Prefs.Keys.AGENT_SUBAGENT_ENABLED))
        assertEquals(customSettings, SettingsDataStore.subagentSettings())
        assertTrue(existingFile.isFile)
    }

    @Test
    fun invalidSubagentBackupIsRejectedBeforeModifyingData() = runBlocking {
        Prefs.initLocal(context)
        Prefs.localAgentPreferences()?.edit()?.putBoolean(Prefs.Keys.AGENT_SUBAGENT_ENABLED, true)?.commit()
        SettingsDataStore.setSubagentSettings(SubagentSettings())

        val invalidBackups = listOf(
            // Invalid reasoning effort
            EtaBackupDocument(
                exportedAt = 0,
                subagent = SubagentBackupData(
                    settings = SubagentSettings(reasoningEffort = "ultra_hyper_effort"),
                ),
            ),
            // Duplicate custom profile names
            EtaBackupDocument(
                exportedAt = 0,
                subagent = SubagentBackupData(
                    customProfiles = listOf(
                        SubagentBackupProfile("dup-agent", "---\nname: dup-agent\ndescription: a\n---\nbody"),
                        SubagentBackupProfile("dup-agent", "---\nname: dup-agent\ndescription: b\n---\nbody"),
                    ),
                ),
            ),
            // Invalid profile name (uppercase / spaces)
            EtaBackupDocument(
                exportedAt = 0,
                subagent = SubagentBackupData(
                    customProfiles = listOf(
                        SubagentBackupProfile("Invalid_Name", "---\nname: Invalid_Name\ndescription: a\n---\nbody"),
                    ),
                ),
            ),
            // Blank description
            EtaBackupDocument(
                exportedAt = 0,
                subagent = SubagentBackupData(
                    customProfiles = listOf(
                        SubagentBackupProfile("blank-desc", "---\nname: blank-desc\ndescription:  \n---\nbody"),
                    ),
                ),
            ),
            // Blank body
            EtaBackupDocument(
                exportedAt = 0,
                subagent = SubagentBackupData(
                    customProfiles = listOf(
                        SubagentBackupProfile("blank-body", "---\nname: blank-body\ndescription: valid\n---\n   "),
                    ),
                ),
            ),
        )

        for (invalidDoc in invalidBackups) {
            var failed = false
            try {
                EtaBackupRepository.import(context, ByteArrayInputStream(Json.encodeToString(invalidDoc).toByteArray()))
            } catch (_: EtaBackupException) {
                failed = true
            }
            assertTrue(failed)
        }
    }

    @Test
    fun subagentBackupWithUnknownModelIdPassesValidation() = runBlocking {
        // Requirement 3: if modelId is non-blank, do NOT fail validation if modelId is not in providers
        val doc = EtaBackupDocument(
            exportedAt = 0,
            subagent = SubagentBackupData(
                settings = SubagentSettings(modelId = "unknown-or-deleted-model", reasoningEffort = "low"),
            ),
        )
        val summary = EtaBackupRepository.inspect(context, ByteArrayInputStream(Json.encodeToString(doc).toByteArray()))
        assertTrue(summary.providerCount == 0)
    }

    @Test
    fun legacySystemAsrInBackupCoercesToQwenRealtimeOnRestore() = runBlocking {
        SettingsDataStore.setSpeechSettings(SpeechSettings(asr = AsrProvider.DOUBAO))
        assertEquals(AsrProvider.DOUBAO, SettingsDataStore.speechSettings().asr)

        val legacyJson = """
            {
              "format": "eta-backup",
              "schemaVersion": 4,
              "exportedAt": 1000,
              "speech": {
                "asr": "SYSTEM"
              }
            }
        """.trimIndent()

        EtaBackupJsonStreams.stage(context, ByteArrayInputStream(legacyJson.toByteArray())).use { staged ->
            assertEquals(AsrProvider.QWEN_REALTIME, staged.header.speech?.asr)
        }

        val inspectSummary = EtaBackupRepository.inspect(context, ByteArrayInputStream(legacyJson.toByteArray()))
        assertEquals(0, inspectSummary.providerCount)

        val importSummary = EtaBackupRepository.import(context, ByteArrayInputStream(legacyJson.toByteArray()))
        assertEquals(0, importSummary.providerCount)
        assertEquals(AsrProvider.QWEN_REALTIME, SettingsDataStore.speechSettings().asr)
    }
}
