# Upstream v3.3.5 Integration & Subagent Unification Spec

## Overview & Decisions (ADR 0002 & Glossary)

1. **Upstream v3.3.5 Base Merge**:
   - Merge tag `v3.3.5` from `upstream` (`Mangi-11/Eta`) into integration branch `integrate/v3.3.5`.
   - Preserve all local custom features:
     - Native Google Gemini 3.X / GenAI (`GeminiGenerateContentProvider`, `VertexAiServiceAccountAuth`, native `google_search` grounding, adaptive thinking, multimodal image gen)
     - Zero-Copy Pipe IPC for large conversation histories (`AgentRuntimeWire`)
     - ColorOS 16 / Android 16 Fluid Cloud (`EtaLiveUpdateManager`, `EtaFluidCloudStateMapper`)
     - Double-press power key for Google / ColorOS Wallet (`PowerHooks`)
     - STDIO MCP client & JSON config import (`McpStdioClient`, `McpConfigJsonParser`)
     - Skill Slash Command autocomplete, Skill Editor, Skill Subtree Archive & Manual Update (`SkillOriginStore`, `SkillUpdateGateway`)
     - Backup v4 with Skills & MCP servers (`EtaBackupRepository`, `ExtensionBackupData`)
     - Model `maxOutputTokens` configuration (`Model`, `RuntimeConfigRepository`)
     - `AgentModelRetry.MAX_RETRIES = 10` (per Q5 decision)

2. **Q1 & Q2: Subagent Architecture Unification (`io.github.mangi.eta.agent.subagent.*`)**:
   - Adopt official v3.3.5 `io.github.mangi.eta.agent.subagent` package (`AgentSubagentToolCatalog`, `SubagentProfile`, `SubagentProfileCatalog`, `SubagentSessionStore`, `SubagentActivitySnapshot`, `SubagentExecutor`), UI (`SubagentActivitySheet`, `SubagentSettingsScreen`), and `AgentLoop.executeToolBatch` / `executeParallelSubagents`.
   - Remove deprecated local `app/src/main/kotlin/io/github/mangi/eta/agent/model/AgentSubagentToolCatalog.kt`, `app/src/main/kotlin/io/github/mangi/eta/agent/model/SubagentExecutor.kt`, and `app/src/test/kotlin/io/github/mangi/eta/agent/model/SubagentExecutorTest.kt` (`delegate_task`).
   - Graft the local **Subagent Step Guardrail** & **Partial Output Preservation** into `io.github.mangi.eta.agent.subagent.SubagentExecutor`:
     - Enforce `maxSteps: Int = MAX_TOOL_STEPS` (`const val MAX_TOOL_STEPS = 100`) per `subagent` invocation.
     - Track `stepsTaken` and `collectedOutputs` (up to `MAX_COLLECTED_OUTPUT_CHARS = 2_000` per tool step) inside `childToolExecutor`.
     - When `stepsTaken >= maxSteps`, return a synthetic tool result with `{"ok": true, "limit_reached": true, "code": "SUBAGENT_MAX_STEPS_REACHED", "message": "已达到子代理单次调用最大工具步数上限 ($maxSteps 步)。请不要再调用任何工具，立即将前面 $maxSteps 步已经获取到的所有结果与进展整理成详细报告交回主 Agent，不要丢弃任何已有信息。"}` so the Subagent gracefully synthesizes its final report instead of looping infinitely.
     - Include `"limit_reached"` and `"max_steps"` in the completed JSON result when `limitTriggered` is true, and when an exception interrupts a Subagent after `stepsTaken > 0`, include `"collected_outputs"` (the array of completed tool step summaries/outputs) alongside `"partial_output"` in the error/interrupted JSON result so the primary agent never loses completed work.

3. **Q3: Subagent Configuration, `ModelConfig` Cleanup & Backup Integration**:
   - Use `Prefs.Keys.AGENT_SUBAGENT_ENABLED` (default `true` in `BOOLEAN_DEFAULTS` and `LOCAL_AGENT_KEYS`) and `SubagentSettings(modelId, reasoningEffort)` in `SettingsDataStore` (`subagent_settings_v1`).
   - Clean up legacy local fields from `Settings` (`subagentEnabled`, `subagentModelId`, `subagentReasoningEffort`) and `AgentModelClient.ModelConfig` (`subagentModel`, `subagentEnabled`, `subagentReasoningEffort`, `subagentContextWindow`, `subagentMaxOutputTokens`, `subagentConfigJson`), replacing them with official `val subagentTools: Boolean = Prefs.Keys.BOOLEAN_DEFAULTS.getValue(Prefs.Keys.AGENT_SUBAGENT_ENABLED)` and `KEY_SUBAGENT_TOOLS` in `AgentRuntimeWire`.
   - In `RuntimeConfigRepository.subagentRuntimeConfig(primaryConfig)`, ensure `maxOutputTokens` from the resolved secondary model (or `primaryConfig.maxOutputTokens` when following primary) is preserved.
   - In `SettingsScreen.kt`, replace the inline local subagent dropdowns with the official navigation entry to `AppRoute.SubagentSettings` (`SubagentSettingsScreen`).
   - **Backup & Restore Integration (`EtaBackupRepository`, `ExtensionBackupData`, `EtaBackupValidation`)**:
     - Add optional `val subagent: SubagentBackupData? = null` to `EtaBackupDocument` (where `SubagentBackupData` holds `val enabled: Boolean = true`, `val settings: SubagentSettings = SubagentSettings()`, and `val customProfiles: List<SubagentBackupProfile> = emptyList()`).
     - On export, snapshot `Prefs.isEnabled(Prefs.Keys.AGENT_SUBAGENT_ENABLED)`, `SettingsDataStore.subagentSettings()`, and custom profiles from `SubagentProfileCatalog.listCustom()`.
     - On validate & import, validate custom profile names/descriptions/prompts and restore `SubagentSettings`, `AGENT_SUBAGENT_ENABLED` pref, and custom `agents/<name>.md` files.

