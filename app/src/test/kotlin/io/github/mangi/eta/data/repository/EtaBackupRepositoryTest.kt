package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.ConversationContextCheckpointEntity
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.ConversationMessageEntity
import io.github.mangi.eta.data.db.ConversationStateEntity
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.McpServerEntity
import io.github.mangi.eta.data.db.SkillRegistryEntity
import io.github.mangi.eta.data.model.McpAuthorizationType
import io.github.mangi.eta.data.model.McpProtocolMode
import io.github.mangi.eta.data.model.McpStdioEnvironment
import io.github.mangi.eta.data.model.McpTransportType
import io.github.mangi.eta.data.model.ModelSource
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        SettingsDataStore.init(context)
        runBlocking {
            SettingsDataStore.setSelection(null, null)
            SettingsDataStore.setOfficialModelCatalogRevision(0)
        }
        ProviderRepository.init(context)
        AgentMemoryRepository.init(context)
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
                row.models.filterNot { it.modelId.startsWith("gpt-6-") || it.modelId == "gpt-5.6-terra" }
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
    }

    @Test
    fun currentBackupKeepsDeletionOfNewCatalogModel() = runBlocking {
        ProviderRepository.ensureBuiltInsMerged()
        val provider = ProviderRepository.providerById(BuiltinProviders.OPENAI_ID)!!
        val removed = provider.models.first { it.modelId == "gpt-6-sol" }
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
        try {
            var failed = false
            try {
                EtaBackupRepository.import(context, ByteArrayInputStream(Json.encodeToString(document).toByteArray()))
            } catch (_: AgentMemoryException) {
                failed = true
            }
            assertTrue(failed)
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
        val httpServer = McpServerEntity(
            id = "http-server",
            name = "Test HTTP Server",
            url = "https://example.com/mcp",
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
        val stdioServer = McpServerEntity(
            id = "stdio-server",
            name = "Test STDIO Server",
            url = "",
            enabled = true,
            protocolMode = McpProtocolMode.LATEST,
            authorizationType = McpAuthorizationType.NONE,
            toolsJson = "[]",
            enabledToolNamesJson = "[]",
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
        assertEquals("secret-token-xyz", secretStore.bearerToken("http-server"))

        val restoredStdio = restoredServers.first { it.id == "stdio-server" }
        assertEquals("Test STDIO Server", restoredStdio.name)
        assertEquals("python3", restoredStdio.command)
        assertEquals(mapOf("MY_API_KEY" to "k-123", "MY_ENV" to "prod"), secretStore.envVars("stdio-server"))
    }

    @Test
    fun exportAndImportRestoresSkillsAndPackages() = runBlocking {
        val database = EtaDatabase.get(context)
        val skillsRoot = SkillRuntime.skillsRoot(context)
        val userSkillDir = File(skillsRoot, "custom-calc")
        userSkillDir.mkdirs()
        File(userSkillDir, "SKILL.md").writeText("---\nname: custom-calc\ndescription: A calculation skill\n---\n# Custom Calc")
        val scriptsDir = File(userSkillDir, "scripts")
        scriptsDir.mkdirs()
        File(scriptsDir, "calc.py").writeText("print(42)")

        val userRegistry = SkillRegistryEntity(
            skillId = "custom-calc",
            enabled = true,
            source = "user",
            installState = "installed",
        )
        val removedBuiltin = SkillRegistryEntity(
            skillId = "removed-skill",
            enabled = false,
            source = "builtin",
            installState = "removed_builtin",
        )
        database.skillDao().replaceRegistry(listOf(userRegistry, removedBuiltin))

        val output = ByteArrayOutputStream()
        val summary = EtaBackupRepository.export(context, output)
        assertTrue(summary.skillCount >= 1)

        // Clear local skills directory and registry
        userSkillDir.deleteRecursively()
        database.skillDao().deleteRegistryEntries()
        SkillRuntime.createIndexService(context).listSkillsForManagement(forceRefresh = true)

        val imported = EtaBackupRepository.import(context, ByteArrayInputStream(output.toByteArray()))
        assertTrue(imported.skillCount >= 1)

        val restoredRegistry = database.skillDao().registryEntries()
        assertTrue(restoredRegistry.any { it.skillId == "custom-calc" && it.enabled && it.source == "user" })
        assertTrue(restoredRegistry.any { it.skillId == "removed-skill" && !it.enabled && it.installState == "removed_builtin" })

        val restoredSkillMd = File(skillsRoot, "custom-calc/SKILL.md")
        assertTrue(restoredSkillMd.isFile)
        assertTrue(restoredSkillMd.readText().contains("custom-calc"))

        val restoredScript = File(skillsRoot, "custom-calc/scripts/calc.py")
        assertTrue(restoredScript.isFile)
        assertEquals("print(42)", restoredScript.readText())
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

        // Legacy backup without skills or mcpServers (version 3)
        val legacyDocument = EtaBackupDocument(
            schemaVersion = 3,
            exportedAt = 1000L,
            conversations = listOf(ConversationEntity("c1", "Title", false, createdAt = 1, updatedAt = 1)),
            skills = null,
            mcpServers = null,
        )
        val bytes = Json.encodeToString(legacyDocument).toByteArray()
        val summary = EtaBackupRepository.import(context, ByteArrayInputStream(bytes))
        assertEquals(0, summary.mcpServerCount)
        assertEquals(0, summary.skillCount)

        // Verify existing MCP server and secrets are preserved
        val currentServers = database.mcpServerDao().servers()
        assertEquals(listOf("existing-server"), currentServers.map { it.id })
        assertEquals("existing-secret", secretStore.bearerToken("existing-server"))

        // Verify existing skill and registry are preserved
        val currentRegistry = database.skillDao().registryEntries()
        assertEquals(listOf("existing-skill"), currentRegistry.map { it.skillId })
        assertTrue(File(skillsRoot, "existing-skill/SKILL.md").isFile)
    }

    @Test
    fun invalidMcpOrSkillBackupIsRejectedBeforeModifyingData() = runBlocking {
        val database = EtaDatabase.get(context)
        val initialServer = McpServerEntity(
            id = "safe-server",
            name = "Safe Server",
            url = "https://safe.com",
            enabled = true,
            protocolMode = McpProtocolMode.AUTO,
            authorizationType = McpAuthorizationType.NONE,
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

        val invalidBackups = listOf(
            // Unsafe skill relative path
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
            mcpServers = listOf(McpBackupServer(newServer, bearerToken = "new-token")),
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
            assertTrue(secretStore.bearerToken("new-server") == null)

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
}
