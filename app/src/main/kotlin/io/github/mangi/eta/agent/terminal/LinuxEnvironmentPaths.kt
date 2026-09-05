package io.github.mangi.eta.agent.terminal

import android.content.Context
import java.io.File

/** 两个 Linux rootfs 共用的磁盘布局和就绪判定。 */
internal object LinuxEnvironmentPaths {
    const val READY_MARKER = ".eta-environment-ready"
    const val SANDBOX_MARKER = ".eta-sandbox-enabled"
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
            if (enabled) {
                marker.createNewFile()
                runCatching { legacyMarker.createNewFile() }
                runCatching {
                    ProcessBuilder(
                        "su",
                        "-c",
                        "touch '${marker.absolutePath}' 2>/dev/null || true",
                    ).start().waitFor()
                }
            } else {
                marker.delete()
                runCatching { legacyMarker.delete() }
                runCatching {
                    ProcessBuilder(
                        "su",
                        "-c",
                        "rm -f '${marker.absolutePath}' '${legacyMarker.absolutePath}' 2>/dev/null || true",
                    ).start().waitFor()
                }
            }
            true
        }.getOrDefault(false)
    }

    fun commitSandbox(context: Context, distribution: LinuxDistribution): Boolean {
        val sandboxDir = sandboxRootfsDir(context, distribution)
        val sourceDir = rootfsDir(context, distribution)
        if (!sandboxDir.isDirectory || !sourceDir.isDirectory) return false

        return runCatching {
            val src = sandboxDir.absolutePath
            val dst = sourceDir.absolutePath
            val backup = File(environmentDir(context, distribution), "rootfs_backup_${System.currentTimeMillis()}").absolutePath
            val garbage = File("/data/local/tmp/.garbage_commit_${System.currentTimeMillis()}").absolutePath
            // 异步脱钩原子固化：将旧底包秒级移至待清理目录，将当前沙盒提拔为新底包，再克隆出新沙盒
            val cmd = """
                mv '$dst' '$backup' && \
                mv '$src' '$dst' && \
                mkdir -p '$src' && \
                cp -al '$dst/.' '$src/' 2>/dev/null && \
                touch '$dst/$READY_MARKER' '$src/$READY_MARKER' && \
                (mkdir -p '$garbage' && mv '$backup' '$garbage/' && rm -rf '$garbage' >/dev/null 2>&1 &) || true
            """.trimIndent()
            val process = ProcessBuilder("su", "-c", cmd).start()
            process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
            File(sourceDir, READY_MARKER).isFile && File(sandboxDir, READY_MARKER).isFile
        }.getOrDefault(false)
    }

    fun resetSandbox(context: Context, distribution: LinuxDistribution): Boolean {
        val sandboxDir = sandboxRootfsDir(context, distribution)
        val sourceDir = rootfsDir(context, distribution)
        return runCatching {
            val dst = sandboxDir.absolutePath
            val src = sourceDir.absolutePath
            val garbage = File("/data/local/tmp/.garbage_reset_${System.currentTimeMillis()}").absolutePath
            // 异步脱钩瞬时重置：秒级移除沙盒，秒级克隆底包，后台静默销毁垃圾
            val cmd = """
                if [ -d '$dst' ]; then
                    mkdir -p '$garbage' 2>/dev/null
                    mv '$dst' '$garbage/' 2>/dev/null || rm -rf '$dst' 2>/dev/null
                fi
                mkdir -p '$dst' && \
                cp -al '$src/.' '$dst/' 2>/dev/null && \
                touch '$dst/$READY_MARKER' && \
                (rm -rf '$garbage' >/dev/null 2>&1 &) || true
            """.trimIndent()
            val process = ProcessBuilder("su", "-c", cmd).start()
            process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
            File(sandboxDir, READY_MARKER).isFile
        }.getOrDefault(false)
    }
}
