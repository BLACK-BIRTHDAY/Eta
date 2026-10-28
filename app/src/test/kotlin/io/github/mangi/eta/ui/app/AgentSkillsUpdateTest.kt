package io.github.mangi.eta.ui.app

import android.content.Context
import io.github.mangi.eta.agent.skill.PublicGitHubSkillSource
import io.github.mangi.eta.agent.skill.SkillIndexEntry
import io.github.mangi.eta.agent.skill.SkillIndexService
import io.github.mangi.eta.agent.skill.SkillOrigin
import io.github.mangi.eta.agent.skill.SkillOriginStore
import io.github.mangi.eta.agent.skill.SkillPackageInstaller
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.ui.model.SkillItemUi
import io.github.mangi.eta.ui.model.SkillUpdateCandidateUi
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentSkillsUpdateTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var context: Context
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)

    private val cacheRoot: File
        get() = File(temporaryFolder.root, "cache").apply { mkdirs() }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
    }

    @After
    fun tearDown() {
        scope.cancel()
        EtaDatabase.closeForTests()
    }

    @Test
    fun manualUpdateCheckDetectsRemoteTreeShaChange() = runBlocking {
        val oldCommit = "1".repeat(40)
        val oldTree = "2".repeat(40)
        val newCommit = "3".repeat(40)
        val newTree = "4".repeat(40)
        val rootTree = "5".repeat(40)

        val skillsRoot = temporaryFolder.newFolder("skills")
        val skillDir = File(skillsRoot, "my-skill").apply { mkdirs() }
        val skillFile = File(skillDir, "SKILL.md").apply {
            writeText("---\nname: my-skill\ndescription: Test skill\n---\nBody")
        }
        val origin = SkillOrigin(
            repository = "owner/skills-repo",
            trackedRef = "main",
            skillPath = "skills/my-skill",
            commitSha = oldCommit,
            treeSha = oldTree,
            skillMdSha256 = SkillOriginStore.computeSkillMdSha256(skillDir).orEmpty(),
        )
        SkillOriginStore.write(skillDir, origin)

        val entry = SkillIndexEntry(
            id = "my-skill",
            name = "my-skill",
            description = "Test skill",
            rootPath = skillDir.absolutePath,
            skillFilePath = skillFile.absolutePath,
            hasScripts = false,
            hasReferences = false,
            hasAssets = false,
            hasEvals = false,
            enabled = true,
            source = "user",
            installed = true,
            origin = origin,
            isLocallyModified = false,
        )

        var inspectRequests = 0
        val client = fakeClient { request ->
            when {
                request.url.encodedPath.endsWith("/commits/main") -> {
                    inspectRequests++
                    jsonResponse(request, commitJson(newCommit, rootTree))
                }
                request.url.encodedPath.contains("/git/trees/$rootTree") -> jsonResponse(
                    request,
                    treeJson(
                        treeEntry("skills/my-skill", type = "tree", sha = newTree),
                        treeEntry("skills/my-skill/SKILL.md", type = "blob", sha = "6".repeat(40)),
                    ),
                )
                else -> error("Unexpected URL ${request.url}")
            }
        }

        val gateway = CoreSkillUpdateGateway(
            context = context,
            gitHubSourceFactory = { PublicGitHubSkillSource(cacheRoot, client) },
            skillsRoot = skillsRoot,
        )

        val outcome = gateway.checkUpdates(listOf(entry))
        if (outcome is SkillUpdateCheckOutcome.Failure) {
            throw AssertionError("checkUpdates failed: ${outcome.message}", outcome.cause)
        }
        assertTrue(outcome is SkillUpdateCheckOutcome.Success)
        val success = outcome as SkillUpdateCheckOutcome.Success
        assertEquals(1, success.checkedCount)
        assertEquals(1, success.candidates.size)

        val candidate = success.candidates.single()
        assertEquals("my-skill", candidate.skillId)
        assertEquals("owner/skills-repo", candidate.repository)
        assertEquals("skills/my-skill", candidate.skillPath)
        assertEquals(oldCommit, candidate.currentCommitSha)
        assertEquals(newCommit, candidate.remoteCommitSha)
        assertEquals(newTree, candidate.remoteTreeSha)
        assertFalse(candidate.isLocallyModified)
        assertEquals(1, inspectRequests)
    }

    @Test
    fun manualUpdateCheckSurfacesIsLocallyModifiedWhenSkillMdWasEditedLocally() = runBlocking {
        val skillsRoot = temporaryFolder.newFolder("skills")
        val skillDir = File(skillsRoot, "modified-skill").apply { mkdirs() }
        val skillFile = File(skillDir, "SKILL.md").apply {
            writeText("---\nname: modified-skill\ndescription: Original\n---\nOriginal body")
        }
        val originalSha = SkillOriginStore.computeSkillMdSha256(skillDir).orEmpty()
        val origin = SkillOrigin(
            repository = "owner/repo",
            trackedRef = "main",
            skillPath = "skills/modified-skill",
            commitSha = "1".repeat(40),
            treeSha = "2".repeat(40),
            skillMdSha256 = originalSha,
        )
        SkillOriginStore.write(skillDir, origin)

        // Modify SKILL.md locally
        skillFile.writeText("---\nname: modified-skill\ndescription: Edited by user\n---\nEdited body")
        assertTrue(SkillOriginStore.isLocallyModified(skillDir, origin))

        val entry = SkillIndexEntry(
            id = "modified-skill",
            name = "modified-skill",
            description = "Edited by user",
            rootPath = skillDir.absolutePath,
            skillFilePath = skillFile.absolutePath,
            hasScripts = false,
            hasReferences = false,
            hasAssets = false,
            hasEvals = false,
            enabled = true,
            source = "user",
            installed = true,
            origin = origin,
            isLocallyModified = true,
        )

        val newCommit = "3".repeat(40)
        val newTree = "4".repeat(40)
        val rootTree = "5".repeat(40)

        val client = fakeClient { request ->
            when {
                request.url.encodedPath.endsWith("/commits/main") -> jsonResponse(request, commitJson(newCommit, rootTree))
                request.url.encodedPath.contains("/git/trees/$rootTree") -> jsonResponse(
                    request,
                    treeJson(
                        treeEntry("skills/modified-skill", type = "tree", sha = newTree),
                        treeEntry("skills/modified-skill/SKILL.md", type = "blob", sha = "6".repeat(40)),
                    ),
                )
                else -> error("Unexpected URL ${request.url}")
            }
        }

        val gateway = CoreSkillUpdateGateway(
            context = context,
            gitHubSourceFactory = { PublicGitHubSkillSource(cacheRoot, client) },
            skillsRoot = skillsRoot,
        )

        val outcome = gateway.checkUpdates(listOf(entry))
        if (outcome is SkillUpdateCheckOutcome.Failure) {
            throw AssertionError("checkUpdates failed: ${outcome.message}", outcome.cause)
        }
        val candidate = (outcome as SkillUpdateCheckOutcome.Success).candidates.single()
        assertTrue(candidate.isLocallyModified)
    }

    @Test
    fun updateCheckDoesNotRunAutomaticallyOnStoreCreationOrRefresh() {
        var checkCalls = 0
        val trackingGateway = object : SkillUpdateGateway {
            override suspend fun checkUpdates(entries: List<SkillIndexEntry>): SkillUpdateCheckOutcome {
                checkCalls++
                return SkillUpdateCheckOutcome.Success(emptyList(), entries.size)
            }

            override suspend fun applyUpdates(updates: List<SkillUpdateCandidateUi>): SkillUpdateApplyOutcome {
                return SkillUpdateApplyOutcome.Success(emptyList())
            }
        }

        val skillsRoot = temporaryFolder.newFolder("skills")
        val skillDir = File(skillsRoot, "user-skill").apply { mkdirs() }
        File(skillDir, "SKILL.md").writeText("---\nname: user-skill\ndescription: desc\n---\nbody")
        SkillOriginStore.write(
            skillDir,
            SkillOrigin("repo/slug", "main", "skills/user-skill", "1".repeat(40), "2".repeat(40), "3".repeat(64)),
        )
        val indexService = SkillIndexService(context, skillsRoot)

        val store = AgentSkillsStore(
            context = context,
            scope = scope,
            skillUpdateGateway = trackingGateway,
            skillIndexServiceProvider = { indexService },
        )

        // Initial state before refresh
        assertFalse(store.skillsState.isCheckingUpdates)
        assertFalse(store.skillsState.isUpdatingSkills)
        assertTrue(store.skillsState.pendingUpdates.isEmpty())
        assertEquals(0, checkCalls)

        // Trigger refreshSkills
        store.refreshSkills()
        awaitCondition { !store.skillsState.isLoading }

        // Must still NOT run update check
        assertEquals(0, checkCalls)
        assertFalse(store.skillsState.isCheckingUpdates)
        assertTrue(store.skillsState.pendingUpdates.isEmpty())

        // SkillItemUi properly mapped canCheckUpdate and originRepository
        val item = store.skillsState.skills.first { it.id == "user-skill" }
        assertTrue(item.canCheckUpdate)
        assertEquals("repo/slug", item.originRepository)
    }

    @Test
    fun groupsMultipleSkillsFromSameRepoAndBranchIntoSingleInspectCall() = runBlocking {
        val rootTree = "1".repeat(40)
        val commitSha = "2".repeat(40)
        val tree1 = "3".repeat(40)
        val tree2 = "4".repeat(40)
        val tree1New = "a".repeat(40)
        val tree2New = "b".repeat(40)

        val origin1 = SkillOrigin("org/mono", "main", "skills/one", commitSha, tree1, "c".repeat(64))
        val origin2 = SkillOrigin("org/mono", "main", "skills/two", commitSha, tree2, "d".repeat(64))
        val entry1 = createTestEntry("one", origin1)
        val entry2 = createTestEntry("two", origin2)

        var inspectCommitCalls = 0
        val client = fakeClient { request ->
            when {
                request.url.encodedPath.endsWith("/commits/main") -> {
                    inspectCommitCalls++
                    jsonResponse(request, commitJson(commitSha, rootTree))
                }
                request.url.encodedPath.contains("/git/trees/$rootTree") -> jsonResponse(
                    request,
                    treeJson(
                        treeEntry("skills/one", type = "tree", sha = tree1New),
                        treeEntry("skills/one/SKILL.md", type = "blob", sha = "e".repeat(40)),
                        treeEntry("skills/two", type = "tree", sha = tree2New),
                        treeEntry("skills/two/SKILL.md", type = "blob", sha = "f".repeat(40)),
                    ),
                )
                else -> error("Unexpected URL ${request.url}")
            }
        }

        val gateway = CoreSkillUpdateGateway(
            context = context,
            gitHubSourceFactory = { PublicGitHubSkillSource(cacheRoot, client) },
            skillsRoot = temporaryFolder.root,
        )

        val outcome = gateway.checkUpdates(listOf(entry1, entry2))
        if (outcome is SkillUpdateCheckOutcome.Failure) {
            throw AssertionError("checkUpdates failed: ${outcome.message}", outcome.cause)
        }
        val success = outcome as SkillUpdateCheckOutcome.Success
        assertEquals(1, inspectCommitCalls)
        assertEquals(2, success.candidates.size)
        assertEquals(2, success.checkedCount)
    }

    @Test
    fun skipsCandidateWhenTreeShaIsUnchangedEvenIfCommitChanged() = runBlocking {
        val sameTreeSha = "1".repeat(40)
        val rootTree = "2".repeat(40)
        val oldCommit = "3".repeat(40)
        val newCommit = "4".repeat(40)

        val origin = SkillOrigin("org/repo", "main", "skills/stable", oldCommit, sameTreeSha, "5".repeat(64))
        val entry = createTestEntry("stable", origin)

        val client = fakeClient { request ->
            when {
                request.url.encodedPath.endsWith("/commits/main") -> jsonResponse(
                    request,
                    commitJson(newCommit, rootTree),
                )
                request.url.encodedPath.contains("/git/trees/$rootTree") -> jsonResponse(
                    request,
                    treeJson(
                        treeEntry("skills/stable", type = "tree", sha = sameTreeSha),
                        treeEntry("skills/stable/SKILL.md", type = "blob", sha = "6".repeat(40)),
                    ),
                )
                else -> error("Unexpected URL ${request.url}")
            }
        }

        val gateway = CoreSkillUpdateGateway(
            context = context,
            gitHubSourceFactory = { PublicGitHubSkillSource(cacheRoot, client) },
            skillsRoot = temporaryFolder.root,
        )

        val outcome = gateway.checkUpdates(listOf(entry))
        if (outcome is SkillUpdateCheckOutcome.Failure) {
            throw AssertionError("checkUpdates failed: ${outcome.message}", outcome.cause)
        }
        val success = outcome as SkillUpdateCheckOutcome.Success
        assertTrue("No updates when treeSha is identical", success.candidates.isEmpty())
        assertEquals(1, success.checkedCount)
    }

    @Test
    fun confirmingUpdatesInstallsNewSkillVersionAndUpdatesOriginJson() = runBlocking {
        val skillsRoot = temporaryFolder.newFolder("skills")
        val skillDir = File(skillsRoot, "updatable-skill").apply { mkdirs() }
        File(skillDir, "SKILL.md").writeText("---\nname: updatable-skill\ndescription: v1\n---\nOld content")
        val oldSha = SkillOriginStore.computeSkillMdSha256(skillDir).orEmpty()

        val oldCommit = "1".repeat(40)
        val oldTree = "2".repeat(40)
        val newCommit = "3".repeat(40)
        val newTree = "4".repeat(40)
        val rootTree = "5".repeat(40)
        val blobSha = "6".repeat(40)

        SkillOriginStore.write(
            skillDir,
            SkillOrigin(
                repository = "owner/repo",
                trackedRef = "main",
                skillPath = "skills/updatable-skill",
                commitSha = oldCommit,
                treeSha = oldTree,
                skillMdSha256 = oldSha,
            ),
        )

        val newContent = "---\nname: updatable-skill\ndescription: v2 updated\n---\nNew content v2"

        val client = fakeClient { request ->
            when {
                request.url.encodedPath.endsWith("/commits/main") -> jsonResponse(request, commitJson(newCommit, rootTree))
                request.url.encodedPath.contains("/git/trees/$rootTree") -> jsonResponse(
                    request,
                    treeJson(
                        treeEntry("skills/updatable-skill", type = "tree", sha = newTree),
                        treeEntry("skills/updatable-skill/SKILL.md", type = "blob", sha = blobSha),
                    ),
                )
                request.url.host == "raw.githubusercontent.com" -> response(
                    request,
                    newContent.toByteArray().toResponseBody("text/plain".toMediaType()),
                )
                else -> error("Unexpected URL ${request.url}")
            }
        }

        val indexService = SkillIndexService(context, skillsRoot)
        indexService.registerInstalledUserSkills(listOf("updatable-skill"))

        val gateway = CoreSkillUpdateGateway(
            context = context,
            gitHubSourceFactory = { PublicGitHubSkillSource(cacheRoot, client) },
            installerFactory = {
                SkillPackageInstaller(
                    skillsRoot = skillsRoot,
                    indexService = indexService,
                )
            },
            skillsRoot = skillsRoot,
        )

        val candidate = SkillUpdateCandidateUi(
            skillId = "updatable-skill",
            name = "updatable-skill",
            repository = "owner/repo",
            trackedRef = "main",
            skillPath = "skills/updatable-skill",
            currentCommitSha = oldCommit,
            remoteCommitSha = newCommit,
            remoteTreeSha = newTree,
            isLocallyModified = false,
        )

        val outcome = gateway.applyUpdates(listOf(candidate))
        if (outcome is SkillUpdateApplyOutcome.Failure) {
            throw AssertionError("applyUpdates failed: ${outcome.message}", outcome.cause)
        }
        assertTrue(outcome is SkillUpdateApplyOutcome.Success)
        val success = outcome as SkillUpdateApplyOutcome.Success
        assertEquals(listOf("updatable-skill"), success.updatedSkillNames)

        // Verify updated file on disk
        val updatedSkillMd = File(skillDir, "SKILL.md").readText()
        assertTrue(updatedSkillMd.contains("v2 updated"))

        // Verify origin metadata was updated
        val updatedOrigin = SkillOriginStore.read(skillDir)
        assertNotNull(updatedOrigin)
        assertEquals("owner/repo", updatedOrigin!!.repository)
        assertEquals("main", updatedOrigin.trackedRef)
        assertEquals("skills/updatable-skill", updatedOrigin.skillPath)
        assertEquals(newCommit, updatedOrigin.commitSha)
        assertEquals(newTree, updatedOrigin.treeSha)
        assertFalse(SkillOriginStore.isLocallyModified(skillDir, updatedOrigin))
        assertEquals(SkillOriginStore.computeSkillMdSha256(skillDir), updatedOrigin.skillMdSha256)
    }

    @Test
    fun storeCheckSkillUpdatesFlowAndConfirmUpdatesFlow() {
        val candidate = SkillUpdateCandidateUi(
            skillId = "flow-skill",
            name = "flow-skill",
            repository = "owner/repo",
            trackedRef = "main",
            skillPath = "skills/flow-skill",
            currentCommitSha = "1".repeat(40),
            remoteCommitSha = "2".repeat(40),
            remoteTreeSha = "3".repeat(40),
            isLocallyModified = false,
        )

        var checkInvoked = false
        var applyInvoked = false

        val testGateway = object : SkillUpdateGateway {
            override suspend fun checkUpdates(entries: List<SkillIndexEntry>): SkillUpdateCheckOutcome {
                checkInvoked = true
                return SkillUpdateCheckOutcome.Success(listOf(candidate), entries.size)
            }

            override suspend fun applyUpdates(updates: List<SkillUpdateCandidateUi>): SkillUpdateApplyOutcome {
                applyInvoked = true
                return SkillUpdateApplyOutcome.Success(updates.map { it.name })
            }
        }

        val skillsRoot = temporaryFolder.newFolder("skills")
        val skillDir = File(skillsRoot, "flow-skill").apply { mkdirs() }
        File(skillDir, "SKILL.md").writeText("---\nname: flow-skill\ndescription: flow desc\n---\nbody")
        SkillOriginStore.write(
            skillDir,
            SkillOrigin("owner/repo", "main", "skills/flow-skill", "1".repeat(40), "2".repeat(40), "3".repeat(64)),
        )
        val indexService = SkillIndexService(context, skillsRoot)

        val store = AgentSkillsStore(
            context = context,
            scope = scope,
            skillUpdateGateway = testGateway,
            skillIndexServiceProvider = { indexService },
        )

        // 1. Check updates manually
        store.checkSkillUpdates()
        awaitCondition { !store.skillsState.isCheckingUpdates }

        assertTrue(checkInvoked)
        assertEquals(listOf(candidate), store.skillsState.pendingUpdates)

        // 2. Confirm updates
        store.confirmSkillUpdates()
        awaitCondition { !store.skillsState.isUpdatingSkills }

        assertTrue(applyInvoked)
        assertTrue(store.skillsState.pendingUpdates.isEmpty())
        assertNotNull(store.skillsState.notice)
        assertEquals("技能更新完成", store.skillsState.notice!!.title)
        assertTrue(store.skillsState.notice!!.message.contains("flow-skill"))
    }

    @Test
    fun storeCancelSkillUpdatesClearsPendingList() {
        val candidate = SkillUpdateCandidateUi(
            skillId = "cancel-skill",
            name = "cancel-skill",
            repository = "owner/repo",
            trackedRef = "main",
            skillPath = "skills/cancel-skill",
            currentCommitSha = "1".repeat(40),
            remoteCommitSha = "2".repeat(40),
            remoteTreeSha = "3".repeat(40),
            isLocallyModified = false,
        )

        val testGateway = object : SkillUpdateGateway {
            override suspend fun checkUpdates(entries: List<SkillIndexEntry>): SkillUpdateCheckOutcome {
                return SkillUpdateCheckOutcome.Success(listOf(candidate), entries.size)
            }

            override suspend fun applyUpdates(updates: List<SkillUpdateCandidateUi>): SkillUpdateApplyOutcome {
                return SkillUpdateApplyOutcome.Success(emptyList())
            }
        }

        val skillsRoot = temporaryFolder.newFolder("skills")
        val skillDir = File(skillsRoot, "cancel-skill").apply { mkdirs() }
        File(skillDir, "SKILL.md").writeText("---\nname: cancel-skill\ndescription: cancel\n---\nbody")
        SkillOriginStore.write(
            skillDir,
            SkillOrigin("owner/repo", "main", "skills/cancel-skill", "1".repeat(40), "2".repeat(40), "3".repeat(64)),
        )
        val indexService = SkillIndexService(context, skillsRoot)

        val store = AgentSkillsStore(
            context = context,
            scope = scope,
            skillUpdateGateway = testGateway,
            skillIndexServiceProvider = { indexService },
        )

        store.checkSkillUpdates()
        awaitCondition { store.skillsState.pendingUpdates.isNotEmpty() }

        store.cancelSkillUpdates()
        assertTrue(store.skillsState.pendingUpdates.isEmpty())
    }

    @Test
    fun storeShowsNoticeWhenNoGitHubSkillsToUpdate() {
        val skillsRoot = temporaryFolder.newFolder("skills")
        val skillDir = File(skillsRoot, "local-zip-skill").apply { mkdirs() }
        File(skillDir, "SKILL.md").writeText("---\nname: local-zip-skill\ndescription: local\n---\nbody")
        // No origin file written -> origin is null

        val indexService = SkillIndexService(context, skillsRoot)

        val store = AgentSkillsStore(
            context = context,
            scope = scope,
            skillIndexServiceProvider = { indexService },
        )

        store.checkSkillUpdates()
        awaitCondition { !store.skillsState.isCheckingUpdates }

        assertTrue(store.skillsState.pendingUpdates.isEmpty())
        assertNotNull(store.skillsState.notice)
        assertEquals("暂无可检查更新的技能", store.skillsState.notice!!.title)
    }

    @Test
    fun storeShowsNoticeWhenAllSkillsAreUpToDate() {
        val testGateway = object : SkillUpdateGateway {
            override suspend fun checkUpdates(entries: List<SkillIndexEntry>): SkillUpdateCheckOutcome {
                return SkillUpdateCheckOutcome.Success(emptyList(), entries.size)
            }

            override suspend fun applyUpdates(updates: List<SkillUpdateCandidateUi>): SkillUpdateApplyOutcome {
                return SkillUpdateApplyOutcome.Success(emptyList())
            }
        }

        val skillsRoot = temporaryFolder.newFolder("skills")
        val skillDir = File(skillsRoot, "up-to-date").apply { mkdirs() }
        File(skillDir, "SKILL.md").writeText("---\nname: up-to-date\ndescription: up to date\n---\nbody")
        SkillOriginStore.write(
            skillDir,
            SkillOrigin("owner/repo", "main", "skills/up-to-date", "1".repeat(40), "2".repeat(40), "3".repeat(64)),
        )
        val indexService = SkillIndexService(context, skillsRoot)

        val store = AgentSkillsStore(
            context = context,
            scope = scope,
            skillUpdateGateway = testGateway,
            skillIndexServiceProvider = { indexService },
        )

        store.checkSkillUpdates()
        awaitCondition { !store.skillsState.isCheckingUpdates }

        assertTrue(store.skillsState.pendingUpdates.isEmpty())
        assertNotNull(store.skillsState.notice)
        assertEquals("已是最新版本", store.skillsState.notice!!.title)
        assertTrue(store.skillsState.notice!!.message.contains("1 个 GitHub 技能"))
    }

    private fun createTestEntry(id: String, origin: SkillOrigin): SkillIndexEntry {
        val skillDir = File(temporaryFolder.root, id).apply { mkdirs() }
        val skillFile = File(skillDir, "SKILL.md").apply {
            if (!exists()) writeText("---\nname: $id\ndescription: test $id\n---\nbody")
        }
        return SkillIndexEntry(
            id = id,
            name = id,
            description = "test $id",
            rootPath = skillDir.absolutePath,
            skillFilePath = skillFile.absolutePath,
            hasScripts = false,
            hasReferences = false,
            hasAssets = false,
            hasEvals = false,
            enabled = true,
            source = "user",
            installed = true,
            origin = origin,
        )
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper(10, TimeUnit.MILLISECONDS)
            Thread.sleep(1)
        }
        assertTrue("异步状态未在限定时间内完成", condition())
    }

    private fun fakeClient(handler: (Request) -> Response): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain -> handler(chain.request()) }
            .build()

    private fun commitJson(sha: String, treeSha: String): JSONObject = JSONObject()
        .put("sha", sha)
        .put(
            "commit",
            JSONObject().put("tree", JSONObject().put("sha", treeSha)),
        )

    private fun treeEntry(path: String, type: String = "blob", sha: String? = null): JSONObject = JSONObject()
        .put("type", type)
        .put("path", path)
        .apply {
            if (sha != null) put("sha", sha)
        }

    private fun treeJson(vararg entries: JSONObject): JSONObject = JSONObject()
        .put("truncated", false)
        .put("tree", JSONArray().apply { entries.forEach { put(it) } })

    private fun jsonResponse(request: Request, body: JSONObject): Response = response(
        request,
        body.toString().toResponseBody("application/json".toMediaType()),
    )

    private fun response(request: Request, body: okhttp3.ResponseBody): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body)
            .build()
}