4. **Q4: Android 16 Live Updates & Overlay Policy**:
   - Keep `EtaLiveUpdateManager` as the sole owner of Promoted Ongoing notifications (avoiding duplicate capsule conflict with `AgentExecutionService`).
   - In `EtaLiveUpdateManager.buildNotification()`, add `builder.extras.putBoolean("android.requestPromotedOngoing", requestPromoted)` alongside `builder.setRequestPromotedOngoing(requestPromoted)` on API 36 (`Build.VERSION_CODES.BAKLAVA`) so both API 36.0 and 36.1 promote reliably.
   - In `AgentRuntimeService.publishExecutionState()`, adopt upstream v3.3.5's background-run status decoupling (`val activeRuns = runs.all().filterNot { it.session.isTerminal }`, `(owner ?: activeRuns.firstOrNull())?.overlayState?.status`).
   - In `AgentExecutionService`, keep the notification as a non-promoted ongoing foreground service notification (or align `AgentExecutionServiceTest` with our single-capsule design where `EtaLiveUpdateManager` owns `android.requestPromotedOngoing`).
   - Adopt upstream v3.3.5's foreground overlay suppression (`AgentOverlayVisibilityPolicy.shouldSuppressOverlayInEtaUi`) and 8-second auto-dismissing result card (`AgentResultCard`).

5. **Q5: Model Retry Policy**:
   - Keep `AgentModelRetry.MAX_RETRIES = 10` in `AgentModelRetry.kt`, `AgentRuntimeWire.kt`, `AgentModelRetryTest.kt`, and `AgentRuntimeWireTest.kt`.

---

## Ticket Graph

- **Ticket 1 (Wave 1 - Foundation Merge & Core Model/Runtime Unification)**:
  - Merge `v3.3.5` into `integrate/v3.3.5` and resolve all git conflicts across `README.md`, `README_EN.md`, `app/build.gradle.kts`, `AgentLoop.kt`, `AgentModelClient.kt`, `AgentPromptBuilder.kt`, `AgentTraceFormatter.kt`, `AgentRuntimeWire.kt`, `AgentExecutionService.kt`, `AgentRuntimeService.kt`, `RuntimeConfigRepository.kt`, `SettingsScreen.kt`, `Settings.kt`, `SettingsDataStore.kt`, `Prefs.kt`, `AgentOverlayText.kt`.
  - Remove legacy `app/src/main/kotlin/io/github/mangi/eta/agent/model/AgentSubagentToolCatalog.kt`, `app/src/main/kotlin/io/github/mangi/eta/agent/model/SubagentExecutor.kt`, and `app/src/test/kotlin/io/github/mangi/eta/agent/model/SubagentExecutorTest.kt`.

- **Ticket 2 (Wave 2 - Subagent Step Guardrail & Partial Output Preservation, blocked by Ticket 1)**:
  - Scope: `app/src/main/kotlin/io/github/mangi/eta/agent/subagent/SubagentExecutor.kt`, `app/src/test/kotlin/io/github/mangi/eta/agent/subagent/SubagentTest.kt`.
  - Enhance `io.github.mangi.eta.agent.subagent.SubagentExecutor` with `maxSteps: Int = MAX_TOOL_STEPS` (100), `SUBAGENT_MAX_STEPS_REACHED` graceful convergence, and `collected_outputs` retention on step limit or mid-run failure, with unit tests in `SubagentTest.kt`.

- **Ticket 3 (Wave 2 - Subagent Settings & Custom Profiles Backup/Restore, blocked by Ticket 1)**:
  - Scope: `app/src/main/kotlin/io/github/mangi/eta/data/repository/ExtensionBackupData.kt`, `app/src/main/kotlin/io/github/mangi/eta/data/repository/EtaBackupRepository.kt`, `app/src/main/kotlin/io/github/mangi/eta/data/repository/EtaBackupValidation.kt`, `app/src/test/kotlin/io/github/mangi/eta/data/repository/EtaBackupRepositoryTest.kt`.
  - Add `SubagentBackupData` & `SubagentBackupTransfer` to backup and restore `SubagentSettings`, `AGENT_SUBAGENT_ENABLED`, and custom `agents/<name>.md` profiles, with unit tests in `EtaBackupRepositoryTest.kt`.

- **Ticket 4 (Wave 2 - Live Updates Single-Capsule & Test Alignment, blocked by Ticket 1)**:
  - Scope: `app/src/main/kotlin/io/github/mangi/eta/agent/runtime/EtaLiveUpdateManager.kt`, `app/src/main/kotlin/io/github/mangi/eta/agent/runtime/AgentExecutionService.kt`, `app/src/test/kotlin/io/github/mangi/eta/agent/runtime/AgentExecutionServiceTest.kt`, `app/src/test/kotlin/io/github/mangi/eta/agent/runtime/AgentRuntimeWireTest.kt`, `app/src/test/kotlin/io/github/mangi/eta/data/repository/RuntimeConfigRepositoryTest.kt`.
  - Ensure `EtaLiveUpdateManager` sets `extras.putBoolean("android.requestPromotedOngoing", requestPromoted)` on API 36+, keep `AgentExecutionService` non-promoted to prevent dual-capsule conflict, and align unit tests (`AgentExecutionServiceTest`, `AgentRuntimeWireTest`, `RuntimeConfigRepositoryTest`).
