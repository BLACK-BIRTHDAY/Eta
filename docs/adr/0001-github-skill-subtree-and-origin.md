# 0001. GitHub Skill Subtree Fetching and Manual Update Provenance

When installing or updating Agent Skills from public GitHub repositories, full-repository ZIP archives (`codeload.github.com`) fail on large repositories exceeding the 32 MiB limit (e.g., `volcengine/OpenViking`). We decided to synthesize a **Skill Subtree Archive** directly from the GitHub Git Trees API (`/git/trees/{sha}?recursive=1`) and `raw.githubusercontent.com` blob fetches for only the selected skill directory paths, feeding the resulting lightweight ZIP into `SkillPackageInstaller`, and to store a sidecar `.eta-skill-origin.json` inside each GitHub-installed user skill directory to support strictly user-initiated update checks via subtree `treeSha` comparison and local `SKILL.md` SHA-256 modification detection.

## Considered Options

- **Full repository ZIP download (`codeload`) with raised size limit**: Rejected because monorepos can exceed hundreds of megabytes, wasting mobile bandwidth and storage for a few kilobytes of markdown files.
- **Git sparse-checkout via native git binary**: Rejected because Android app sandboxes cannot assume a system `git` binary is installed when terminal/root tools are disabled.
- **Room schema expansion on `skill_registry` for origin metadata**: Rejected in favor of a skill-directory sidecar `.eta-skill-origin.json` because atomic directory backup/rollback (`SkillRecoveryJournal`) and backup/restore (`SkillBackupTransfer`) already move and snapshot the skill directory atomically without requiring database schema migrations.
