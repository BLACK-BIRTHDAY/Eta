package fuck.andes.agent.terminal

import android.content.Context
import fuck.andes.core.AndroidAgentLogger
import fuck.andes.core.safeLogType
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

internal enum class ApkAnalysisInstallStage(val displayName: String) {
    CHECKING("检查 Linux 基础环境"),
    DOWNLOADING("下载 APK 分析工具"),
    PREPARING("准备工具文件"),
    INSTALLING_JAVA("安装 OpenJDK 17"),
    ACTIVATING("启用 APK 分析工具"),
    VERIFYING("验证分析命令"),
    COMPLETE("APK 分析工具已就绪"),
}

internal data class ApkAnalysisInstallProgress(
    val stage: ApkAnalysisInstallStage,
    val artifactName: String? = null,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
)

internal sealed interface ApkAnalysisInstallResult {
    data object AlreadyReady : ApkAnalysisInstallResult
    data object EnvironmentNotReady : ApkAnalysisInstallResult
    data class InsufficientSpace(val requiredBytes: Long, val availableBytes: Long) : ApkAnalysisInstallResult
    data object Installed : ApkAnalysisInstallResult
    data class Failed(val stage: ApkAnalysisInstallStage) : ApkAnalysisInstallResult
}

/** 安装架构无关的 Java 分析工具；APK 资源回编译仍需单独的 ARM64 AAPT2 支持。 */
internal class AlpineApkAnalysisInstaller(
    private val context: Context,
    private val artifactDownloader: VerifiedArtifactDownloader = VerifiedArtifactDownloader(),
) {
    fun isReady(): Boolean =
        AlpineEnvironmentPaths.apkAnalysisReady(AlpineEnvironmentPaths.rootfsDir(context).absolutePath)

    suspend fun install(
        onProgress: suspend (ApkAnalysisInstallProgress) -> Unit = {},
    ): ApkAnalysisInstallResult {
        installMutex.lock()
        return try {
            installLocked(onProgress)
        } finally {
            installMutex.unlock()
        }
    }

    private suspend fun installLocked(
        onProgress: suspend (ApkAnalysisInstallProgress) -> Unit,
    ): ApkAnalysisInstallResult = withContext(Dispatchers.IO) {
        if (isReady()) return@withContext ApkAnalysisInstallResult.AlreadyReady
        val rootfs = AlpineEnvironmentPaths.rootfsDir(context)
        onProgress(ApkAnalysisInstallProgress(ApkAnalysisInstallStage.CHECKING))
        if (!AlpineEnvironmentPaths.commonToolsReady(rootfs.absolutePath)) {
            return@withContext ApkAnalysisInstallResult.EnvironmentNotReady
        }
        val availableBytes = rootfs.parentFile?.usableSpace ?: context.filesDir.usableSpace
        if (availableBytes < MIN_AVAILABLE_BYTES) {
            return@withContext ApkAnalysisInstallResult.InsufficientSpace(
                requiredBytes = MIN_AVAILABLE_BYTES,
                availableBytes = availableBytes,
            )
        }

        val downloadedArtifacts = linkedMapOf<VerifiedArtifact, File>()
        for (artifact in ARTIFACTS) {
            coroutineContext.ensureActive()
            val target = File(AlpineEnvironmentPaths.artifactDir(context), artifact.fileName)
            onProgress(
                ApkAnalysisInstallProgress(
                    stage = ApkAnalysisInstallStage.DOWNLOADING,
                    artifactName = artifact.id,
                    totalBytes = artifact.sizeBytes,
                ),
            )
            val downloaded = artifactDownloader.download(artifact, target) { downloadedBytes, totalBytes ->
                onProgress(
                    ApkAnalysisInstallProgress(
                        stage = ApkAnalysisInstallStage.DOWNLOADING,
                        artifactName = artifact.id,
                        downloadedBytes = downloadedBytes,
                        totalBytes = totalBytes,
                    ),
                )
            }
            if (!downloaded) {
                return@withContext ApkAnalysisInstallResult.Failed(ApkAnalysisInstallStage.DOWNLOADING)
            }
            downloadedArtifacts[artifact] = target
        }

        coroutineContext.ensureActive()
        onProgress(ApkAnalysisInstallProgress(ApkAnalysisInstallStage.PREPARING))
        val staging = AlpineEnvironmentPaths.profileStagingDir(context, PROFILE_ID)
        if (!prepareStaging(staging, downloadedArtifacts)) {
            return@withContext ApkAnalysisInstallResult.Failed(ApkAnalysisInstallStage.PREPARING)
        }

        coroutineContext.ensureActive()
        onProgress(ApkAnalysisInstallProgress(ApkAnalysisInstallStage.INSTALLING_JAVA))
        if (!installJava(rootfs)) {
            return@withContext ApkAnalysisInstallResult.Failed(ApkAnalysisInstallStage.INSTALLING_JAVA)
        }

        coroutineContext.ensureActive()
        onProgress(ApkAnalysisInstallProgress(ApkAnalysisInstallStage.ACTIVATING))
        if (!activateStaging(rootfs, staging)) {
            return@withContext ApkAnalysisInstallResult.Failed(ApkAnalysisInstallStage.ACTIVATING)
        }

        coroutineContext.ensureActive()
        onProgress(ApkAnalysisInstallProgress(ApkAnalysisInstallStage.VERIFYING))
        if (!verifyAndMark(rootfs)) {
            rollback(rootfs)
            return@withContext ApkAnalysisInstallResult.Failed(ApkAnalysisInstallStage.VERIFYING)
        }

        cleanupAfterSuccess(rootfs, downloadedArtifacts.values)
        onProgress(ApkAnalysisInstallProgress(ApkAnalysisInstallStage.COMPLETE))
        ApkAnalysisInstallResult.Installed
    }

    private fun prepareStaging(
        staging: File,
        artifacts: Map<VerifiedArtifact, File>,
    ): Boolean = try {
        staging.deleteRecursively()
        check(staging.mkdirs())
        val jadxArchive = artifacts.getValue(JADX_ARTIFACT)
        check(extractJadx(jadxArchive, staging))
        val libraryDir = File(staging, "lib").apply { check(mkdirs()) }
        artifacts.getValue(APKTOOL_ARTIFACT).copyTo(File(libraryDir, "apktool.jar"), overwrite = true)
        artifacts.getValue(SMALI_ARTIFACT).copyTo(File(libraryDir, "smali.jar"), overwrite = true)
        artifacts.getValue(BAKSMALI_ARTIFACT).copyTo(File(libraryDir, "baksmali.jar"), overwrite = true)
        val binDir = File(staging, "bin").apply { check(mkdirs()) }
        File(binDir, "java").writeText(JAVA_WRAPPER)
        File(binDir, "apktool").writeText(APKTOOL_WRAPPER)
        File(binDir, "smali").writeText(javaJarWrapper("smali.jar"))
        File(binDir, "baksmali").writeText(javaJarWrapper("baksmali.jar"))
        true
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (throwable: Throwable) {
        AndroidAgentLogger.warn(
            "APK analysis profile action=prepare outcome=failed errorType=${throwable.safeLogType()}",
        )
        staging.deleteRecursively()
        false
    }

    private fun extractJadx(archive: File, staging: File): Boolean {
        val targets = mapOf(
            "bin/jadx" to File(staging, "jadx/bin/jadx"),
            "lib/jadx-$JADX_VERSION-all.jar" to File(staging, "jadx/lib/jadx-$JADX_VERSION-all.jar"),
            "LICENSE" to File(staging, "licenses/jadx-LICENSE"),
        )
        val extracted = mutableSetOf<String>()
        var extractedBytes = 0L
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = targets[entry.name]
                if (target != null) {
                    check(!entry.isDirectory && extracted.add(entry.name))
                    target.parentFile?.mkdirs()
                    target.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            extractedBytes += count.toLong()
                            check(extractedBytes <= MAX_JADX_EXTRACTED_BYTES)
                            output.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        return extracted == targets.keys
    }

    private suspend fun installJava(rootfs: File): Boolean {
        val result = InstallerShellRunner.run(
            command = "apk add --no-cache openjdk17-jdk",
            timeoutSeconds = 900,
            environment = TerminalEnvironment.LINUX,
            linuxRootfsPath = rootfs.absolutePath,
        )
        AndroidAgentLogger.info(
            "APK analysis profile action=install_java " +
                "outcome=${if (result.exitCode == 0) "succeeded" else "failed"} " +
                "exitCode=${result.exitCode} outputChars=${result.output.length}",
        )
        return result.exitCode == 0
    }

    private suspend fun activateStaging(rootfs: File, staging: File): Boolean {
        val profileRoot = File(rootfs, "opt/eta/apk-analysis")
        val current = File(profileRoot, "current")
        val installing = File(profileRoot, "current.installing")
        val previous = File(profileRoot, "previous")
        val command = """
            ${AndroidBusyBox.discoveryScript()}
            [ -n "${'$'}eta_busybox" ] || exit 127
            "${'$'}eta_busybox" mkdir -p ${shellQuote(profileRoot.absolutePath)} || exit 70
            "${'$'}eta_busybox" rm -rf ${shellQuote(installing.absolutePath)} ${shellQuote(previous.absolutePath)}
            "${'$'}eta_busybox" mv ${shellQuote(staging.absolutePath)} ${shellQuote(installing.absolutePath)} || exit 71
            "${'$'}eta_busybox" chmod 0755 \
              ${shellQuote(File(installing, "jadx/bin/jadx").absolutePath)} \
              ${shellQuote(File(installing, "bin/java").absolutePath)} \
              ${shellQuote(File(installing, "bin/apktool").absolutePath)} \
              ${shellQuote(File(installing, "bin/smali").absolutePath)} \
              ${shellQuote(File(installing, "bin/baksmali").absolutePath)} || exit 72
            eta_link_commands() {
              "${'$'}eta_busybox" mkdir -p ${shellQuote(File(rootfs, "usr/local/bin").absolutePath)} || return 1
              for eta_command in java jadx apktool smali baksmali; do
                "${'$'}eta_busybox" rm -f ${shellQuote(File(rootfs, "usr/local/bin").absolutePath)}/"${'$'}eta_command"
              done
              "${'$'}eta_busybox" ln -s ../../../opt/eta/apk-analysis/current/bin/java \
                ${shellQuote(File(rootfs, "usr/local/bin/java").absolutePath)} || return 1
              "${'$'}eta_busybox" ln -s ../../../opt/eta/apk-analysis/current/jadx/bin/jadx \
                ${shellQuote(File(rootfs, "usr/local/bin/jadx").absolutePath)} || return 1
              for eta_command in apktool smali baksmali; do
                "${'$'}eta_busybox" ln -s ../../../opt/eta/apk-analysis/current/bin/"${'$'}eta_command" \
                  ${shellQuote(File(rootfs, "usr/local/bin").absolutePath)}/"${'$'}eta_command" || return 1
              done
            }
            eta_restore_previous() {
              "${'$'}eta_busybox" rm -rf ${shellQuote(current.absolutePath)}
              if [ -d ${shellQuote(previous.absolutePath)} ]; then
                "${'$'}eta_busybox" mv ${shellQuote(previous.absolutePath)} ${shellQuote(current.absolutePath)}
                eta_link_commands || true
              fi
            }
            if [ -d ${shellQuote(current.absolutePath)} ]; then
              "${'$'}eta_busybox" mv ${shellQuote(current.absolutePath)} ${shellQuote(previous.absolutePath)} || exit 73
            fi
            "${'$'}eta_busybox" mv ${shellQuote(installing.absolutePath)} ${shellQuote(current.absolutePath)} || {
              eta_restore_previous
              exit 74
            }
            eta_link_commands || {
              eta_restore_previous
              exit 76
            }
            "${'$'}eta_busybox" rm -f ${shellQuote(File(rootfs, AlpineEnvironmentPaths.APK_ANALYSIS_MARKER).absolutePath)}
        """.trimIndent()
        val result = InstallerShellRunner.run(
            command = command,
            timeoutSeconds = 60,
            environment = TerminalEnvironment.ANDROID,
        )
        AndroidAgentLogger.info(
            "APK analysis profile action=activate " +
                "outcome=${if (result.exitCode == 0) "succeeded" else "failed"} exitCode=${result.exitCode}",
        )
        return result.exitCode == 0
    }

    private suspend fun verifyAndMark(rootfs: File): Boolean {
        val command = """
            rm -f /${AlpineEnvironmentPaths.APK_ANALYSIS_MARKER}
            java -version >/dev/null 2>&1 || exit 81
            jadx --version >/dev/null 2>&1 || exit 82
            apktool --version >/dev/null 2>&1 || exit 83
            smali --version >/dev/null 2>&1 || exit 84
            baksmali --version >/dev/null 2>&1 || exit 85
            cat > /${AlpineEnvironmentPaths.APK_ANALYSIS_MARKER} <<'ETA_APK_ANALYSIS_EOF'
            profile=${AlpineEnvironmentPaths.APK_ANALYSIS_REVISION}
            jadx=$JADX_VERSION
            apktool=$APKTOOL_VERSION
            smali=$SMALI_VERSION
            ETA_APK_ANALYSIS_EOF
            chmod 0644 /${AlpineEnvironmentPaths.APK_ANALYSIS_MARKER} || exit 86
        """.trimIndent()
        val result = InstallerShellRunner.run(
            command = command,
            timeoutSeconds = 90,
            environment = TerminalEnvironment.LINUX,
            linuxRootfsPath = rootfs.absolutePath,
        )
        AndroidAgentLogger.info(
            "APK analysis profile action=verify " +
                "outcome=${if (result.exitCode == 0) "succeeded" else "failed"} " +
                "exitCode=${result.exitCode} outputChars=${result.output.length}",
        )
        return result.exitCode == 0
    }

    private suspend fun rollback(rootfs: File) {
        val profileRoot = File(rootfs, "opt/eta/apk-analysis")
        val current = File(profileRoot, "current")
        val previous = File(profileRoot, "previous")
        val command = """
            ${AndroidBusyBox.discoveryScript()}
            [ -n "${'$'}eta_busybox" ] || exit 127
            "${'$'}eta_busybox" rm -rf ${shellQuote(current.absolutePath)}
            "${'$'}eta_busybox" rm -f ${shellQuote(File(rootfs, AlpineEnvironmentPaths.APK_ANALYSIS_MARKER).absolutePath)}
            if [ -d ${shellQuote(previous.absolutePath)} ]; then
              "${'$'}eta_busybox" mv ${shellQuote(previous.absolutePath)} ${shellQuote(current.absolutePath)}
            fi
        """.trimIndent()
        InstallerShellRunner.run(command, 30, TerminalEnvironment.ANDROID)
    }

    private suspend fun cleanupAfterSuccess(rootfs: File, artifacts: Collection<File>) {
        artifacts.forEach(File::delete)
        val previous = File(rootfs, "opt/eta/apk-analysis/previous")
        val command = """
            ${AndroidBusyBox.discoveryScript()}
            [ -n "${'$'}eta_busybox" ] || exit 127
            "${'$'}eta_busybox" rm -rf ${shellQuote(previous.absolutePath)}
        """.trimIndent()
        InstallerShellRunner.run(command, 30, TerminalEnvironment.ANDROID)
    }

    companion object {
        private const val PROFILE_ID = "apk-analysis"
        private const val JADX_VERSION = "1.5.5"
        private const val APKTOOL_VERSION = "3.0.2"
        private const val SMALI_VERSION = "3.0.9"
        private const val MAX_JADX_EXTRACTED_BYTES = 80L * 1024L * 1024L
        internal const val MIN_AVAILABLE_BYTES = 768L * 1024L * 1024L

        private val installMutex = Mutex()
        private val GITHUB_PROXY_PREFIXES = listOf(
            "https://ghfast.top/",
            "https://gh-proxy.com/",
        )

        internal val JADX_ARTIFACT = githubReleaseArtifact(
            id = "jadx",
            version = JADX_VERSION,
            fileName = "jadx-$JADX_VERSION.zip",
            repository = "skylot/jadx",
            tag = "v$JADX_VERSION",
            sha256 = "38a5766d3c8170c41566b4b13ea0ede2430e3008421af4927235c2880234d51a",
            sizeBytes = 58_379_804L,
        )
        internal val APKTOOL_ARTIFACT = githubReleaseArtifact(
            id = "apktool",
            version = APKTOOL_VERSION,
            fileName = "apktool_$APKTOOL_VERSION.jar",
            repository = "iBotPeaches/Apktool",
            tag = "v$APKTOOL_VERSION",
            sha256 = "eee4669a704a14e0623407e6701b0b91887e61e1e4049cb7a82833e14ae8b5fd",
            sizeBytes = 15_476_804L,
        )
        internal val SMALI_ARTIFACT = githubReleaseArtifact(
            id = "smali",
            version = SMALI_VERSION,
            fileName = "smali-$SMALI_VERSION-fat-release.jar",
            repository = "baksmali/smali",
            tag = SMALI_VERSION,
            sha256 = "b5e2aa019c49ddaf7b2cace80f60ebbfd61f7ac18e7a3dea1075bec8779cc37a",
            sizeBytes = 5_301_841L,
        )
        internal val BAKSMALI_ARTIFACT = githubReleaseArtifact(
            id = "baksmali",
            version = SMALI_VERSION,
            fileName = "baksmali-$SMALI_VERSION-fat-release.jar",
            repository = "baksmali/smali",
            tag = SMALI_VERSION,
            sha256 = "1b206070701b2145bd1e6204abd6c14fea877f335486845cd4df149bf5f79c2e",
            sizeBytes = 4_723_147L,
        )
        internal val ARTIFACTS = listOf(
            JADX_ARTIFACT,
            APKTOOL_ARTIFACT,
            SMALI_ARTIFACT,
            BAKSMALI_ARTIFACT,
        )

        private fun githubReleaseArtifact(
            id: String,
            version: String,
            fileName: String,
            repository: String,
            tag: String,
            sha256: String,
            sizeBytes: Long,
        ): VerifiedArtifact {
            val officialUrl = "https://github.com/$repository/releases/download/$tag/$fileName"
            return VerifiedArtifact(
                id = id,
                version = version,
                fileName = fileName,
                url = officialUrl,
                sha256 = sha256,
                sizeBytes = sizeBytes,
                fallbackUrls = GITHUB_PROXY_PREFIXES.map { prefix -> prefix + officialUrl },
            )
        }

        internal val APKTOOL_WRAPPER = """
            #!/bin/sh
            case "${'$'}{1:-}" in
              b|build)
                echo "APKTOOL_BUILD_UNAVAILABLE: Eta APK 分析档案暂不包含 ARM64 AAPT2，仅支持解码与检查。" >&2
                exit 64
                ;;
            esac
            exec java -jar /opt/eta/apk-analysis/current/lib/apktool.jar "${'$'}@"
        """.trimIndent() + "\n"

        internal val JAVA_WRAPPER = """
            #!/bin/sh
            export JAVA_HOME=/usr/lib/jvm/default-jvm
            export LD_LIBRARY_PATH=/usr/lib/jvm/default-jvm/lib:/usr/lib/jvm/default-jvm/lib/server${'$'}{LD_LIBRARY_PATH:+:${'$'}LD_LIBRARY_PATH}
            exec /usr/lib/jvm/default-jvm/bin/java "${'$'}@"
        """.trimIndent() + "\n"

        private fun javaJarWrapper(fileName: String): String =
            "#!/bin/sh\nexec java -jar /opt/eta/apk-analysis/current/lib/$fileName \"${'$'}@\"\n"
    }
}
