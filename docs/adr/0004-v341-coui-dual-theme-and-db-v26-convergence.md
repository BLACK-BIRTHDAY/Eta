# 0004. Upstream v3.4.1 COUI Dual-Theme Integration and Database v26 Convergence

When integrating upstream official v3.4.1 (COUI & Miuix dual-design UI style with lossless runtime switching, conversation branching, turn performance telemetry, per-tool toggles, and background virtual display focus isolation) with our local architecture, we made three structural decisions:

1. **Idempotent Room Schema Convergence to v26 (`EtaDatabase`)**:
   Both our local branch (v22→v25: `mcp_servers` STDIO columns, `provider_models.max_output_tokens`, `model_providers.auth_mode`) and upstream v3.4.1 (v22→v25: `model_providers.auth_mode`, `conversations.is_pinned`, and 7 turn performance telemetry columns on `conversation_messages`) independently advanced `EtaDatabase` to version 25 with divergent schemas. Leaving the version at 25 would cause existing local v25 installations to skip upstream's `23→24` and `24→25` migrations and crash at runtime with `SQLiteException: no such column`. We bumped `EtaDatabase` to **version 26** and added an idempotent `MIGRATION_25_26` guarded by `PRAGMA table_info(...)` checks so databases upgrading from either local v25 or upstream v22–v25 converge cleanly onto the unified v26 schema.

2. **Full Dual-Theme Parity for Local Features (`COUI` + `Miuix`)**:
   Upstream v3.4.1 introduced `AppearanceUiStyle` (`COUI` default and `MIUIX`) across all settings and feature screens. Rather than restricting local capabilities to a single UI style, we adapted all local extensions—power-button double-tap wallet hooks, Gemini Vertex AI Service Account JSON import and `Interactions` endpoint mode, model `maxOutputTokens` presets, STDIO MCP server configuration and JSON import, GitHub Skill token and update checks, `WebToolBackend.GEMINI` search grounding, `/` slash skill autocomplete popup, and ColorOS photo-picker hijack bypass (`ChooseMultipleImagesContract` / `ChooseSingleImageContract`)—into both the COUI and Miuix component trees, while keeping Android 16 / ColorOS 16 Fluid Cloud (`EtaLiveUpdateManager`) active alongside the COUI-styled runtime overlay.

3. **Global Per-Tool Toggle Enforcement Across Primary Agent and Subagents (`AgentToolCatalog`, `AgentLocalTools`, `SubagentProfile`) and Zero-Copy Media Branching**:
   Upstream's per-tool enablement state (`Prefs.isToolEnabled(toolName)`) acts as the highest-priority guardrail for both the primary agent catalog and all spawned Subagents (`SubagentProfile`), and disabling `subagent` (`AgentSubagentToolCatalog.TOOL_NAME`) removes subagent delegation at the root. When creating a Conversation Branch from a turn containing local Generated Media Assets (`file://` image URIs), branch messages retain zero-copy references to the immutable media files on disk without duplicating image bytes.

## Considered Options

- **Keep `EtaDatabase` at version 25 and rewrite migrations 22–25 in place**: Rejected because existing local v25 installations would never trigger a Room migration and would crash on missing `is_pinned` and telemetry columns.
- **Maintain local settings and dialogs only in Miuix or only in COUI**: Rejected because runtime switching between COUI and Miuix is a core v3.4.1 invariant; omitting controls in either style would hide or break local configurations when switching styles.
- **Duplicate generated image files on disk when branching a conversation**: Rejected because local Generated Media Assets are immutable files; sharing `file://` URI references across branches is zero-copy and instantaneous.
