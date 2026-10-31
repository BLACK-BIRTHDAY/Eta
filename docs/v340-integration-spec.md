# Upstream v3.4.0 Integration Spec

## Overview & Decisions (ADR 0003 & Glossary)

1. **Q1: Subagent Tool Boundaries & Dual-Environment / MCP Prompt Synthesis (`SubagentProfile.kt`, `AgentPromptBuilder.kt`)**:
   - Keep `present_to_user` inside `SubagentProfile.DISALLOWED_TOOLS` so Subagents remain strictly isolated from singleton GUI and Virtual Display Canvas resources.
   - Preserve local full `mcp_*` tool access across all builtin profiles (`explore`, `coder`, `plan`) and ephemeral ad-hoc profiles.
   - In `SubagentProfile.CODER`, combine upstream v3.4.0's explicit `environment="linux"` vs `environment="android"` and bidirectional bind-mount instructions with local `mcp_*` tool highlights and description.
   - In `AgentPromptBuilder.kt`, combine upstream v3.4.0's Virtual Display Canvas routing (`display=auto`, `present_to_user`) and detailed Android/Linux dual-environment + shared mount mapping rules with local PEP 668 (`pip install --break-system-packages`) and Subagent `mcp_*` inheritance rules.

2. **Q2: Virtual Display Canvas & ColorOS 16 Fluid Cloud Coexistence (`EtaFluidCloudStateMapper.kt`, `AgentRuntimeService.kt`, `AgentOverlayCapsule.kt`)**:
   - Adopt upstream v3.4.0's `VirtualDisplayController`, `CanvasRoutingPolicy`, `VirtualDisplayStageCard`, `VirtualDisplaySystemHooks`, and `AgentOverlayCapsule` (`18×32.dp` micro-lens and gallery stage window) intact.
   - Keep `EtaLiveUpdateManager` and `EtaFluidCloudStateMapper` active for background/lockscreen Promoted Ongoing notifications, and enrich `EtaFluidCloudStateMapper.formatToolTexts` with `present_to_user` (`🖥️ 呈递` / `正在将虚拟屏画面移交主屏...`) and `launch_app` / `open_uri` labels.

3. **Q3 (Option B): Backup Schema v4 Compatibility with `AsrProvider.SYSTEM` Removal (`EtaBackupJsonStreams.kt`, `EtaBackupRepositoryTest.kt`)**:
   - Enable `coerceInputValues = true` on `EtaBackupJsonStreams.json` so backup archives created prior to v3.4.0 with `"asr": "SYSTEM"` automatically coerce to `AsrProvider.QWEN_REALTIME` during staging/restore instead of throwing `SerializationException`.
   - Do not add `AGENT_VIRTUAL_DISPLAY_ENABLED` to `EtaBackupDocument`; keep `SCHEMA_VERSION = 4`.
   - Update `EtaBackupRepositoryTest` to verify that restoring legacy `"asr": "SYSTEM"` backup payloads succeeds and coerces to `AsrProvider.QWEN_REALTIME`, and update existing test fixtures from `AsrProvider.SYSTEM` to `AsrProvider.QWEN_REALTIME` / `AsrProvider.DOUBAO`.

4. **Q4: Build Config, Version Bump & Documentation (`app/build.gradle.kts`, `README.md`, `README_EN.md`, `.github/dependabot.yml`)**:
   - Bump `versionCode = 46` and `versionName = "3.4.0"` in `app/build.gradle.kts`, while preserving `compileSdkMinor = 1` and fallback debug keystore signing.
   - Remove `.github/dependabot.yml` as in upstream v3.4.0.
   - Merge upstream v3.4.0's Virtual Display Canvas & contribution notes into `README.md` and `README_EN.md` while keeping the `BLACK-BIRTHDAY` fork architecture highlights.

---

## Ticket Graph

- **Ticket 1 (Wave 1 - Upstream v3.4.0 Merge & Conflict Resolution)**:
  - Create integration branch `integrate/v3.4.0` from current HEAD (`fix/terminal-environment-routing`), commit `GLOSSARY.md`, `docs/adr/0003-v340-virtual-display-and-local-integration.md`, and `docs/v340-integration-spec.md`, and merge tag `v3.4.0`.
  - Resolve `SubagentProfile.kt` conflict and verify all auto-merged files (`AgentPromptBuilder.kt`, `AgentTraceFormatter.kt`, `AgentOverlayText.kt`, `AgentRuntimeService.kt`, `AgentLocalTools.kt`, `EtaAssistantOverlayService.kt`, `Prefs.kt`, `SettingsDataStore.kt`, `SettingsScreen.kt`, `app/build.gradle.kts`, `README.md`, `README_EN.md`).

- **Ticket 2 (Wave 2 - Fluid Cloud Virtual Display Tool Mapping, blocked by Ticket 1)**:
  - Scope: `app/src/main/kotlin/io/github/mangi/eta/agent/runtime/EtaFluidCloudStateMapper.kt`, `app/src/test/kotlin/io/github/mangi/eta/agent/runtime/EtaFluidCloudStateMapperTest.kt`.
  - Add `present_to_user` and virtual display launch tool formatting to `EtaFluidCloudStateMapper` and verify with unit tests.

- **Ticket 3 (Wave 2 - Backup `coerceInputValues` & Legacy `AsrProvider.SYSTEM` Migration Test, blocked by Ticket 1)**:
  - Scope: `app/src/main/kotlin/io/github/mangi/eta/data/repository/EtaBackupJsonStreams.kt`, `app/src/test/kotlin/io/github/mangi/eta/data/repository/EtaBackupRepositoryTest.kt`.
  - Enable `coerceInputValues = true` in `EtaBackupJsonStreams.json`, update `EtaBackupRepositoryTest` fixtures for removed `AsrProvider.SYSTEM`, and add a regression test verifying that a v4 backup JSON with `"asr": "SYSTEM"` restores cleanly to `AsrProvider.QWEN_REALTIME`.
