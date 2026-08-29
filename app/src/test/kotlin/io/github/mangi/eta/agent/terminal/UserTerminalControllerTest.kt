package io.github.mangi.eta.agent.terminal

import io.github.mangi.eta.core.AgentLogger
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UserTerminalControllerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun sessionKeepsCwdAndEnvironmentAcrossExec() {
        val controller = UserTerminalController(NoopLogger)
        try {
            val subdir = File(temporaryFolder.root, "subdir").apply { mkdirs() }
            val open = controller.openSession(
                TerminalEnvironment.ANDROID,
                cwd = temporaryFolder.root.absolutePath, identity = "user",
            )
            assertTrue("$open", open is UserTerminalController.OpenResult.Ready)

            val export = controller.exec("export ETA_TEST_VALUE=streaming") { _, _ -> }
            assertEquals(0, export.exitCode)

            val echoOutput = StringBuilder()
            val echo = controller.exec("printf %s \"\$ETA_TEST_VALUE\"") { text, _ -> echoOutput.append(text) }
            assertEquals(0, echo.exitCode)
            assertEquals("streaming", echoOutput.toString())

            val cd = controller.exec("cd subdir") { _, _ -> }
            assertEquals(0, cd.exitCode)
            assertEquals(subdir.absolutePath, cd.cwd)

            val pwdOutput = StringBuilder()
            controller.exec("pwd") { text, _ -> pwdOutput.append(text) }
            assertTrue(pwdOutput.toString().trim().endsWith("subdir"))
        } finally {
            controller.close()
        }
    }

    @Test
    fun execStreamsOutputBeforeCompletion() {
        val controller = UserTerminalController(NoopLogger)
        try {
            val open = controller.openSession(
                TerminalEnvironment.ANDROID,
                cwd = temporaryFolder.root.absolutePath, identity = "user",
            )
            assertTrue(open is UserTerminalController.OpenResult.Ready)

            val firstDelta = CountDownLatch(1)
            val output = StringBuilder()
            val execThread = thread(name = "test-user-terminal-exec") {
                controller.exec("echo first; sleep 1; echo second") { text, _ ->
                    output.append(text)
                    if (output.contains("first")) firstDelta.countDown()
                }
            }

            // 命令整体约 1 秒才结束；首段输出必须在这之前流式到达。
            assertTrue("first delta should arrive before exec completes", firstDelta.await(500, TimeUnit.MILLISECONDS))
            execThread.join(5_000)
            assertFalse(execThread.isAlive)
            assertTrue(output.toString().contains("second"))
        } finally {
            controller.close()
        }
    }

    @Test
    fun statusMarkerIsNotLeakedToOutput() {
        val controller = UserTerminalController(NoopLogger)
        try {
            controller.openSession(TerminalEnvironment.ANDROID, cwd = temporaryFolder.root.absolutePath, identity = "user")
            val output = StringBuilder()
            val result = controller.exec("echo hello") { text, _ -> output.append(text) }
            assertEquals(0, result.exitCode)
            assertEquals("hello", output.toString().trim())
            assertFalse(output.toString().contains("__ETA_STATUS_"))
        } finally {
            controller.close()
        }
    }

    @Test
    fun nonZeroExitCodeIsReported() {
        val controller = UserTerminalController(NoopLogger)
        try {
            controller.openSession(TerminalEnvironment.ANDROID, cwd = temporaryFolder.root.absolutePath, identity = "user")
            val result = controller.exec("(exit 42)") { _, _ -> }
            assertEquals(42, result.exitCode)
            assertFalse(result.sessionClosed)
            assertTrue(controller.isAlive)
        } finally {
            controller.close()
        }
    }

    @Test
    fun exitCommandClosesSessionAndReopenWorks() {
        val controller = UserTerminalController(NoopLogger)
        try {
            controller.openSession(TerminalEnvironment.ANDROID, cwd = temporaryFolder.root.absolutePath, identity = "user")
            val result = controller.exec("exit") { _, _ -> }
            assertNull(result.exitCode)
            assertTrue(result.sessionClosed)
            assertFalse(controller.isAlive)

            val reopen = controller.openSession(TerminalEnvironment.ANDROID, cwd = temporaryFolder.root.absolutePath, identity = "user")
            assertTrue(reopen is UserTerminalController.OpenResult.Ready)
            val output = StringBuilder()
            val exec = controller.exec("echo ok") { text, _ -> output.append(text) }
            assertEquals(0, exec.exitCode)
            assertEquals("ok", output.toString().trim())
        } finally {
            controller.close()
        }
    }

    @Test
    fun stopSessionTerminatesLongCommand() {
        val controller = UserTerminalController(NoopLogger)
        try {
            controller.openSession(TerminalEnvironment.ANDROID, cwd = temporaryFolder.root.absolutePath, identity = "user")

            val execResult = arrayOfNulls<UserTerminalController.ExecResult>(1)
            val started = CountDownLatch(1)
            val execThread = thread(name = "test-user-terminal-stop") {
                started.countDown()
                execResult[0] = controller.exec("sleep 30") { _, _ -> }
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            Thread.sleep(300)

            controller.stopSession()
            execThread.join(5_000)
            assertFalse(execThread.isAlive)

            val result = execResult[0]
            assertNotNull(result)
            assertTrue(result!!.sessionClosed)
            assertTrue(result.interrupted)
            assertNull(result.exitCode)
            assertFalse(controller.isAlive)
        } finally {
            controller.close()
        }
    }

    private object NoopLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
