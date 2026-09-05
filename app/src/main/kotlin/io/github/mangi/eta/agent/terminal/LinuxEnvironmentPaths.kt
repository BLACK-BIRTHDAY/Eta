package io.github.mangi.eta.agent.terminal

import android.content.Context
import java.io.File

/** 两个 Linux rootfs 共用的磁盘布局和就绪判定。 */
internal object LinuxEnvironmentPaths {
    const val READY_MARKER = ".eta-environment-ready"
    const val SANDBOX_MARKER = ".eta-sandbox-enabled"
    const val OVERLAY_BASE_PATH = "/data/local/tmp/eta/overlay"
    const val SANDBOX_FLAG_PATH = "/data/local/tmp/eta/.sandbox_enabled"
    const val PREFS_NAME = "eta_terminal_prefs"
    const val PREF_KEY_SANDBOX = "sandbox_enabled"
    const val ENV_SANDBOX = "ETA_SANDBOX"
    const val ENV_LINUX_SANDBOX = "ETA_LINUX_SANDBOX"

    fun environmentDir(context: Context, distribution: LinuxDistribution): File =
        File(context.filesDir, "terminal/${distribution.wireName}")

    fun rootfsDir(context: Context, distribution: LinuxDistribution): File =
        File(environmentDir(context, distribution), "rootfs")

    fun rootfsReady(rootfsPath: String?): Boolean {
        if (rootfsPath.isNullOrBlank()) return false
        return File(rootfsPath, READY_MARKER).isFile
    }

    fun sandboxRootfsDir(context: Context, distribution: LinuxDistribution): File =
        File(environmentDir(context, distribution), "sandbox_rootfs")

    fun effectiveRootfsDir(context: Context, distribution: LinuxDistribution): File {
        val rootfs = rootfsDir(context, distribution)
        if (!isSandboxEnabled(context) || !rootfsReady(rootfs.absolutePath)) {
            return rootfs
        }
        val sandboxDir = sandboxRootfsDir(context, distribution)
        val sandboxReadyMarker = File(sandboxDir, READY_MARKER)
        if (!sandboxReadyMarker.isFile) {
            prepareSandboxRootfs(context, distribution)
        }
        return if (sandboxReadyMarker.isFile) sandboxDir else rootfs
    }

    fun prepareSandboxRootfs(context: Context, distribution: LinuxDistribution): Boolean {
        val sourceDir = rootfsDir(context, distribution)
        val targetDir = sandboxRootfsDir(context, distribution)
        if (!rootfsReady(sourceDir.absolutePath)) return false

        return runCatching {
            targetDir.mkdirs()
            val src = sourceDir.absolutePath
            val dst = targetDir.absolutePath
            val cmd = "rm -rf '$dst' && mkdir -p '$dst' && cp -al '$src/.' '$dst/' 2>/dev/null && touch '$dst/$READY_MARKER' || true"
            val process = ProcessBuilder("su", "-c", cmd).start()
            process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
            File(targetDir, READY_MARKER).isFile
        }.getOrDefault(false)
    }

    fun overlayDir(distribution: LinuxDistribution): File =
        File(OVERLAY_BASE_PATH, distribution.wireName)

    fun overlayDir(context: Context, distribution: LinuxDistribution): File =
        overlayDir(distribution)

    fun upperDir(distribution: LinuxDistribution): File =
        File(overlayDir(distribution), "upper")

    fun upperDir(context: Context, distribution: LinuxDistribution): File =
        upperDir(distribution)

    fun workDir(distribution: LinuxDistribution): File =
        File(overlayDir(distribution), "work")

    fun workDir(context: Context, distribution: LinuxDistribution): File =
        workDir(distribution)

    fun mergedDir(distribution: LinuxDistribution): File =
        File(overlayDir(distribution), "merged")

    fun mergedDir(context: Context, distribution: LinuxDistribution): File =
        mergedDir(distribution)

    fun sandboxMarkerFile(context: Context): File =
        File(context.filesDir, SANDBOX_MARKER)

    fun isSandboxEnabled(context: Context): Boolean =
        isSandboxEnabledInternal(context)

    fun isSandboxEnabled(): Boolean =
        isSandboxEnabledInternal(null)

