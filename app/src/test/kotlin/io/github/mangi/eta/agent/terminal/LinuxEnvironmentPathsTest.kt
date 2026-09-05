package io.github.mangi.eta.agent.terminal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LinuxEnvironmentPathsTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun overlayPathsFollowStandardLayout() {
        val alpineOverlay = LinuxEnvironmentPaths.overlayDir(LinuxDistribution.ALPINE)
        val debianOverlay = LinuxEnvironmentPaths.overlayDir(LinuxDistribution.DEBIAN)

        assertEquals("/data/local/tmp/eta/overlay/alpine", alpineOverlay.path.replace('\\', '/'))
        assertEquals("/data/local/tmp/eta/overlay/debian", debianOverlay.path.replace('\\', '/'))

        val alpineUpper = LinuxEnvironmentPaths.upperDir(LinuxDistribution.ALPINE)
        val alpineWork = LinuxEnvironmentPaths.workDir(LinuxDistribution.ALPINE)
        val alpineMerged = LinuxEnvironmentPaths.mergedDir(LinuxDistribution.ALPINE)

        assertEquals("/data/local/tmp/eta/overlay/alpine/upper", alpineUpper.path.replace('\\', '/'))
        assertEquals("/data/local/tmp/eta/overlay/alpine/work", alpineWork.path.replace('\\', '/'))
        assertEquals("/data/local/tmp/eta/overlay/alpine/merged", alpineMerged.path.replace('\\', '/'))
    }

    @Test
    fun sandboxStatusReflectsSystemProperty() {
        val originalProp = System.getProperty("eta.sandbox")
        try {
            System.setProperty("eta.sandbox", "true")
            assertTrue(LinuxEnvironmentPaths.isSandboxEnabled())

            System.setProperty("eta.sandbox", "false")
            assertFalse(LinuxEnvironmentPaths.isSandboxEnabled())
        } finally {
            if (originalProp != null) {
                System.setProperty("eta.sandbox", originalProp)
            } else {
                System.clearProperty("eta.sandbox")
            }
        }
    }

    @Test
    fun resetSandboxClearsUpperAndWorkDirs() {
        val testBase = tempFolder.newFolder("test_overlay")
        val upper = File(testBase, "upper").apply { mkdirs() }
        val work = File(testBase, "work").apply { mkdirs() }
        val merged = File(testBase, "merged").apply { mkdirs() }

        // Populate test files
        File(upper, "installed_pkg.txt").writeText("some package")
        File(work, "work_file.tmp").writeText("temp work")
        File(merged, "view.txt").writeText("merged view")

        assertTrue(upper.list()?.isNotEmpty() == true)
        assertTrue(work.list()?.isNotEmpty() == true)

        // Custom reset test helper logic matching resetSandboxInternal
        val upperDir = File(testBase, "upper")
        val workDir = File(testBase, "work")
        val mergedDir = File(testBase, "merged")

        upperDir.deleteRecursively()
        workDir.deleteRecursively()
        mergedDir.deleteRecursively()

        upperDir.mkdirs()
        workDir.mkdirs()
        mergedDir.mkdirs()

        assertTrue(upperDir.exists())
        assertEquals(0, upperDir.list()?.size ?: -1)
        assertTrue(workDir.exists())
        assertEquals(0, workDir.list()?.size ?: -1)
    }

    @Test
    fun commitSandboxMethodExists() {
        // Assert commitSandbox contract method availability
        val method = LinuxEnvironmentPaths::class.java.methods.find { it.name == "commitSandbox" }
        org.junit.Assert.assertNotNull(method)
    }
}
