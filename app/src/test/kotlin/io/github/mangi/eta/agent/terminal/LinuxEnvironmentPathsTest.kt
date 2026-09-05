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
    fun commitSandboxMethodExists() {
        // Assert commitSandbox contract method availability
        val method = LinuxEnvironmentPaths::class.java.methods.find { it.name == "commitSandbox" }
        org.junit.Assert.assertNotNull(method)
    }
}
