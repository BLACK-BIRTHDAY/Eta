package io.github.mangi.eta.agent.skill

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PublicGitHubSkillSourceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `inspection pins ref to commit tree and filters candidates by prefix`() {
        val requests = mutableListOf<Request>()
        val client = fakeClient { request ->
            requests += request
            when {
                request.url.encodedPath.endsWith("/commits/main") -> jsonResponse(
                    request,
                    commitJson(),
                )
                request.url.encodedPath.contains("/git/trees/$TREE_SHA") -> jsonResponse(
                    request,
                    JSONObject()
                        .put("truncated", false)
                        .put(
                            "tree",
                            JSONArray()
                                .put(treeEntry("SKILL.md"))
                                .put(treeEntry("skills/alpha/SKILL.md"))
                                .put(treeEntry("skills/beta/SKILL.md"))
                                .put(treeEntry("other/ignored/SKILL.md")),
                        ),
                )
                else -> error("unexpected URL ${request.url}")
            }
        }
        val source = PublicGitHubSkillSource(temporaryFolder.root, client)

        val inspection = source.inspect(
            GitHubSkillRepository("example", "skills", ref = "main", path = "skills"),
        )

        assertEquals(listOf("skills/alpha", "skills/beta"), inspection.candidates.map { it.path })
        assertEquals(COMMIT_SHA, inspection.commitSha)
        assertTrue(requests[1].url.queryParameter("recursive") == "1")
        assertTrue(requests[1].url.encodedPath.endsWith("/git/trees/$TREE_SHA"))
        requests.forEach { request -> assertNull(request.header("Authorization")) }
        source.close()
    }

    @Test
    fun `download uses fixed commit codeload host and deletes temporary archive`() {
        val requests = mutableListOf<Request>()
        val client = fakeClient { request ->
            requests += request
            when (request.url.host) {
                "api.github.com" -> jsonResponse(request, commitJson())
                "codeload.github.com" -> response(
                    request = request,
                    body = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
                        .toResponseBody("application/zip".toMediaType()),
                )
                else -> error("unexpected host ${request.url.host}")
            }
        }
        val cacheDirectory = File(temporaryFolder.root, "skill-github").also { it.mkdirs() }
        val staleArchive = File(cacheDirectory, "eta-skill-github-stale.zip").also {
            it.writeBytes(byteArrayOf(1))
            it.setLastModified(System.currentTimeMillis() - 25L * 60 * 60 * 1_000)
        }
        val source = PublicGitHubSkillSource(temporaryFolder.root, client)
        lateinit var archiveFile: File

        source.downloadArchive(GitHubSkillRepository("example", "skills", ref = "main"))
            .use { archive ->
                archiveFile = archive.file
                assertTrue(archiveFile.isFile)
                assertEquals(COMMIT_SHA, archive.commitSha)
            }

        assertFalse(archiveFile.exists())
        assertFalse(staleArchive.exists())
        val download = requests.single { it.url.host == "codeload.github.com" }
        assertEquals("/$OWNER/$REPOSITORY/zip/$COMMIT_SHA", download.url.encodedPath)
        source.close()
    }

    @Test
    fun `redirects are rejected instead of following an untrusted host`() {
        val client = fakeClient { request ->
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(302)
                .message("Found")
                .header("Location", "https://example.com/archive.zip")
                .body(ByteArray(0).toResponseBody())
                .build()
        }
        val source = PublicGitHubSkillSource(temporaryFolder.root, client)

        val failure = assertThrows(GitHubSkillSourceException::class.java) {
            source.inspect(GitHubSkillRepository(OWNER, REPOSITORY, ref = "main"))
        }

        assertEquals("GITHUB_REDIRECT_REJECTED", failure.code)
        source.close()
    }

    @Test
    fun `download rejects a different commit for an already pinned sha`() {
        val source = PublicGitHubSkillSource(
            temporaryFolder.root,
            fakeClient { request -> jsonResponse(request, commitJson()) },
        )
        val pinned = "3".repeat(40)

        val failure = assertThrows(GitHubSkillSourceException::class.java) {
            source.downloadArchive(
                GitHubSkillRepository(OWNER, REPOSITORY, ref = pinned),
            )
        }

        assertEquals("GITHUB_COMMIT_MISMATCH", failure.code)
        source.close()
    }

    @Test
    fun `download cache rejects directory symlink`() {
        val external = temporaryFolder.newFolder("external-cache")
        val cacheDirectory = File(temporaryFolder.root, "skill-github")
        try {
            Files.createSymbolicLink(cacheDirectory.toPath(), external.toPath())
        } catch (error: Exception) {
            assumeNoException(error)
        }
        val source = PublicGitHubSkillSource(
            temporaryFolder.root,
            fakeClient { request -> jsonResponse(request, commitJson()) },
        )

        val failure = assertThrows(GitHubSkillSourceException::class.java) {
            source.downloadArchive(GitHubSkillRepository(OWNER, REPOSITORY, ref = "main"))
        }

        assertEquals("CACHE_UNAVAILABLE", failure.code)
        assertTrue(external.listFiles().orEmpty().isEmpty())
        source.close()
    }

    @Test
    fun `inspection extracts directory treeSha for candidate and defaultBranch`() {
        val client = fakeClient { request ->
            when {
                request.url.encodedPath.endsWith("/commits/main") -> jsonResponse(request, commitJson())
                request.url.encodedPath.contains("/git/trees/$TREE_SHA") -> jsonResponse(
                    request,
                    JSONObject()
                        .put("truncated", false)
                        .put(
                            "tree",
                            JSONArray()
                                .put(treeEntry("skills/alpha", type = "tree", sha = ALPHA_TREE_SHA))
                                .put(treeEntry("skills/alpha/SKILL.md", sha = ALPHA_BLOB_SHA))
                                .put(treeEntry("skills/beta/SKILL.md")),
                        ),
                )
                else -> error("unexpected URL ${request.url}")
            }
        }
        val source = PublicGitHubSkillSource(temporaryFolder.root, client)

        val inspection = source.inspect(
            GitHubSkillRepository("example", "skills", ref = "main", path = "skills"),
        )

        assertEquals("main", inspection.defaultBranch)
        val alphaCandidate = inspection.candidates.single { it.path == "skills/alpha" }
        assertEquals(ALPHA_TREE_SHA, alphaCandidate.treeSha)
        val betaCandidate = inspection.candidates.single { it.path == "skills/beta" }
        assertEquals(TREE_SHA, betaCandidate.treeSha)
        source.close()
    }

    @Test
    fun `downloadSkillSubtreeArchive fetches only blobs under selectedPaths from raw host and never calls codeload`() {
        val requests = mutableListOf<Request>()
        val client = fakeClient { request ->
            requests += request
            when {
                request.url.encodedPath.endsWith("/commits/main") -> jsonResponse(request, commitJson())
                request.url.encodedPath.contains("/git/trees/$TREE_SHA") -> jsonResponse(
                    request,
                    JSONObject()
                        .put("truncated", false)
                        .put(
                            "tree",
                            JSONArray()
                                .put(treeEntry("skills/alpha", type = "tree", sha = ALPHA_TREE_SHA))
                                .put(treeEntry("skills/alpha/SKILL.md", sha = ALPHA_BLOB_SHA))
                                .put(treeEntry("skills/alpha/scripts/run.sh", sha = "4".repeat(40)))
                                .put(treeEntry("skills/beta/SKILL.md", sha = "5".repeat(40)))
                                .put(treeEntry("skills/beta/other.txt", sha = "6".repeat(40)))
                                .put(treeEntry("root_file.txt", sha = "7".repeat(40))),
                        ),
                )
                request.url.host == "raw.githubusercontent.com" -> {
                    val content = when (request.url.encodedPath) {
                        "/example/skills/$COMMIT_SHA/skills/alpha/SKILL.md" -> "---\nname: alpha\n---\n# Alpha"
                        "/example/skills/$COMMIT_SHA/skills/alpha/scripts/run.sh" -> "#!/bin/sh\necho hello"
                        else -> error("Unexpected raw URL ${request.url}")
                    }
                    response(request, content.toByteArray().toResponseBody("text/plain".toMediaType()))
                }
                else -> error("unexpected URL ${request.url}")
            }
        }
        val source = PublicGitHubSkillSource(temporaryFolder.root, client)

        lateinit var archiveFile: File
        source.downloadSkillSubtreeArchive(
            repository = GitHubSkillRepository("example", "skills", ref = "main"),
            selectedPaths = listOf("skills/alpha"),
        ).use { archive ->
            archiveFile = archive.file
            assertTrue(archiveFile.isFile)
            assertEquals("main", archive.defaultBranch)
            assertEquals(COMMIT_SHA, archive.commitSha)
            assertEquals(mapOf("skills/alpha" to ALPHA_TREE_SHA), archive.treeShasByPath)

            // Inspect contents of synthesized envelope zip
            val zipEntries = mutableListOf<String>()
            ZipInputStream(archive.file.inputStream().buffered()).use { zipIn ->
                while (true) {
                    val entry = zipIn.nextEntry ?: break
                    zipEntries += entry.name
                }
            }
            assertTrue("Envelope directory must exist", zipEntries.contains("skills-$COMMIT_SHA/"))
            assertTrue(
                "SKILL.md must exist in envelope",
                zipEntries.contains("skills-$COMMIT_SHA/skills/alpha/SKILL.md"),
            )
            assertTrue(
                "Script file must exist in envelope",
                zipEntries.contains("skills-$COMMIT_SHA/skills/alpha/scripts/run.sh"),
            )
            assertFalse(
                "Beta skill must not be included",
                zipEntries.any { it.contains("beta") },
            )
        }

        assertFalse("Temporary archive must be deleted on close", archiveFile.exists())
        assertFalse("Codeload host must NEVER be called", requests.any { it.url.host == "codeload.github.com" })
        val rawRequests = requests.filter { it.url.host == "raw.githubusercontent.com" }
        assertEquals(2, rawRequests.size)
        assertTrue(rawRequests.all { it.url.encodedPath.startsWith("/example/skills/$COMMIT_SHA/skills/alpha/") })
        source.close()
    }

    @Test
    fun `downloadSkillSubtreeArchive throws if selectedPaths is empty`() {
        val source = PublicGitHubSkillSource(
            temporaryFolder.root,
            fakeClient { request -> jsonResponse(request, commitJson()) },
        )

        val failure = assertThrows(GitHubSkillSourceException::class.java) {
            source.downloadSkillSubtreeArchive(
                repository = GitHubSkillRepository(OWNER, REPOSITORY, ref = "main"),
                selectedPaths = emptyList(),
            )
        }

        assertEquals("INVALID_GITHUB_SOURCE", failure.code)
        source.close()
    }

    @Test
    fun `downloadSkillSubtreeArchive throws if selected path has no SKILL md in tree`() {
        val client = fakeClient { request ->
            when {
                request.url.encodedPath.endsWith("/commits/main") -> jsonResponse(request, commitJson())
                request.url.encodedPath.contains("/git/trees/$TREE_SHA") -> jsonResponse(
                    request,
                    JSONObject()
                        .put("truncated", false)
                        .put(
                            "tree",
                            JSONArray()
                                .put(treeEntry("skills/alpha/scripts/run.sh")),
                        ),
                )
                else -> error("unexpected URL ${request.url}")
            }
        }
        val source = PublicGitHubSkillSource(temporaryFolder.root, client)

        val failure = assertThrows(GitHubSkillSourceException::class.java) {
            source.downloadSkillSubtreeArchive(
                repository = GitHubSkillRepository(OWNER, REPOSITORY, ref = "main"),
                selectedPaths = listOf("skills/alpha"),
            )
        }

        assertEquals("SKILL_NOT_FOUND_IN_TREE", failure.code)
        source.close()
    }

    @Test
    fun `downloadSkillSubtreeArchive supports root skill path`() {
        val client = fakeClient { request ->
            when {
                request.url.encodedPath.endsWith("/commits/main") -> jsonResponse(request, commitJson())
                request.url.encodedPath.contains("/git/trees/$TREE_SHA") -> jsonResponse(
                    request,
                    JSONObject()
                        .put("truncated", false)
                        .put(
                            "tree",
                            JSONArray()
                                .put(treeEntry("SKILL.md", sha = "a".repeat(40)))
                                .put(treeEntry("assets/logo.png", sha = "b".repeat(40))),
                        ),
                )
                request.url.host == "raw.githubusercontent.com" -> {
                    response(request, "dummy".toByteArray().toResponseBody("text/plain".toMediaType()))
                }
                else -> error("unexpected URL ${request.url}")
            }
        }
        val source = PublicGitHubSkillSource(temporaryFolder.root, client)

        source.downloadSkillSubtreeArchive(
            repository = GitHubSkillRepository("example", "skills", ref = "main"),
            selectedPaths = listOf("."),
        ).use { archive ->
            assertEquals(mapOf("." to TREE_SHA), archive.treeShasByPath)
            val zipEntries = mutableListOf<String>()
            ZipInputStream(archive.file.inputStream().buffered()).use { zipIn ->
                while (true) {
                    val entry = zipIn.nextEntry ?: break
                    zipEntries += entry.name
                }
            }
            assertTrue(zipEntries.contains("skills-$COMMIT_SHA/SKILL.md"))
            assertTrue(zipEntries.contains("skills-$COMMIT_SHA/assets/logo.png"))
        }
        source.close()
    }

    @Test
    fun `downloadSkillSubtreeArchive enforces size limit across blobs`() {
        val client = fakeClient { request ->
            when {
                request.url.encodedPath.endsWith("/commits/main") -> jsonResponse(request, commitJson())
                request.url.encodedPath.contains("/git/trees/$TREE_SHA") -> jsonResponse(
                    request,
                    JSONObject()
                        .put("truncated", false)
                        .put(
                            "tree",
                            JSONArray()
                                .put(treeEntry("skills/large/SKILL.md", sha = "a".repeat(40))),
                        ),
                )
                request.url.host == "raw.githubusercontent.com" -> {
                    val largeBody = object : okhttp3.ResponseBody() {
                        override fun contentType(): okhttp3.MediaType? = "application/octet-stream".toMediaType()
                        override fun contentLength(): Long = 33L * 1024 * 1024
                        override fun source(): okio.BufferedSource = okio.Buffer()
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(largeBody)
                        .build()
                }
                else -> error("unexpected URL ${request.url}")
            }
        }
        val source = PublicGitHubSkillSource(temporaryFolder.root, client)

        val failure = assertThrows(GitHubSkillSourceException::class.java) {
            source.downloadSkillSubtreeArchive(
                repository = GitHubSkillRepository(OWNER, REPOSITORY, ref = "main"),
                selectedPaths = listOf("skills/large"),
            )
        }

        assertEquals("GITHUB_ARCHIVE_TOO_LARGE", failure.code)
        source.close()
    }

    private fun fakeClient(handler: (Request) -> Response): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain -> handler(chain.request()) }
            .build()

    private fun commitJson(): JSONObject = JSONObject()
        .put("sha", COMMIT_SHA)
        .put(
            "commit",
            JSONObject().put("tree", JSONObject().put("sha", TREE_SHA)),
        )

    private fun treeEntry(path: String, type: String = "blob", sha: String? = null): JSONObject = JSONObject()
        .put("type", type)
        .put("path", path)
        .apply {
            if (sha != null) put("sha", sha)
        }

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

    private companion object {
        const val OWNER = "example"
        const val REPOSITORY = "skills"
        const val COMMIT_SHA = "1111111111111111111111111111111111111111"
        const val TREE_SHA = "2222222222222222222222222222222222222222"
        const val ALPHA_TREE_SHA = "3333333333333333333333333333333333333333"
        const val ALPHA_BLOB_SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
