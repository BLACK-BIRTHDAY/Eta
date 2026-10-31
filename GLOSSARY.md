# Eta

Eta is a system-level Android AI Agent supporting Xposed and Root capabilities, multi-provider LLM orchestration, and extensible Agent Skills.

## Language

### Skills

**Builtin Skill**:
A pre-packaged skill bundled with the application. Builtin skills can be locally edited (overridden) and restored to their default bundled content, or removed and reinstalled, but never overwritten by external installation.
_Avoid_: System skill, default skill

**User Skill**:
A skill installed by the user or the agent after app installation, either imported from a local archive or fetched from a public GitHub repository.
_Avoid_: Custom skill, external skill, third-party skill

**Skill Origin**:
The recorded provenance of an installed User Skill (such as its GitHub repository, tracked branch or ref, relative skill path, and pinned commit and subtree hashes) used for manual update checks.
_Avoid_: Skill lock, skill source URL

**Skill Subtree Archive**:
A synthesized, lightweight archive containing only the files under the selected skill directory path(s) fetched from a GitHub commit tree, rather than the entire repository archive.
_Avoid_: Partial zipball, sparse clone

### Subagents

**Subagent**:
An isolated child agent execution spawned by the primary agent to handle an autonomous subtask (such as multi-source exploration, code and terminal tasks, or architecture planning) and return a structured summary without polluting the primary conversation context.
_Avoid_: Delegate task, worker agent

**Subagent Profile**:
A role and tool-allowlist specification for a Subagent, either pre-packaged, saved as a reusable template by the user or agent, or synthesized on the fly as an ephemeral role for a single turn.
_Avoid_: Subagent template, worker profile

**Subagent Session**:
An in-memory conversation transcript cached within the active conversation so the primary agent can resume an earlier Subagent with preserved context.
_Avoid_: Subagent thread

**Subagent Step Guardrail**:
A per-invocation upper bound on tool calls inside a Subagent run that prevents runaway loops and instructs the Subagent to synthesize its collected findings when reached, preserving partial outputs on interruption.
_Avoid_: Hard abort limit

### Terminal Environments

**Android Host Environment**:
The native Android userland execution environment used for Android system administration and app-private storage inspection, without a general-purpose Linux development toolchain.
_Avoid_: System shell, host container

**Linux Tool Environment**:
An isolated Linux userland distribution that hosts engineering, scripting, and package toolchains while sharing host workspace and configured shared directories via bidirectional bind mounts.
_Avoid_: Guest VM, Linux sandbox

**Terminal Execution Context**:
The per-run immutable snapshot of terminal and file-tool execution state declared to the primary agent and subagents, specifying default environment and identity, workspace mappings, shared host mounts, and installed Linux tool profiles.
_Avoid_: Shell session state, runtime environment config

### Virtual Display & Spatial Routing

**Virtual Display Canvas**:
An off-screen, trusted interactive Android display where cross-app GUI agent tasks execute silently in the background without stealing focus or interrupting the user's physical main screen.
_Avoid_: Background PiP window, floating monitor, secondary screen

**Canvas Routing Policy**:
The spatial decision rule that determines whether an app launch or URI navigation executes in-place on the physical main display (when the target app is already in the foreground or is a system surface) or silently inside the Virtual Display Canvas.
_Avoid_: Display selector, screen switcher

**Canvas Materialization**:
The seamless in-place handoff that migrates the active root task from the Virtual Display Canvas back to the physical main display when the agent reaches a payment confirmation, QR code, security-protected window, or final result screen requiring the user's direct attention.
_Avoid_: Screen cast, task relaunch
