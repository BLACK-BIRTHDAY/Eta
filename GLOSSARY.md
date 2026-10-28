# Eta

Eta is a system-level Android AI Agent supporting Xposed/Root capabilities, multi-provider LLM orchestration, and extensible Agent Skills.

## Language

### Skills

**Builtin Skill**:
A pre-packaged skill bundled in the APK assets (`source = "builtin"`). Builtin skills can be locally edited (overridden) and restored to their default bundled content, or removed and reinstalled, but never overwritten by external installation.
_Avoid_: System skill, default skill

**User Skill**:
A skill installed by the user or the agent after app installation (`source = "user"`), either imported from a local ZIP package or fetched from a public GitHub repository.
_Avoid_: Custom skill, external skill, third-party skill

**Skill Origin**:
The recorded provenance of an installed User Skill (such as a GitHub repository slug, tracked branch/ref, relative skill path, and pinned commit/tree SHA) used for manual update checks.
_Avoid_: Skill lock, skill source URL

**Skill Subtree Archive**:
A synthesized, lightweight ZIP archive containing only the files under the selected skill directory path(s) fetched from a GitHub commit tree, rather than the entire repository archive.
_Avoid_: Partial zipball, sparse clone
