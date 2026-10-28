package io.github.mangi.eta.agent.tool

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.skill.PublicGitHubSkillSource
import io.github.mangi.eta.agent.skill.SkillIndexService
import io.github.mangi.eta.core.AgentLogger
import io.github.mangi.eta.data.db.EtaDatabase
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentLocalSkillInstallAuthorizationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Before
    fun setUp() {
        EtaDatabase.closeForTests()
        RuntimeEnvironment.getApplication().deleteDatabase("eta.db")
    }

    @Test
    fun installationRequiresInspectionInTheSameExecutor() {
        val tools = tools()

        val result = tools.execute(installCall(replaceExisting = false))

        assertEquals(
            "SKILL_INSPECTION_REQUIRED",
            JSONObject(result.content).getString("code"),
        )
        tools.close()
    }

    @Test
    fun replacementStillRequiresLinkedPriorConflict() {
        val tools = tools()

        val result = tools.execute(installCall(replaceExisting = true))

        assertEquals(
            "SKILL_REPLACE_CAPABILITY_REQUIRED",
            JSONObject(result.content).getString("code"),
        )
        tools.close()
    }

    @Test
    fun directReplacementWithoutConflictFailsIfSkillNotInstalled() {
        val context = RuntimeEnvironment.getApplication() as Context
        val skillsRoot = temporaryFolder.newFolder("auth-skills")
        val indexService = SkillIndexService(context, skillsRoot)
        val source = PublicGitHubSkillSource(
            cacheRoot = temporaryFolder.newFolder("auth-cache"),
            baseClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val body = if ("/git/trees/" in request.url.encodedPath) {
                        JSONObject()
                            .put("truncated", false)
                            .put("tree", JSONArray().put(JSONObject().put("type", "blob").put("path", "skills/demo/SKILL.md")))
                            .toString()
                            .toResponseBody("application/json".toMediaType())
                    } else {
                        JSONObject()
                            .put("sha", COMMIT_SHA)
                            .put("default_branch", "main")
                            .put("commit", JSONObject().put("tree", JSONObject().put("sha", "2222222222222222222222222222222222222222")))
                            .toString()
                            .toResponseBody("application/json".toMediaType())
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body)
                        .build()
                }
                .build(),
        )
        val tools = AgentLocalTools(
            context = context,
            logger = NoOpLogger,
            githubSkillSource = source,
            skillIndexService = indexService,
        )

        tools.execute(
            AgentModelClient.ToolCall(
                id = "inspect",
                name = "skills_inspect_github",
                argumentsJson = JSONObject().put("repository", "example/skills").put("ref", "main").toString(),
            ),
        )

        val result = tools.execute(
            installCall(
                replaceExisting = true,
                repository = "example/skills",
                ref = "main",
                paths = listOf("skills/demo"),
                expectedReplacementId = "not-installed",
            ),
        )

        assertEquals(
            "SKILL_REPLACE_NOT_ALLOWED",
            JSONObject(result.content).getString("code"),
        )
        tools.close()
    }

    @Test
    fun directReplacementWithoutConflictFailsIfExpectedReplacementIdIsBlank() {
        val context = RuntimeEnvironment.getApplication() as Context
        val skillsRoot = temporaryFolder.newFolder("auth-skills-blank")
        val indexService = SkillIndexService(context, skillsRoot)
        val source = PublicGitHubSkillSource(
            cacheRoot = temporaryFolder.newFolder("auth-cache-blank"),
            baseClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val body = if ("/git/trees/" in request.url.encodedPath) {
                        JSONObject()
                            .put("truncated", false)
                            .put("tree", JSONArray().put(JSONObject().put("type", "blob").put("path", "skills/demo/SKILL.md")))
                            .toString()
                            .toResponseBody("application/json".toMediaType())
                    } else {
                        JSONObject()
                            .put("sha", COMMIT_SHA)
                            .put("default_branch", "main")
                            .put("commit", JSONObject().put("tree", JSONObject().put("sha", "2222222222222222222222222222222222222222")))
                            .toString()
                            .toResponseBody("application/json".toMediaType())
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body)
                        .build()
                }
                .build(),
        )
        val tools = AgentLocalTools(
            context = context,
            logger = NoOpLogger,
            githubSkillSource = source,
            skillIndexService = indexService,
        )

        tools.execute(
            AgentModelClient.ToolCall(
                id = "inspect",
                name = "skills_inspect_github",
                argumentsJson = JSONObject().put("repository", "example/skills").put("ref", "main").toString(),
            ),
        )

        val result = tools.execute(
            installCall(
                replaceExisting = true,
                repository = "example/skills",
                ref = "main",
                paths = listOf("skills/demo"),
                expectedReplacementId = "   ",
            ),
        )

        assertEquals(
            "SKILL_REPLACE_CAPABILITY_REQUIRED",
            JSONObject(result.content).getString("code"),
        )
        tools.close()
    }

    @Test
    fun oneConfirmationCannotAuthorizeReplacingMultiplePaths() {
        val tools = tools()

        val result = tools.execute(
            installCall(
                replaceExisting = true,
                paths = listOf("skills/demo", "skills/other"),
            ),
        )

        assertEquals(
            "SKILL_REPLACE_SCOPE_TOO_BROAD",
            JSONObject(result.content).getString("code"),
        )
        tools.close()
    }

    @Test
    fun replacementMustExactlyReplayLinkedConflictCapability() {
        val tools = tools(
            pending = pendingConflict(),
        )

        val result = tools.execute(
            installCall(
                replaceExisting = true,
                repository = "example/skills",
                ref = COMMIT_SHA,
                paths = listOf("skills/other"),
                expectedReplacementId = "demo",
            ),
        )

        assertEquals(
            "SKILL_REPLACE_CAPABILITY_MISMATCH",
            JSONObject(result.content).getString("code"),
        )
        tools.close()
    }

    @Test
    fun linkedConflictCanBeReplayedWithoutPromptWording() {
        val tools = tools(
            pending = pendingConflict(),
        )

        val result = tools.execute(
            installCall(
                replaceExisting = true,
                repository = "example/skills",
                ref = COMMIT_SHA,
                paths = listOf("skills/demo"),
                expectedReplacementId = "demo",
            ),
        )

        assertEquals(
            "SKILL_INSTALLER_UNAVAILABLE",
            JSONObject(result.content).getString("code"),
        )
        tools.close()
    }

    private fun tools(
        pending: PendingSkillConflictCapability? = null,
    ): AgentLocalTools = AgentLocalTools(
        context = RuntimeEnvironment.getApplication() as Context,
        logger = NoOpLogger,
        pendingSkillConflict = pending,
    )

    private fun installCall(
        replaceExisting: Boolean,
        paths: List<String> = listOf("skills/.curated/openai-docs"),
        repository: String = "openai/skills",
        ref: String? = null,
        expectedReplacementId: String = "openai-docs",
    ) = AgentModelClient.ToolCall(
        id = "install-1",
        name = "skills_install_from_github",
        argumentsJson = JSONObject()
            .put("repository", repository)
            .also { if (ref != null) it.put("ref", ref) }
            .put("paths", org.json.JSONArray(paths))
            .put("replaceExisting", replaceExisting)
            .also { if (replaceExisting) it.put("expectedReplacementId", expectedReplacementId) }
            .toString(),
    )

    private fun pendingConflict() = PendingSkillConflictCapability(
        repository = "example/skills",
        commitSha = COMMIT_SHA,
        selectedPath = "skills/demo",
        expectedReplacementId = "demo",
        expectedReplacementName = "Demo Skill",
    )

    private object NoOpLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }

    private companion object {
        const val COMMIT_SHA = "0123456789abcdef0123456789abcdef01234567"
    }
}
