# Skill Subtree Archive, Inspection Auto-Healing & Manual Update Plan

## Spec & Global Constraints
- **Repository**: `D:\AI\Xposed\ETA` (Package: `io.github.mangi.eta`)
- **Glossary (`GLOSSARY.md`) & ADR (`docs/adr/0001-github-skill-subtree-and-origin.md`)**:
  1. **Skill Subtree Archive**: GitHub skill installations and updates MUST NOT download full repository ZIP archives from `codeload.github.com` when `selectedPaths` are provided. Instead, `PublicGitHubSkillSource.downloadSkillSubtreeArchive(...)` fetches the commit's recursive Git tree (`/repos/{owner}/{repo}/git/trees/{treeSha}?recursive=1`), filters `blob` entries under the selected skill directory path(s), downloads only those blobs from `https://raw.githubusercontent.com/{owner}/{repo}/{commitSha}/{path}` (with `MAX_ARCHIVE_BYTES = 32 MiB` and `MAX_SINGLE_FILE_BYTES = 32 MiB` safety bounds), and synthesizes a temporary ZIP file with envelope directory `<repository>-<commitSha>/<relative-blob-path>` so `SkillPackageInstaller.installRepositoryZip` works unchanged.
  2. **Skill Origin Sidecar (`.eta-skill-origin.json`)**: When a User Skill is installed from GitHub, write `.eta-skill-origin.json` inside the skill directory (`skills/<skillId>/.eta-skill-origin.json`) containing:
     - `repository`: `"owner/repo"`
     - `trackedRef`: branch/tag name (e.g. `"main"`, never a 40-char commit SHA unless the user explicitly tracked a commit or default branch resolution was used)
     - `skillPath`: normalized relative path in repository (e.g. `"."` or `"skills/demo"`)
     - `commitSha`: 40-char commit SHA installed
     - `treeSha`: 40-char Git tree SHA for the skill directory (or the commit's root `treeSha` when `skillPath == "."`)
     - `skillMdSha256`: lowercase hex SHA-256 of the installed `SKILL.md` file bytes, used to detect whether the user locally edited `SKILL.md` (`isLocallyModified`).
  3. **Inspection Snapshot Auto-Healing & 1-Step Replace in `AgentLocalTools`**:
     - `rememberInspection` always stores the default key (`inspectionKey(repository.slug, null)`) and merges `candidatesByPath` across inspections of the same repository+commitSha instead of overwriting narrower prefixes.
     - In `skillsInstallFromGitHub`, if no snapshot is found in `inspectedGitHubSnapshots` or a selected path is missing from a narrow prefix snapshot, automatically invoke `source.inspect(requestedRepository.copy(path = null), tokenOverride = explicitToken)` to verify the selected path(s) exist in the repository tree rather than failing with `SKILL_INSPECTION_REQUIRED` or `INVALID_SKILL_SELECTION` due to prefix mismatch.
     - In `skillsInstallFromGitHub`, when `replaceExisting == true` and `pendingSkillConflict.get() == null`, allow replacement if the target skill is an already-installed User Skill (`source == "user"`) and the selected path is verified via inspection and `expectedReplacementId` matches the skill id.
     - Persist `SkillOrigin` (`.eta-skill-origin.json`) for every skill installed via `skillsInstallFromGitHub`.
  4. **Manual-Only Update Check & Confirmation UI (`AgentSkillsScreen` & `AgentSkillsStore`)**:
     - NEVER check for updates automatically in the background. Only check when the user explicitly clicks:
       (a) Global "检查技能更新" row in the top install card of `AgentSkillsScreen`, or
       (b) Per-skill "检查更新" item in the `...` dropdown menu of `SkillSwitchRow` (shown only for User Skills that have a GitHub `SkillOrigin`).
     - Update check resolves the latest commit & recursive tree for each skill's `trackedRef` on `repository`, extracts the current remote `treeSha` for `skillPath`, and compares it with `origin.treeSha` (fallback to `commitSha` comparison if `treeSha` is blank).
     - If updates are found, display a confirmation dialog (`EtaWindowDialog`) listing the updatable skill(s), their `repository`, short SHA transition (`oldSha -> newSha`), and if any updatable skill has `SKILL.md` SHA-256 != `origin.skillMdSha256`, a clear warning: `"检测到本地修改，更新将覆盖本地修改的内容"`.
     - Only when the user clicks Confirm (`立即更新`), download the Skill Subtree Archive for each updatable skill, install with `replaceUserSkills = true`, and update `.eta-skill-origin.json`.

---

## Task 1: SkillSubtreeArchive, TreeSHA Discovery & SkillOrigin Sidecar in Core Skill Layer

### Files Owned
- Modify: `app/src/main/kotlin/io/github/mangi/eta/agent/skill/PublicGitHubSkillSource.kt`
- Modify: `app/src/main/kotlin/io/github/mangi/eta/agent/skill/SkillModels.kt`
- Modify: `app/src/main/kotlin/io/github/mangi/eta/agent/skill/SkillRuntime.kt`
- Create: `app/src/main/kotlin/io/github/mangi/eta/agent/skill/SkillOriginStore.kt`
- Modify: `app/src/test/kotlin/io/github/mangi/eta/agent/skill/PublicGitHubSkillSourceTest.kt`
- Modify: `app/src/test/kotlin/io/github/mangi/eta/agent/skill/SkillRuntimeTest.kt`

### Requirements
1. In `PublicGitHubSkillSource.kt`:
   - Add `val treeSha: String = ""` to `GitHubSkillCandidate(val name: String, val path: String, val treeSha: String = "")` and `val defaultBranch: String = ref` to `GitHubSkillInspection`.
   - During `inspect()`, parse both `"tree"` and `"blob"` entries from `tree.optJSONArray("tree")`. Build a map of directory `path -> sha` from entries where `type == "tree"`. For each candidate `SKILL.md` at `root`:
     - If `root == "."`, its `treeSha` is `commit.treeSha`.
     - Otherwise, its `treeSha` is the `"tree"` entry SHA for `root` (or if GitHub omitted the directory entry in a synthetic test fixture, compute a deterministic SHA-1/SHA-256 hex of the blob entries under `root` or fall back to the `SKILL.md` blob `sha` / `commit.treeSha`).
   - Add `fun downloadSkillSubtreeArchive(repository: GitHubSkillRepository, selectedPaths: List<String>, tokenOverride: String? = null): DownloadedGitHubArchive`:
     - Resolve `ref` and `commit = resolveCommit(repository, ref, token)` (checking `COMMIT_SHA_PATTERN` mismatch just like `downloadArchive`).
     - Fetch the recursive tree JSON from `/repos/{owner}/{repo}/git/trees/{commit.treeSha}?recursive=1`.
     - Normalize `selectedPaths` via `GitHubSkillRepositoryParser.normalizeRelativePath`. Require `selectedPaths.isNotEmpty()`.
     - Filter `tree` array for `type == "blob"` entries whose normalized path belongs to any selected path (`selectedPath == "."` matches all blobs; otherwise `blobPath == "$selectedPath/SKILL.md"` or `blobPath.startsWith("$selectedPath/")`).
     - If no blobs match or any selected path has no `SKILL.md` blob, throw `GitHubSkillSourceException("SKILL_NOT_FOUND_IN_TREE", "未在仓库树中找到所选 Skill 文件")`.
     - Create temp zip file in `cacheDirectory` (`eta-skill-github-*.zip`). Write a valid ZIP using `ZipOutputStream` with top-level envelope directory `${repository.repository}-${commit.sha}/` so `SkillPackageInstaller.installRepositoryZip` strips the single envelope directory identically to codeload ZIPs.
     - For each matching blob, fetch its raw bytes from `https://raw.githubusercontent.com/${repository.owner}/${repository.repository}/${commit.sha}/<blobPath>` (using `HttpUrl.Builder().scheme("https").host(RAW_HOST)...`) and stream into the ZIP entry, enforcing `MAX_ARCHIVE_BYTES` (32 MiB total across all downloaded blobs).
     - Also compute each selected path's `treeSha` (same logic as `inspect`) and expose `val treeShasByPath: Map<String, String> = emptyMap()` and `val defaultBranch: String = ref` on `DownloadedGitHubArchive`.
2. Create `SkillOriginStore.kt` in `io.github.mangi.eta.agent.skill`:
   - Define `@Immutable data class SkillOrigin(val repository: String, val trackedRef: String, val skillPath: String, val commitSha: String, val treeSha: String, val skillMdSha256: String)` in `SkillModels.kt`.
   - Add `val origin: SkillOrigin? = null` and `val isLocallyModified: Boolean = false` to `SkillIndexEntry` in `SkillModels.kt`.
   - `SkillOriginStore` reads/writes `.eta-skill-origin.json` inside a skill directory (`File(skillDir, ".eta-skill-origin.json")`), refusing symlinks (`Files.isSymbolicLink`), computing `sha256Hex(File(skillDir, "SKILL.md"))`, and checking `isLocallyModified(skillDir, origin)` by comparing current `SKILL.md` SHA-256 with `origin.skillMdSha256`.
3. In `SkillRuntime.kt` (`SkillIndexService.buildInstalledEntry`):
   - When `source == USER_SOURCE`, read `SkillOriginStore.read(canonicalDir)` and populate `origin` and `isLocallyModified` on `SkillIndexEntry`.
4. Write unit tests in `PublicGitHubSkillSourceTest.kt` and `SkillRuntimeTest.kt` verifying:
   - `downloadSkillSubtreeArchive` fetches only blobs under `selectedPaths` from `raw.githubusercontent.com` and never calls `codeload.github.com`.
   - `SkillOriginStore` writes/reads `.eta-skill-origin.json` and `SkillIndexService` surfaces `origin` and `isLocallyModified = true` when `SKILL.md` is edited.

---

## Task 2: Inspection Snapshot Auto-Healing, Subtree Install & Direct Replace in AgentLocalTools

### Files Owned
- Modify: `app/src/main/kotlin/io/github/mangi/eta/agent/tool/AgentLocalTools.kt`
- Modify: `app/src/main/assets/builtin_skills/skill-installer/SKILL.md`
- Modify: `app/src/test/kotlin/io/github/mangi/eta/agent/tool/AgentLocalSkillInstallAuthorizationTest.kt`
- Modify: `app/src/test/kotlin/io/github/mangi/eta/agent/tool/AgentLocalSkillInstallIntegrationTest.kt`

### Requirements
1. In `AgentLocalTools.kt`:
   - In `GitHubInspectionSnapshot`, store `val defaultBranch: String`, `val commitSha: String`, `val prefix: String?`, `val candidatesByPath: Map<String, GitHubSkillCandidate>`.
   - In `rememberInspection`:
     - Always register `inspectionKey(repository.slug, null)` in addition to `repository.ref`, `inspection.ref`, and `inspection.commitSha`, merging `candidatesByPath` with any existing snapshot for the same `commitSha` so inspecting a subdirectory URL (`tree/main/skills/a`) does not erase or block other paths or default-ref lookups.
   - In `skillsInstallFromGitHub`:
     - When `replaceExisting == false`:
       - Look up snapshot in `inspectedGitHubSnapshots`. If missing, OR if any `selectedPaths` is not in `snapshot.candidatesByPath` or outside `snapshot.prefix`:
         - If `inspectedGitHubSnapshots` has NO entry at all for `requestedRepository.slug` (meaning the model never called `skills_inspect_github` or `skills_list_curated` for this repo in this turn), keep returning `SKILL_INSPECTION_REQUIRED` so the authorization gate (`installationRequiresInspectionInTheSameExecutor`) remains intact!
         - However, if the model DID inspect `requestedRepository.slug` in this turn (under any ref or narrower subpath prefix) and `selectedPaths` wasn't in the narrow prefix or the ref key differed (e.g. `ref=null` vs `ref="main"`), auto-inspect `source.inspect(requestedRepository.copy(path = null), tokenOverride = explicitToken)`, remember the merged snapshot, and validate `selectedPaths` against the full tree! If still not in `candidatesByPath`, return `INVALID_SKILL_SELECTION`.
     - When `replaceExisting == true`:
       - If `pendingSkillConflict.get() != null`, validate with `validateReplacementReplay` as before.
       - If `pendingSkillConflict.get() == null`, check if `requestedRepository.slug` was inspected in this turn (`findRepoSnapshots(requestedRepository.slug).isNotEmpty()`): if so, verify `selectedPaths.size == 1`, verify the selected path is in the snapshot (auto-inspecting full repo tree if needed), verify `expectedReplacementId` is non-blank and matches an existing installed User Skill (`source == "user"`) in `skillIndexService`, and allow direct 1-step replacement using the snapshot's `commitSha`! (If not inspected and no pending conflict, return `SKILL_REPLACE_CAPABILITY_REQUIRED`).
     - Use `source.downloadSkillSubtreeArchive(repository, selectedPaths, tokenOverride = explicitToken)` instead of `source.downloadArchive(...)`.
     - Upon `SkillInstallResult.Success`, write `SkillOrigin` via `SkillOriginStore.write(...)` for each installed skill (mapping `selectedPaths` to installed skills via candidate metadata, recording `trackedRef` = original branch ref from inspection or `archive.defaultBranch`, `commitSha = archive.commitSha`, `treeSha = archive.treeShasByPath[path]`).
2. Update `app/src/main/assets/builtin_skills/skill-installer/SKILL.md` to note that an already-installed User Skill (`installed: true` in inspection result) can be updated directly with `replaceExisting=true` and `expectedReplacementId`.
3. Update `AgentLocalSkillInstallIntegrationTest.kt` and `AgentLocalSkillInstallAuthorizationTest.kt`:
   - Update the mock HTTP client in `AgentLocalSkillInstallIntegrationTest` to serve `/git/trees/$TREE_SHA?recursive=1` and `raw.githubusercontent.com` blobs (while keeping `codeload.github.com` fallback if any test calls `downloadArchive` directly).
   - Add tests for:
     - Inspecting a tree URL with subpath (`https://github.com/example/skills/tree/main/skills/demo`) and then calling `skills_install_from_github` with `repository = "example/skills"` (omitting `ref` or using another path in the repo) succeeds without `INVALID_SKILL_SELECTION`.
     - Direct 1-step update (`replaceExisting = true`, `expectedReplacementId = "demo-skill"`) right after `skills_inspect_github` when `"demo-skill"` is already installed as a user skill.
     - `.eta-skill-origin.json` is written upon GitHub skill installation.

---

## Task 3: Manual Update Check & Confirmation Dialog in Skills UI & Store

### Files Owned
- Create: `app/src/main/kotlin/io/github/mangi/eta/ui/app/SkillUpdateGateway.kt`
- Modify: `app/src/main/kotlin/io/github/mangi/eta/ui/model/AgentSkillsUiState.kt`
- Modify: `app/src/main/kotlin/io/github/mangi/eta/ui/model/AgentUiActions.kt`
- Modify: `app/src/main/kotlin/io/github/mangi/eta/ui/app/AgentSkillsStore.kt`
- Modify: `app/src/main/kotlin/io/github/mangi/eta/ui/app/AgentAppRoot.kt`
- Modify: `app/src/main/kotlin/io/github/mangi/eta/ui/screens/skills/AgentSkillsScreen.kt`
- Modify: `app/src/main/kotlin/io/github/mangi/eta/ui/screens/skills/SkillSwitchRow.kt`
- Create: `app/src/test/kotlin/io/github/mangi/eta/ui/app/AgentSkillsUpdateTest.kt`

### Requirements
1. In `AgentSkillsUiState.kt`:
   - Add `val isCheckingUpdates: Boolean = false`, `val isUpdatingSkills: Boolean = false`, `val pendingUpdates: List<SkillUpdateCandidateUi> = emptyList()` to `AgentSkillsUiState`.
   - Define `@Immutable data class SkillUpdateCandidateUi(val skillId: String, val name: String, val repository: String, val trackedRef: String, val skillPath: String, val currentCommitSha: String, val remoteCommitSha: String, val remoteTreeSha: String, val isLocallyModified: Boolean)`.
   - Add `val canCheckUpdate: Boolean = false` and `val originRepository: String? = null` to `SkillItemUi`.
2. In `AgentUiActions.kt` (`AgentSkillsAction`):
   - Add `data class CheckSkillUpdates(val skillId: String? = null) : AgentSkillsAction` (`null` = check all GitHub User Skills; non-null = check single skill).
   - Add `data object ConfirmSkillUpdates : AgentSkillsAction`.
   - Add `data object CancelSkillUpdates : AgentSkillsAction`.
3. Create `SkillUpdateGateway.kt`:
   - Interface `SkillUpdateGateway` with:
     - `suspend fun checkUpdates(entries: List<SkillIndexEntry>): SkillUpdateCheckOutcome`
     - `suspend fun applyUpdates(updates: List<SkillUpdateCandidateUi>): SkillUpdateApplyOutcome`
   - `CoreSkillUpdateGateway(context: Context)` implementation using `PublicGitHubSkillSource` (with token from `SettingsDataStore.getGithubToken()`), `SkillPackageInstaller`, and `SkillOriginStore`:
     - Groups `entries` (filtered to `installed && source == "user" && origin != null`) by `(origin.repository, origin.trackedRef)` so multiple skills from the same repo+branch only make 1 `inspect()` call!
     - Compares remote candidate `treeSha` (or `commitSha` if `origin.treeSha` is blank) with `origin.treeSha` (or `origin.commitSha`). If different, produces a `SkillUpdateCandidateUi` with `isLocallyModified = entry.isLocallyModified`.
     - In `applyUpdates`, downloads subtree archive via `downloadSkillSubtreeArchive`, installs via `installer.installRepositoryZip(..., replaceUserSkills = true, expectedReplacementIds = ...)`, and updates `SkillOriginStore.write(...)` with the new `commitSha`, `treeSha`, and fresh `SKILL.md` SHA-256.
4. In `AgentSkillsStore.kt` & `AgentAppRoot.kt`:
   - Wire `checkSkillUpdates(skillId: String? = null)`, `confirmSkillUpdates()`, `cancelSkillUpdates()`.
   - Never trigger `checkSkillUpdates` automatically in `init` or `refreshSkills()`.
   - When `checkSkillUpdates` completes:
     - If no skills have GitHub origin: show notice `"暂无可检查更新的技能"` / `"仅从 GitHub 安装的技能支持在线检查更新"`.
     - If all checked skills are up-to-date: show notice `"已是最新版本"` / `"已检查 N 个 GitHub 技能，当前均为最新版本"`.
     - If updates are found: set `pendingUpdates = candidates` to display the confirmation dialog.
   - When `confirmSkillUpdates()` completes: clear `pendingUpdates`, `refreshSkills()`, and show notice `"技能更新完成"` listing updated skill names.
5. In `AgentSkillsScreen.kt` & `SkillSwitchRow.kt`:
   - In `AgentSkillsScreen` top install card (`zip-import-card`), add a row for `"检查技能更新"` with `Icons.Rounded.Refresh` (showing progress indicator when `state.isCheckingUpdates || state.isUpdatingSkills`).
   - In `SkillSwitchRow`, add optional `onCheckUpdate: (() -> Unit)? = null` and when `skill.canCheckUpdate && onCheckUpdate != null`, add a `"检查更新"` item to the `OverlayIconDropdownMenu`.
   - In `AgentSkillsScreen`, when `state.pendingUpdates.isNotEmpty()`, show `EtaWindowDialog`:
     - Title: `"发现技能更新"`
     - Summary: Lists each skill (`name (repository): oldSha7 → newSha7`), and if any candidate has `isLocallyModified == true`, appends `"⚠️ 检测到本地修改，更新将覆盖本地修改的内容"`.
     - Actions: Cancel (`取消` -> `CancelSkillUpdates`) and Confirm (`立即更新` -> `ConfirmSkillUpdates`).
6. Write unit tests in `AgentSkillsUpdateTest.kt` verifying:
   - Manual update check detects remote `treeSha` change, surfaces `isLocallyModified` when `SKILL.md` was edited locally, and does not run automatically on store creation/refresh.
   - Confirming updates installs the new skill version and updates `.eta-skill-origin.json`.
