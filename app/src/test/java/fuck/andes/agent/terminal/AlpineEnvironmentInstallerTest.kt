package fuck.andes.agent.terminal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AlpineEnvironmentInstallerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun artifactSelectionUsesFirstSupportedAbiWithPinnedIntegrityMetadata() {
        val artifact = AlpineEnvironmentInstaller.artifactForAbis(
            listOf("armeabi-v7a", "arm64-v8a", "x86_64"),
        )

        requireNotNull(artifact)
        assertEquals("3.24.1", artifact.version)
        assertTrue(artifact.fileName.endsWith("-aarch64.tar.gz"))
        assertTrue(artifact.url.startsWith("https://dl-cdn.alpinelinux.org/alpine/v3.24/"))
        assertEquals(64, artifact.sha256.length)
        assertEquals(4_023_732L, artifact.sizeBytes)
    }

    @Test
    fun unsupportedAbiDoesNotGuessAnArtifact() {
        assertNull(AlpineEnvironmentInstaller.artifactForAbis(listOf("armeabi-v7a", "x86")))
    }

    @Test
    fun readinessRequiresMarkerAndBusyBoxAndTracksCommonToolsSeparately() {
        val rootfs = temporaryFolder.newFolder("rootfs")
        val bin = File(rootfs, "bin").apply { mkdirs() }
        val busyBox = File(bin, "busybox")
        val ready = File(rootfs, AlpineEnvironmentPaths.READY_MARKER)

        assertFalse(AlpineEnvironmentPaths.rootfsReady(rootfs.absolutePath))
        busyBox.writeText("busybox")
        assertFalse(AlpineEnvironmentPaths.rootfsReady(rootfs.absolutePath))
        ready.writeText("version=3.24.1\n")
        assertTrue(AlpineEnvironmentPaths.rootfsReady(rootfs.absolutePath))
        assertFalse(AlpineEnvironmentPaths.commonToolsReady(rootfs.absolutePath))

        File(rootfs, AlpineEnvironmentPaths.COMMON_TOOLS_MARKER).writeText("3.24.1\n")
        assertFalse(AlpineEnvironmentPaths.commonToolsReady(rootfs.absolutePath))

        File(rootfs, AlpineEnvironmentPaths.COMMON_TOOLS_MARKER).writeText(
            "alpine=3.24.1\ntoolset=${AlpineEnvironmentPaths.TOOLSET_REVISION}\nprofiles=agent,python\n",
        )
        assertTrue(AlpineEnvironmentPaths.commonToolsReady(rootfs.absolutePath))
    }

    @Test
    fun defaultToolsetContainsAgentAndPythonEssentialsWithoutInteractiveEditors() {
        val packages = AlpineEnvironmentInstaller.DEFAULT_PACKAGES

        assertTrue(packages.containsAll(listOf("ripgrep", "fd", "diffutils", "patch", "rsync")))
        assertTrue(packages.containsAll(listOf("python3", "py3-virtualenv", "pipx", "uv", "ruff")))
        assertFalse(packages.contains("vim"))
        assertFalse(packages.contains("nano"))
        assertEquals(packages.distinct(), packages)
    }

    @Test
    fun apkAnalysisReadinessRequiresCurrentMarkerAndManagedFiles() {
        val rootfs = temporaryFolder.newFolder("analysis-rootfs")
        File(rootfs, "bin").mkdirs()
        File(rootfs, "bin/busybox").writeText("busybox")
        File(rootfs, AlpineEnvironmentPaths.READY_MARKER).writeText("version=3.24.1\n")
        File(rootfs, AlpineEnvironmentPaths.COMMON_TOOLS_MARKER).writeText(
            "toolset=${AlpineEnvironmentPaths.TOOLSET_REVISION}\n",
        )
        val current = File(rootfs, "opt/eta/apk-analysis/current")
        listOf("bin/java", "jadx/bin/jadx", "bin/apktool", "bin/smali", "bin/baksmali").forEach { path ->
            File(current, path).apply {
                parentFile?.mkdirs()
                writeText(path)
            }
        }

        File(rootfs, AlpineEnvironmentPaths.APK_ANALYSIS_MARKER).writeText("profile=0\n")
        assertFalse(AlpineEnvironmentPaths.apkAnalysisReady(rootfs.absolutePath))

        File(rootfs, AlpineEnvironmentPaths.APK_ANALYSIS_MARKER).writeText(
            "profile=${AlpineEnvironmentPaths.APK_ANALYSIS_REVISION}\n",
        )
        assertTrue(AlpineEnvironmentPaths.apkAnalysisReady(rootfs.absolutePath))
        File(current, "jadx/bin/jadx").delete()
        assertFalse(AlpineEnvironmentPaths.apkAnalysisReady(rootfs.absolutePath))
    }
}
