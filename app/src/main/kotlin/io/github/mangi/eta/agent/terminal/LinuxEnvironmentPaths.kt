package io.github.mangi.eta.agent.terminal

import android.content.Context
import java.io.File

/** 两个 Linux rootfs 共用的磁盘布局和就绪判定。 */
internal object LinuxEnvironmentPaths {
    const val READY_MARKER = ".eta-environment-ready"
    const val SANDBOX_MARKER = ".eta-sandbox-enabled"
    const val OVERLAY_BASE_PATH = "/data/local/tmp/eta/overlay"
    const val SANDBOX_FLAG_PATH = "/data/local/tmp/eta/.sandbox_enabled"
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
        File(context.filesDir, "terminal/$SANDBOX_MARKER")

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
        if (File(SANDBOX_FLAG_PATH).isFile) return true
        if (File(OVERLAY_BASE_PATH, ".sandbox_enabled").isFile) return true
        if (context != null && sandboxMarkerFile(context).isFile) return true
        return false
    }

    fun setSandboxEnabled(context: Context, enabled: Boolean): Boolean {
        return runCatching {
            val marker = sandboxMarkerFile(context)
            marker.parentFile?.mkdirs()
            val flag = File(SANDBOX_FLAG_PATH)
            val overlayFlag = File(OVERLAY_BASE_PATH, ".sandbox_enabled")
            if (enabled) {
                marker.createNewFile()
                runCatching { flag.parentFile?.mkdirs(); flag.createNewFile() }
                runCatching { overlayFlag.parentFile?.mkdirs(); overlayFlag.createNewFile() }
            } else {
                marker.delete()
                runCatching { flag.delete() }
                runCatching { overlayFlag.delete() }
            }
            true
        }.getOrDefault(false)
    }

    fun resetSandbox(distribution: LinuxDistribution): Boolean =
        resetSandboxInternal(overlayDir(distribution))

    fun resetSandbox(context: Context, distribution: LinuxDistribution): Boolean =
        resetSandboxInternal(overlayDir(context, distribution))

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
