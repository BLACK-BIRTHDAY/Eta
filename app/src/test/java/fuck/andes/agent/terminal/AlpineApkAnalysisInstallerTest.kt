package fuck.andes.agent.terminal

import fuck.andes.core.AgentLogger
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AlpineApkAnalysisInstallerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun artifactManifestPinsOfficialHttpsDownloadsAndIntegrityMetadata() {
        val artifacts = AlpineApkAnalysisInstaller.ARTIFACTS

        assertEquals(listOf("jadx", "apktool", "smali", "baksmali"), artifacts.map { it.id })
        assertTrue(artifacts.all { artifact -> artifact.url.startsWith("https://github.com/") })
        assertTrue(artifacts.all { artifact -> artifact.fallbackUrls.size == 2 })
        assertTrue(
            artifacts.all { artifact ->
                artifact.fallbackUrls.all { fallback -> fallback.endsWith(artifact.url) }
            },
        )
        assertTrue(artifacts.all { artifact -> artifact.sha256.matches(Regex("[0-9a-f]{64}")) })
        assertTrue(artifacts.all { artifact -> artifact.sizeBytes > 1_000_000L })
        assertEquals(83_881_596L, artifacts.sumOf { artifact -> artifact.sizeBytes })
        assertTrue(AlpineApkAnalysisInstaller.MIN_AVAILABLE_BYTES > artifacts.sumOf { it.sizeBytes } * 2)
    }

    @Test
    fun cachedArtifactVerificationRejectsWrongSizeAndDigest() {
        val file = temporaryFolder.newFile("artifact.bin")
        val artifact = VerifiedArtifact(
            id = "fixture",
            version = "1",
            fileName = file.name,
            url = "https://example.invalid/artifact.bin",
            sha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sizeBytes = 3,
        )
        val downloader = VerifiedArtifactDownloader()

        file.writeText("ab")
        assertFalse(downloader.verify(artifact, file))
        file.writeText("abd")
        assertFalse(downloader.verify(artifact, file))
        file.writeText("abc")
        assertTrue(downloader.verify(artifact, file))
    }

    @Test
    fun apktoolWrapperRejectsBuildWithoutArm64Aapt2() {
        val wrapper = AlpineApkAnalysisInstaller.APKTOOL_WRAPPER

        assertTrue(wrapper.contains("b|build"))
        assertTrue(wrapper.contains("APKTOOL_BUILD_UNAVAILABLE"))
        assertTrue(wrapper.contains("exit 64"))
        assertTrue(wrapper.contains("exec java -jar"))
    }

    @Test
    fun javaWrapperUsesRealLauncherAndSuppliesJdkLibraries() {
        val wrapper = AlpineApkAnalysisInstaller.JAVA_WRAPPER

        assertTrue(wrapper.contains("JAVA_HOME=/usr/lib/jvm/default-jvm"))
        assertTrue(wrapper.contains("LD_LIBRARY_PATH=/usr/lib/jvm/default-jvm/lib"))
        assertTrue(wrapper.contains("exec /usr/lib/jvm/default-jvm/bin/java"))
    }

    @Test
    fun downloaderUsesPinnedFallbackAfterOfficialEndpointFails() = runBlocking {
        val requestedHosts = mutableListOf<String>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                requestedHosts += request.url.host
                if (request.url.host == "official.invalid") {
                    response(request, 502, ByteArray(0))
                } else {
                    response(request, 200, "abc".encodeToByteArray())
                }
            }
            .build()
        val artifact = VerifiedArtifact(
            id = "fixture",
            version = "1",
            fileName = "fixture.bin",
            url = "https://official.invalid/fixture.bin",
            sha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sizeBytes = 3,
            fallbackUrls = listOf("https://fallback.invalid/fixture.bin"),
        )
        val target = File(temporaryFolder.root, artifact.fileName)

        assertTrue(VerifiedArtifactDownloader(client, NoopLogger).download(artifact, target))
        assertEquals(listOf("official.invalid", "fallback.invalid"), requestedHosts)
        assertEquals("abc", target.readText())
    }

    private fun response(request: Request, code: Int, body: ByteArray): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Failed")
            .body(body.toResponseBody())
            .build()

    private object NoopLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
