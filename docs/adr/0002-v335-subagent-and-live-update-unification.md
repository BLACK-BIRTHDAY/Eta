# 0002. Unified v3.3.5 Subagent Architecture with Step Guardrail and Single-Capsule Live Updates

When integrating upstream official v3.3.5 with local custom enhancements, two areas had overlapping implementations:

1. **Subagent Execution Engine (`subagent` vs `delegate_task`)**:
   We adopted the upstream v3.3.5 `io.github.mangi.eta.agent.subagent` architecture (`subagent` and `subagent_profile` tools, builtin `explore`/`coder`/`plan` profiles, ad-hoc ephemeral roles, `agents/<name>.md` persistent templates, `SubagentSessionStore` `resume` support, `SubagentActivitySnapshot` live timeline nodes + `SubagentActivitySheet`, and `SubagentSettings` secondary model resolution) and retired the local `delegate_task` implementation and `ModelConfig.subagentConfigJson` bloat. At the same time, we grafted our local **Subagent Step Guardrail** (`MAX_TOOL_STEPS = 100` per invocation with graceful summary convergence on limit and `collected_outputs` preservation on interruption) into `io.github.mangi.eta.agent.subagent.SubagentExecutor`, and integrated `SubagentSettings` + custom `agents/<name>.md` profiles into `EtaBackupRepository`.

2. **Android 16 Live Updates (`EtaLiveUpdateManager` vs `AgentExecutionService`)**:
   We kept `EtaLiveUpdateManager` as the sole owner of Android 16 / ColorOS 16 Promoted Ongoing capsule notifications (preserving foreground auto-suppression, ColorOS freeform window launch, and 5-second completion capsule demotion), while keeping `AgentExecutionService` as a non-promoted foreground keep-alive notification to prevent duplicate capsules. We incorporated upstream v3.3.5's background-run status decoupling in `AgentRuntimeService.publishExecutionState()` and the standard `android.requestPromotedOngoing` notification extra into `EtaLiveUpdateManager`.

## Considered Options

- **Keep `delegate_task` alongside `subagent`**: Rejected because exposing two overlapping delegation tools confuses tool selection, splits timeline UI rendering, and bloats `ModelConfig` IPC bundles.
- **Allow GUI tools inside Subagents**: Rejected because physical screen gestures and accessibility overlays are singleton device resources; concurrent Subagents would race on screen focus.
- **Enable `requestPromotedOngoing` on both `AgentExecutionService` and `EtaLiveUpdateManager`**: Rejected because Android 16 / ColorOS 16 renders two simultaneous fluid-cloud capsules and lockscreen cards when two notifications from the same app request promotion.
