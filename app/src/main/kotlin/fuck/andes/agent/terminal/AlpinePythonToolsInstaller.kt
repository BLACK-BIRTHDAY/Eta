package fuck.andes.agent.terminal

import android.content.Context
import fuck.andes.core.AndroidAgentLogger
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

internal enum class PythonToolsInstallStage {
    CHECKING,
    INSTALLING,
    VERIFYING,
    COMPLETE,
}

internal data class PythonToolsInstallProgress(
    val stage: PythonToolsInstallStage,
)

internal sealed interface PythonToolsInstallResult {
    data object AlreadyReady : PythonToolsInstallResult
    data object EnvironmentNotReady : PythonToolsInstallResult
    data object Installed : PythonToolsInstallResult
    data class Failed(val stage: PythonToolsInstallStage) : PythonToolsInstallResult
}

/** 按需安装 Python 工具链；验证通过才写完成标记，失败重试可重入。 */
internal class AlpinePythonToolsInstaller(
    private val context: Context,
) {
    fun isReady(): Boolean =
        AlpineEnvironmentPaths.pythonToolsReady(AlpineEnvironmentPaths.rootfsDir(context).absolutePath)

    suspend fun install(
        onProgress: suspend (PythonToolsInstallProgress) -> Unit = {},
    ): PythonToolsInstallResult {
        installMutex.lock()
        return try {
            installLocked(onProgress)
        } finally {
            installMutex.unlock()
        }
    }

    private suspend fun installLocked(
        onProgress: suspend (PythonToolsInstallProgress) -> Unit,
    ): PythonToolsInstallResult = withContext(Dispatchers.IO) {
        if (isReady()) return@withContext PythonToolsInstallResult.AlreadyReady
        val rootfs = AlpineEnvironmentPaths.rootfsDir(context)
        onProgress(PythonToolsInstallProgress(PythonToolsInstallStage.CHECKING))
        if (!AlpineEnvironmentPaths.commonToolsReady(rootfs.absolutePath)) {
            return@withContext PythonToolsInstallResult.EnvironmentNotReady
        }

        onProgress(PythonToolsInstallProgress(PythonToolsInstallStage.INSTALLING))
        val packages = PACKAGES.joinToString(" ")
        val installResult = InstallerShellRunner.run(
            command = """
                apk add --no-cache $packages
                ln -sf /usr/bin/python3 /usr/local/bin/python
            """.trimIndent(),
            timeoutSeconds = INSTALL_TIMEOUT_SECONDS,
            environment = TerminalEnvironment.LINUX,
            linuxRootfsPath = rootfs.absolutePath,
        )
        AndroidAgentLogger.info(
            "Python tools profile action=install " +
                "outcome=${if (installResult.exitCode == 0) "succeeded" else "failed"} " +
                "exitCode=${installResult.exitCode} outputChars=${installResult.output.length}",
        )
        if (installResult.exitCode != 0) {
            return@withContext PythonToolsInstallResult.Failed(PythonToolsInstallStage.INSTALLING)
        }

        onProgress(PythonToolsInstallProgress(PythonToolsInstallStage.VERIFYING))
        if (!verifyAndMark(rootfs)) {
            return@withContext PythonToolsInstallResult.Failed(PythonToolsInstallStage.VERIFYING)
        }

        onProgress(PythonToolsInstallProgress(PythonToolsInstallStage.COMPLETE))
        PythonToolsInstallResult.Installed
    }

    private suspend fun verifyAndMark(rootfs: File): Boolean {
        val command = """
            rm -f /${AlpineEnvironmentPaths.PYTHON_TOOLS_MARKER}
            python3 --version >/dev/null 2>&1 || exit 81
            uv --version >/dev/null 2>&1 || exit 82
            cat > /${AlpineEnvironmentPaths.PYTHON_TOOLS_MARKER} <<'ETA_PYTHON_TOOLS_EOF'
            profile=${AlpineEnvironmentPaths.PYTHON_TOOLS_REVISION}
            ETA_PYTHON_TOOLS_EOF
            chmod 0644 /${AlpineEnvironmentPaths.PYTHON_TOOLS_MARKER} || exit 86
        """.trimIndent()
        val result = InstallerShellRunner.run(
            command = command,
            timeoutSeconds = 60,
            environment = TerminalEnvironment.LINUX,
            linuxRootfsPath = rootfs.absolutePath,
        )
        AndroidAgentLogger.info(
            "Python tools profile action=verify " +
                "outcome=${if (result.exitCode == 0) "succeeded" else "failed"} " +
                "exitCode=${result.exitCode} outputChars=${result.output.length}",
        )
        return result.exitCode == 0
    }

    companion object {
        private const val INSTALL_TIMEOUT_SECONDS = 600L

        private val installMutex = Mutex()

        internal val PACKAGES = listOf(
            "pipx",
            "py3-pip",
            "py3-virtualenv",
            "python3",
            "ruff",
            "uv",
        )
    }
}
