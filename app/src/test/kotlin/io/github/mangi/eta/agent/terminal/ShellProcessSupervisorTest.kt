package io.github.mangi.eta.agent.terminal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellProcessSupervisorTest {
    @Test
    fun missingSetsidFailsClosedWhenTreeFallbackIsDisabled() {
        val supervisor = ShellProcessSupervisor(
            allowTreeFallback = false,
            setsidCommand = "eta-test-missing-setsid",
        )

        val process = supervisor.startShellProcess(
            identity = "user",
            command = "echo should-not-run",
            mergeStderr = false,
        )

        assertNull(process)
    }

    @Test
    fun rootAndroidPayloadUsesDiscoveredBusyBoxWithoutChangingUserShell() {
        val supervisor = ShellProcessSupervisor()

        val rootPayload = supervisor.buildAndroidPayload("root", "command -v xz")
        val userPayload = supervisor.buildAndroidPayload("user", "id")

        assertTrue(rootPayload.contains("/data/adb/magisk/busybox"))
        assertTrue(rootPayload.contains("ASH_STANDALONE=1"))
        assertEquals("sh -c 'id'", userPayload)
    }

    @Test
    fun linuxPayloadKeepsShellQuotesAndMountsPrivateExchangeDirectory() {
        val supervisor = ShellProcessSupervisor()

        val payload = supervisor.buildLinuxPayload(
            rootfsPath = "/data/user/0/io.github.mangi.eta/files/terminal/alpine/rootfs",
            command = "printf '%s' \"hello\"",
        )

        assertFalse(payload.contains("\\\""))
        assertTrue(payload.contains("unshare -m --propagation private"))
        assertTrue(payload.contains("mount -t proc"))
        assertTrue(payload.contains("eta_mount_required /data/local/tmp"))
        assertTrue(payload.contains("eta_mount_required /data/local/tmp/eta"))
        assertTrue(payload.contains("eta_rootfs/workspace"))
        assertTrue(payload.contains("chroot"))
        assertTrue(payload.contains(AlpineEnvironmentPaths.READY_MARKER))
        assertTrue(payload.contains("/bin/busybox env -i"))
        // Alpine 的 /bin/sh 是绝对符号链接，Android 侧就绪检查必须放行符号链接。
        assertTrue(payload.contains("[ -h \"\$eta_rootfs/bin/sh\" ]"))

        val debianPayload = supervisor.buildLinuxPayload(
            rootfsPath = "/data/user/0/io.github.mangi.eta/files/terminal/debian/rootfs",
            command = "python3 --version",
        )
        assertTrue(debianPayload.contains("/usr/bin/env -i"))
    }

    @Test
    fun linuxPayloadMountsTmpfsAndConfiguresOverlayFsSandbox() {
        val supervisor = ShellProcessSupervisor()

        val payload = supervisor.buildLinuxPayload(
            rootfsPath = "/data/user/0/io.github.mangi.eta/files/terminal/alpine/rootfs",
            command = "uptime",
        )

        // tmpfs mount to /tmp
        assertTrue(payload.contains("mkdir -p \"\$eta_rootfs/tmp\""))
        assertTrue(payload.contains("mount -t tmpfs -o size=512M,mode=1777 tmpfs \"\$eta_rootfs/tmp\""))

        // OverlayFS configuration
        assertTrue(payload.contains("eta_overlay_dir=\"/data/local/tmp/eta/overlay/\$eta_distro\""))
        assertTrue(payload.contains(".sandbox_enabled"))
        assertTrue(payload.contains("ETA_SANDBOX"))
        assertTrue(payload.contains("mount -t overlay overlay -o lowerdir=\"\$eta_rootfs\",upperdir=\"\$eta_overlay_dir/upper\",workdir=\"\$eta_overlay_dir/work\" \"\$eta_overlay_dir/merged\""))
        assertTrue(payload.contains("eta_rootfs=\"\$eta_overlay_dir/merged\""))
    }

    @Test
    fun ptyLauncherWrapsPayloadWithScriptAndSetsSize() {
        val supervisor = ShellProcessSupervisor()
        val launcher = supervisor.buildTrackedShellLauncher(
            ownershipFile = File(System.getProperty("java.io.tmpdir"), "eta-pty-test.owner"),
            ownershipToken = "token123",
            command = null,
            identity = "user",
            environment = TerminalEnvironment.ANDROID,
            linuxRootfsPath = null,
            pty = true,
            ptyCols = 120,
            ptyRows = 40,
        )

        assertTrue(launcher.contains("script -qfc"))
        assertTrue(launcher.contains("stty rows 40 cols 120"))
        assertTrue(launcher.contains("TERM=xterm-256color"))
        assertTrue(launcher.contains("/dev/null"))
    }
}