    private fun isSandboxEnabledInternal(context: Context?): Boolean {
        System.getenv(ENV_SANDBOX)?.let { env ->
            if (env == "1" || env.equals("true", ignoreCase = true)) return true
            if (env == "0" || env.equals("false", ignoreCase = true)) return false
        }
        System.getenv(ENV_LINUX_SANDBOX)?.let { env ->
            if (env == "1" || env.equals("true", ignoreCase = true)) return true
            if (env == "0" || env.equals("false", ignoreCase = true)) return false
        }
        System.getProperty("eta.sandbox")?.let { prop ->
            if (prop == "1" || prop.equals("true", ignoreCase = true)) return true
            if (prop == "0" || prop.equals("false", ignoreCase = true)) return false
        }
        System.getProperty("eta.linux.sandbox")?.let { prop ->
            if (prop == "1" || prop.equals("true", ignoreCase = true)) return true
            if (prop == "0" || prop.equals("false", ignoreCase = true)) return false
        }
        if (context != null) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.contains(PREF_KEY_SANDBOX)) {
                return prefs.getBoolean(PREF_KEY_SANDBOX, false)
            }
        }
        if (File(SANDBOX_FLAG_PATH).isFile) return true
        if (File(OVERLAY_BASE_PATH, ".sandbox_enabled").isFile) return true
        if (File(OVERLAY_BASE_PATH, SANDBOX_MARKER).isFile) return true
        if (context != null && sandboxMarkerFile(context).isFile) return true
        if (File("/data/data/io.github.mangi.eta/files/$SANDBOX_MARKER").isFile) return true
        if (File("/data/user/0/io.github.mangi.eta/files/$SANDBOX_MARKER").isFile) return true
        if (File("/data/data/io.github.mangi.eta/files/terminal/$SANDBOX_MARKER").isFile) return true
        if (File("/data/user/0/io.github.mangi.eta/files/terminal/$SANDBOX_MARKER").isFile) return true
        return false
    }

    fun setSandboxEnabled(context: Context, enabled: Boolean): Boolean {
        return runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_KEY_SANDBOX, enabled)
                .commit()

            val marker = sandboxMarkerFile(context)
            val legacyMarker = File(context.filesDir, "terminal/$SANDBOX_MARKER")
            val flag = File(SANDBOX_FLAG_PATH)
            val overlayFlag = File(OVERLAY_BASE_PATH, ".sandbox_enabled")
            if (enabled) {
                marker.createNewFile()
                runCatching { legacyMarker.createNewFile() }
                runCatching { flag.parentFile?.mkdirs(); flag.createNewFile() }
                runCatching { overlayFlag.parentFile?.mkdirs(); overlayFlag.createNewFile() }
                runCatching {
                    ProcessBuilder(
                        "su",
                        "-c",
                        "mkdir -p '$OVERLAY_BASE_PATH' && touch '$SANDBOX_FLAG_PATH' '$OVERLAY_BASE_PATH/.sandbox_enabled' '$OVERLAY_BASE_PATH/$SANDBOX_MARKER' '${marker.absolutePath}' 2>/dev/null || true",
                    ).start().waitFor()
                }
            } else {
                marker.delete()
                runCatching { legacyMarker.delete() }
                runCatching { flag.delete() }
                runCatching { overlayFlag.delete() }
                runCatching {
                    ProcessBuilder(
                        "su",
                        "-c",
                        "rm -f '$SANDBOX_FLAG_PATH' '$OVERLAY_BASE_PATH/.sandbox_enabled' '$OVERLAY_BASE_PATH/$SANDBOX_MARKER' '${marker.absolutePath}' '${legacyMarker.absolutePath}' 2>/dev/null || true",
                    ).start().waitFor()
                }
            }
            true
        }.getOrDefault(false)
    }

    fun resetSandbox(distribution: LinuxDistribution): Boolean =
        resetSandboxInternal(overlayDir(distribution))

    fun resetSandbox(context: Context, distribution: LinuxDistribution): Boolean {
        val overlayOk = resetSandboxInternal(overlayDir(context, distribution))
        val sandboxDir = sandboxRootfsDir(context, distribution)
        val sourceDir = rootfsDir(context, distribution)
        val hardlinkOk = runCatching {
            val dst = sandboxDir.absolutePath
            val src = sourceDir.absolutePath
            val cmd = "rm -rf '$dst' && mkdir -p '$dst' && cp -al '$src/.' '$dst/' 2>/dev/null && touch '$dst/$READY_MARKER' || true"
            val process = ProcessBuilder("su", "-c", cmd).start()
            process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
            File(sandboxDir, READY_MARKER).isFile
        }.getOrDefault(false)
        return overlayOk || hardlinkOk
    }

    private fun resetSandboxInternal(baseDir: File): Boolean {
        val upper = File(baseDir, "upper")
        val work = File(baseDir, "work")
        val merged = File(baseDir, "merged")

        var javaDeleted = true
        if (upper.exists()) {
            javaDeleted = upper.deleteRecursively() && javaDeleted
        }
        if (work.exists()) {
            javaDeleted = work.deleteRecursively() && javaDeleted
        }
        if (merged.exists()) {
            merged.deleteRecursively()
        }

        if (upper.exists() || work.exists()) {
            runCatching {
                val upperPath = upper.absolutePath
                val workPath = work.absolutePath
                val mergedPath = merged.absolutePath
                val cmd = "rm -rf '$upperPath' '$workPath' '$mergedPath' 2>/dev/null || true"
                val process = ProcessBuilder("su", "-c", cmd).start()
                process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            }
        }

        val success = (!upper.exists() || upper.list().isNullOrEmpty()) &&
            (!work.exists() || work.list().isNullOrEmpty())

        runCatching {
            upper.mkdirs()
            work.mkdirs()
            merged.mkdirs()
        }

        return success
    }
}
