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
    fun rootfsReadyChecksMarkerFile() {
        val root = tempFolder.newFolder("rootfs")
        assertFalse(LinuxEnvironmentPaths.rootfsReady(root.absolutePath))

        File(root, LinuxEnvironmentPaths.READY_MARKER).createNewFile()
        assertTrue(LinuxEnvironmentPaths.rootfsReady(root.absolutePath))
    }
}
