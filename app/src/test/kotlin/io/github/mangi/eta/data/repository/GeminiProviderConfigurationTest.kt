package io.github.mangi.eta.data.repository

import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.CustomBody
import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.CustomProviderSetting
import io.github.mangi.eta.data.model.GeminiEndpointMode
import io.github.mangi.eta.data.model.GeminiProviderSetting
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.withModels
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.ProviderSourceTypes
import io.github.mangi.eta.data.model.ProviderTypes
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.provider.BuiltinProviders
import io.github.mangi.eta.data.provider.GeminiModelCapabilities
import io.github.mangi.eta.data.provider.OfficialModelCatalog
import io.github.mangi.eta.data.provider.ReasoningCapabilityResolver
import io.github.mangi.eta.ui.pages.providers.ProviderConfigDraft
import io.github.mangi.eta.ui.pages.providers.providerAccountAuthMode
import io.github.mangi.eta.ui.pages.providers.toProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GeminiProviderConfigurationTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        SettingsDataStore.init(context)
        ProviderRepository.init(context)
        AgentMemoryRepository.init(context)
        runBlocking {
            SettingsDataStore.setSelection(null, null)
            SettingsDataStore.setOfficialModelCatalogRevision(3)
        }
    }

    @Test
    fun customNativeProxyRoundTripsCopiesAndProjectsWithoutAnOpenAiEndpoint() = runBlocking {
        val source = GeminiProviderSetting(
            id = "native-gemini", name = "原生代理", baseUrl = "https://proxy.example/v1beta", apiKey = "fixture-key",
            hostedWebSearchEnabled = true,
            customHeaders = listOf(CustomHeader("x-fixture", "value")),
            customBody = listOf(CustomBody("generation_config.temperature", JsonPrimitive(0.4))),
            models = listOf(Model(id = "native-model", modelId = "gemini-3.8-flash", displayName = "Flash")),
        )
        ProviderRepository.addProvider(source)
        val stored = requireNotNull(ProviderRepository.providerById(source.id))
        assertEquals(source, stored)
        val copied = requireNotNull(ProviderRepository.copyProvider(source.id))
        assertTrue(copied is GeminiProviderSetting)
        assertEquals(ProviderSourceTypes.GEMINI, copied.sourceType)
        assertTrue(copied.id != source.id && copied.models.single().id != source.models.single().id)
        assertTrue(copied.hostedWebSearchEnabled)
        val config = RuntimeConfigRepository.buildRuntimeConfig(stored, stored.models.single().copy(reasoningOverride = false))
        assertEquals(ProviderTypes.GEMINI, config.providerType)
        assertEquals(ProviderSourceTypes.GEMINI, config.providerSourceType)
        assertEquals("", config.openAiEndpointMode)
        assertEquals(ReasoningEffort.DEFAULT, config.reasoningEffort)
        assertEquals(listOf(ReasoningEffort.DEFAULT), requireNotNull(config.reasoningCapabilities).selectableEfforts)
        val raw = Json.encodeToString<ProviderSetting>(stored)
        assertEquals(stored, Json.decodeFromString<ProviderSetting>(raw))
    }

    @Test
    fun unknownManualModelUsesDefaultWithoutInventingThinkingCapabilities() {
        val config = RuntimeConfigRepository.buildRuntimeConfig(
            GeminiProviderSetting(id = "manual-gemini", name = "Gemini"),
            Model(id = "manual-model", modelId = "gemini-future", displayName = "手动模型"),
        )
        assertEquals(ReasoningEffort.DEFAULT, config.reasoningEffort)
        assertEquals(ReasoningEffort.DEFAULT, config.effectiveReasoningEffort)
        val capabilities = requireNotNull(config.reasoningCapabilities)
        assertEquals(listOf(ReasoningEffort.DEFAULT), capabilities.selectableEfforts)
        assertNull(capabilities.defaultEnabled)
        assertFalse(capabilities.mandatory)
    }

    @Test
    fun unknownModelManualEffortsSurvivePersistenceAndRuntimeProjection() = runBlocking {
        val manual = ModelReasoningCapabilities(supportedEfforts = listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH),
            defaultEnabled = true, mandatory = true)
        val provider = GeminiProviderSetting(id = "manual-config", name = "Gemini", models = listOf(
            Model(id = "future", modelId = "gemini-future", displayName = "未来模型",
                reasoningOverride = true, reasoningCapabilitiesOverride = manual),
        ))
        ProviderRepository.addProvider(provider)
        val stored = requireNotNull(ProviderRepository.providerById(provider.id))
        val model = stored.models.single()
        assertEquals(manual, model.reasoningCapabilitiesOverride)
        assertEquals(manual, RuntimeConfigRepository.buildRuntimeConfig(stored, model).reasoningCapabilities)
        assertEquals(listOf(ReasoningEffort.DEFAULT), requireNotNull(
            RuntimeConfigRepository.buildRuntimeConfig(stored, model.copy(reasoningOverride = null,
                reasoningCapabilitiesOverride = null)).reasoningCapabilities).selectableEfforts)
    }

    @Test
    fun carbonUpgradePreservesExistingModelAndDoesNotRestoreDeletedPresets() = runBlocking {
        val carbon = Model(id = "existing-carbon", modelId = "gemini-4-carbon", displayName = "我的 Carbon",
            contextWindowOverride = 200_000)
        val provider = requireNotNull(BuiltinProviders.providerById(BuiltinProviders.GEMINI_ID))
            .withModels(listOf(carbon))
        ProviderRepository.addProvider(provider)
        SettingsDataStore.setOfficialModelCatalogRevision(4)
        ProviderRepository.ensureBuiltInsMerged()
        assertEquals(listOf(carbon), requireNotNull(ProviderRepository.providerById(provider.id)).models)
        assertEquals(5, SettingsDataStore.officialModelCatalogRevision())
    }

    @Test
    fun builtinMergeAppendsGeminiAndPreservesTheSelectedCompatibleConnection() = runBlocking {
        val source = CustomProviderSetting(
            id = "existing-gemini-compatible", name = "原有兼容连接",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai", apiKey = "fixture-key",
            models = listOf(Model(id = "existing-model", modelId = "gemini-3.8-flash", displayName = "原模型")),
        )
        ProviderRepository.addProvider(source)
        SettingsDataStore.setSelection(source.id, source.models.single().id)
        ProviderRepository.ensureBuiltInsMerged()
        val providers = ProviderRepository.allProviders()
        assertEquals(source, providers.first())
        assertTrue(providers.last() is GeminiProviderSetting)
        assertEquals(BuiltinProviders.GEMINI_ID, providers.last().id)
        assertEquals(source.id, SettingsDataStore.settings().selectedProviderId)
        assertEquals(source.models.single().id, SettingsDataStore.settings().selectedModelId)
        assertEquals(4, providers.last().models.size)
        assertTrue(providers.last().models.any { it.modelId == "gemini-4-carbon" })
    }

    @Test
    fun configurationDraftRetainsNativeProtocolAndSearchToggle() {
        val source = GeminiProviderSetting(id = "native", name = "Gemini")
        val draft = ProviderConfigDraft.from(source).copy(
            name = "修改后的名称", baseUrl = "https://proxy.example/v1beta", apiKey = "fixture-key",
            hostedWebSearchEnabled = true,
        )
        val updated = draft.toProvider(source)
        assertTrue(updated is GeminiProviderSetting)
        assertEquals(draft, ProviderConfigDraft.from(updated))
        assertEquals("", draft.endpointMode)
        assertNull(providerAccountAuthMode(updated))
    }

    @Test
    fun backupUsesNewVersionAndRestoresTheNativeType() = runBlocking {
        val source = GeminiProviderSetting(id = "backup-gemini", name = "备份 Gemini", hostedWebSearchEnabled = true)
        ProviderRepository.addProvider(source)
        val output = ByteArrayOutputStream()
        EtaBackupRepository.export(context, output)
        val raw = output.toString(Charsets.UTF_8.name())
        val document = Json.decodeFromString<EtaBackupDocument>(raw)
        assertEquals(4, document.schemaVersion)
        assertTrue(document.schemaVersion > 3)
        assertEquals(ProviderTypes.GEMINI, document.providers.single { it.provider.id == source.id }.provider.type)
        ProviderRepository.deleteProvider(source.id)
        assertNull(ProviderRepository.providerById(source.id))
        EtaBackupRepository.import(context, ByteArrayInputStream(output.toByteArray()))
        val restored = ProviderRepository.providerById(source.id)
        assertTrue(restored is GeminiProviderSetting)
        assertTrue(requireNotNull(restored).hostedWebSearchEnabled)
        assertFalse(restored.isBuiltIn)
    }

    @Test
    fun geminiTieredAndCapabilitiesOverrideExposeSelectableEffortsInRuntimeConfig() {
        val provider = GeminiProviderSetting(
            id = "gemini-test",
            name = "Gemini",
            baseUrl = "https://generativelanguage.googleapis.com",
            apiKey = "fixture-key",
        )
        // 1. Unlisted gemini-3.8-flash-tiered falls back to Default per Q1=B before and after catalog enrichment,
        // while OfficialModelCatalog still enriches contextWindow and multimodal input modalities
        val tieredModel = Model(
            id = "tiered-model",
            modelId = "gemini-3.8-flash-tiered",
            displayName = "Tiered",
        )
        val rawTieredCaps = requireNotNull(RuntimeConfigRepository.reasoningCapabilities(provider, tieredModel))
        assertEquals(listOf(ReasoningEffort.DEFAULT), rawTieredCaps.selectableEfforts)

        val enrichedTieredModel = OfficialModelCatalog.enrich(provider, listOf(tieredModel)).single()
        assertEquals(1_048_576, enrichedTieredModel.contextWindow)
        assertTrue("audio" in enrichedTieredModel.inputModalities)
        assertFalse(enrichedTieredModel.reasoning == true)
        assertNull(enrichedTieredModel.reasoningCapabilities)
        val tieredCaps = requireNotNull(RuntimeConfigRepository.reasoningCapabilities(provider, enrichedTieredModel))
        assertFalse(tieredCaps.canDisable)
        assertFalse(tieredCaps.supportsBudget)
        assertNull(tieredCaps.maxBudgetTokens)
        assertEquals(listOf(ReasoningEffort.DEFAULT), tieredCaps.selectableEfforts)

        // 2. Custom reasoningCapabilitiesOverride is respected
        val customOverride = ModelReasoningCapabilities(
            supportedEfforts = listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH),
            canDisable = true,
            defaultEffort = ReasoningEffort.LOW,
        )
        val overriddenModel = enrichedTieredModel.copy(
            reasoningOverride = true,
            reasoningCapabilitiesOverride = customOverride,
        )
        val overriddenCaps = requireNotNull(RuntimeConfigRepository.reasoningCapabilities(provider, overriddenModel))
        assertEquals(customOverride, overriddenCaps)
        assertEquals(
            listOf(ReasoningEffort.OFF, ReasoningEffort.DEFAULT, ReasoningEffort.LOW, ReasoningEffort.HIGH),
            overriddenCaps.selectableEfforts,
        )

        // 3. buildRuntimeConfig produces thinkingEnabled = true and effective ReasoningEffort.DEFAULT
        val runtimeConfig = RuntimeConfigRepository.buildRuntimeConfig(provider, enrichedTieredModel)
        assertTrue(runtimeConfig.thinkingEnabled)
        assertEquals(ReasoningEffort.DEFAULT, runtimeConfig.reasoningEffort)
        assertEquals(tieredCaps, runtimeConfig.reasoningCapabilities)

        // 4. Turning off reasoning override on Gemini falls back to Default without sending effort parameters
        val disabledModel = enrichedTieredModel.copy(reasoningOverride = false)
        val disabledCaps = requireNotNull(RuntimeConfigRepository.reasoningCapabilities(provider, disabledModel))
        assertEquals(listOf(ReasoningEffort.DEFAULT), disabledCaps.selectableEfforts)
        assertFalse(disabledCaps.mandatory)
        val disabledConfig = RuntimeConfigRepository.buildRuntimeConfig(provider, disabledModel)
        assertTrue(disabledConfig.thinkingEnabled)
        assertEquals(ReasoningEffort.DEFAULT, disabledConfig.reasoningEffort)
        assertEquals(disabledCaps, disabledConfig.reasoningCapabilities)
    }

    @Test
    fun runtimeConfigProjectsGeminiEndpointModeAndMaxOutputTokens() {
        val model = Model(
            id = "m1",
            modelId = "gemini-4-carbon",
            displayName = "Carbon",
            maxOutputTokens = 16_384,
        )
        val generateContentConfig = RuntimeConfigRepository.buildRuntimeConfig(
            GeminiProviderSetting(id = "gen", name = "Gemini"),
            model,
        )
        assertEquals(GeminiEndpointMode.GENERATE_CONTENT, generateContentConfig.geminiEndpointMode)
        assertEquals(16_384, generateContentConfig.maxOutputTokens)

        val interactionsConfig = RuntimeConfigRepository.buildRuntimeConfig(
            GeminiProviderSetting(
                id = "inter",
                name = "Gemini",
                endpointMode = GeminiEndpointMode.INTERACTIONS,
            ),
            model,
        )
        assertEquals(GeminiEndpointMode.INTERACTIONS, interactionsConfig.geminiEndpointMode)
        assertEquals(16_384, interactionsConfig.maxOutputTokens)
    }

    @Test
    fun officialCatalogEnrichPreservesImageModelsAndUsesConfirmedGeminiReasoning() {
        val provider = GeminiProviderSetting(id = "g", name = "Gemini")
        val input = listOf(
            Model(id = "c", modelId = "gemini-4-carbon", displayName = "gemini-4-carbon"),
            Model(id = "a", modelId = "gemini-4-argon", displayName = "gemini-4-argon"),
            Model(id = "f38", modelId = "gemini-3.8-flash", displayName = "gemini-3.8-flash"),
            Model(id = "f36", modelId = "gemini-3.6-flash", displayName = "gemini-3.6-flash"),
            Model(id = "img1", modelId = "gemini-3.1-flash-image", displayName = "gemini-3.1-flash-image"),
            Model(id = "img2", modelId = "gemini-3-pro-image", displayName = "gemini-3-pro-image"),
        )
        val enriched = OfficialModelCatalog.enrich(provider, input).associateBy { it.modelId }

        for (id in listOf("gemini-4-carbon", "gemini-4-argon", "gemini-3.8-flash", "gemini-3.6-flash")) {
            val model = requireNotNull(enriched[id])
            assertEquals(1_048_576, model.contextWindow)
            assertTrue(model.inputModalities.containsAll(listOf(Model.TEXT_MODALITY, Model.IMAGE_MODALITY, "audio", "video")))
            val expectedCaps = requireNotNull(GeminiModelCapabilities.reasoningCapabilities(id))
            assertEquals(expectedCaps, model.reasoningCapabilities)
            assertEquals(expectedCaps, ReasoningCapabilityResolver.catalogCapabilities(ProviderSourceTypes.GEMINI, id))
            assertFalse(expectedCaps.canDisable)
            assertEquals(ReasoningEffort.MEDIUM, expectedCaps.defaultEffort)
        }

        for (imgId in listOf("gemini-3.1-flash-image", "gemini-3-pro-image")) {
            val img = requireNotNull(enriched[imgId])
            assertEquals(false, img.reasoning)
            assertNull(img.reasoningCapabilities)
            assertEquals(listOf(Model.TEXT_MODALITY, Model.IMAGE_MODALITY), img.outputModalities)
        }
    }
}
