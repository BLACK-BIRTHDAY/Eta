# 0003. Upstream v3.4.0 Virtual Display Canvas Integration and Legacy ASR Backup Coercion

When integrating upstream official v3.4.0 (trusted background Virtual Display Canvas, spatial `CanvasRoutingPolicy`, `present_to_user` materialization, dual Android/Linux environment prompt boundaries, and removal of `AsrProvider.SYSTEM`) with our local architecture, we made three structural decisions:

1. **Subagent Isolation from Virtual Display & Merged Prompt Boundaries (`SubagentProfile`, `AgentPromptBuilder`)**:
   We kept `present_to_user` in `SubagentProfile.FORBIDDEN_TOOLS` alongside all GUI tools because both the physical main display and the Virtual Display Canvas are singleton runtime surfaces coordinated exclusively by the primary agent, while combining upstream's explicit `environment="linux"` / `environment="android"` bind-mount guidance with our local full-profile `mcp_*` tool inheritance and PEP 668 guidance.

2. **Orthogonal Coexistence of Top Micro-Lens Capsule and ColorOS Fluid Cloud (`AgentOverlayCapsule`, `EtaLiveUpdateManager`, `EtaFluidCloudStateMapper`)**:
   We preserved the separation between the in-process overlay capsule (`AgentOverlayCapsule` with its `18×32.dp` live virtual-display micro-lens and gallery stage card) and the system-level Android 16 / ColorOS 16 Promoted Ongoing notification (`EtaLiveUpdateManager`), extending `EtaFluidCloudStateMapper` to report `present_to_user` and virtual-display tool transitions for lockscreen and background status bar visibility.

3. **Backup Schema v4 Forward/Backward Coercion for Removed `AsrProvider.SYSTEM` (`EtaBackupJsonStreams`)**:
   Upstream v3.4.0 removed `AsrProvider.SYSTEM` and enabled `coerceInputValues = true` in `SettingsDataStore` so legacy preferences fall back to `AsrProvider.QWEN_REALTIME`. We enabled `coerceInputValues = true` in `EtaBackupJsonStreams.json` without bumping `SCHEMA_VERSION = 4` or adding `AGENT_VIRTUAL_DISPLAY_ENABLED` to the backup document, ensuring existing v4 backup archives containing `"asr": "SYSTEM"` deserialize cleanly into `AsrProvider.QWEN_REALTIME` during restore.

## Considered Options

- **Allow Subagents to invoke `present_to_user` or virtual display tools**: Rejected because `activeDisplayId` and `VirtualDisplayController` are singleton per-run GUI resources; concurrent subagents would race on display focus and task migration.
- **Suppress `EtaLiveUpdateManager` whenever the Virtual Display Canvas is active**: Rejected because `EtaLiveUpdateManager` already auto-suppresses when Eta's UI is in the foreground and provides essential lockscreen and notification-shade progress when the user switches across apps or locks the screen.
- **Add `AGENT_VIRTUAL_DISPLAY_ENABLED` to `EtaBackupDocument` or bump backup schema version**: Rejected to keep the backup schema minimal and maintain full compatibility with existing v4 backup files via `coerceInputValues = true`.
