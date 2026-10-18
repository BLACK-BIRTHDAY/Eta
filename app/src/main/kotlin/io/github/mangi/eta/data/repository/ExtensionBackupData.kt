package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.agent.mcp.McpStdioClient
import io.github.mangi.eta.agent.mcp.validateMcpEndpoint
import io.github.mangi.eta.agent.skill.BUILTIN_SKILL_SOURCE
import io.github.mangi.eta.agent.skill.SkillParser
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.agent.skill.USER_SKILL_SOURCE
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.db.McpServerEntity
import io.github.mangi.eta.data.db.SkillRegistryEntity
import io.github.mangi.eta.data.model.McpAuthorizationType
import io.github.mangi.eta.data.model.McpProtocolMode
import io.github.mangi.eta.data.model.McpStdioEnvironment
import io.github.mangi.eta.data.model.McpTransportType
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.text.Normalizer
import java.util.Base64
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.Serializable
import org.json.JSONArray

@Serializable
internal data class McpBackupServer(
    val server: McpServerEntity,
    val bearerToken: String? = null,
    val envVars: Map<String, String> = emptyMap(),
)

@Serializable
internal data class SkillBackupData(
    val registry: List<SkillRegistryEntity> = emptyList(),
    val packages: List<SkillBackupPackage> = emptyList(),
)

@Serializable
internal data class SkillBackupPackage(
    val skillId: String,
    val files: List<SkillBackupFile> = emptyList(),
)

@Serializable
internal data class SkillBackupFile(
    val relativePath: String,
    val contentBase64: String,
)

internal object McpBackupTransfer {
    suspend fun snapshot(context: Context): List<McpBackupServer> {
        val secretStore = McpSecretStore(context)
        val servers = EtaDatabase.get(context).mcpServerDao().servers()
        return servers.map { server ->
            McpBackupServer(
                server = server,
                bearerToken = secretStore.bearerToken(server.id),
                envVars = secretStore.envVars(server.id),
            )
        }
    }

    fun validate(servers: List<McpBackupServer>) {
        val ids = servers.map { it.server.id }
        require(ids.size == ids.toSet().size) { "备份中的 MCP 服务器 ID 重复" }
        require(ids.all { it.isNotBlank() && it.length <= 128 }) { "备份中的 MCP 服务器 ID 无效" }
        for (backup in servers) {
            val server = backup.server
            require(server.name.isNotBlank() && server.name.length <= 256) {
                "备份中的 MCP 服务器名称无效: ${server.name}"
            }
            require(server.transportType in setOf(McpTransportType.HTTP, McpTransportType.STDIO)) {
                "备份中的 MCP 传输协议无效: ${server.transportType}"
            }
            require(
                server.protocolMode in setOf(
                    McpProtocolMode.AUTO,
                    McpProtocolMode.LATEST,
                    McpProtocolMode.LEGACY,
                )
            ) {
                "备份中的 MCP 协议版本模式无效: ${server.protocolMode}"
            }
            require(
                server.authorizationType in setOf(
                    McpAuthorizationType.NONE,
                    McpAuthorizationType.BEARER,
                )
            ) {
                "备份中的 MCP 授权方式无效: ${server.authorizationType}"
            }
            if (server.transportType == McpTransportType.HTTP) {
                runCatching { validateMcpEndpoint(server.url) }
                    .getOrElse { throw IllegalArgumentException("备份中的 MCP HTTP 端点无效: ${server.url}", it) }
            } else {
                runCatching { McpStdioClient.validateStdioCommand(server.command) }
                    .getOrElse { throw IllegalArgumentException("备份中的 MCP STDIO 命令无效: ${server.command}", it) }
                require(
                    server.stdioEnvironment in setOf(
                        McpStdioEnvironment.LINUX,
                        McpStdioEnvironment.ANDROID_ROOT,
                        McpStdioEnvironment.ANDROID_USER,
                    )
                ) {
                    "备份中的 MCP STDIO 环境无效: ${server.stdioEnvironment}"
                }
                require('\u0000' !in server.cwd) { "备份中的 MCP STDIO 工作目录包含非法空字符" }
            }
            require(backup.envVars.keys.all { it.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")) }) {
                "备份中的 MCP 环境变量名不合法"
            }
            require(runCatching { JSONArray(server.toolsJson) }.isSuccess) { "备份中的 MCP 工具列表格式无效" }
            require(runCatching { JSONArray(server.enabledToolNamesJson) }.isSuccess) {
                "备份中的 MCP 启用工具列表格式无效"
            }
            require(runCatching { JSONArray(server.argsJson) }.isSuccess) { "备份中的 MCP 启动参数格式无效" }
        }
    }

    fun captureSecrets(
        context: Context,
        ids: Set<String>,
    ): Map<String, Pair<String?, Map<String, String>>> {
        val secretStore = McpSecretStore(context)
        return ids.associateWith { id ->
            secretStore.bearerToken(id) to secretStore.envVars(id)
        }
    }

    fun restoreSecrets(
        context: Context,
        previousIds: Set<String>,
        servers: List<McpBackupServer>,
    ) {
        val secretStore = McpSecretStore(context)
        previousIds.forEach { secretStore.clear(it) }
        servers.forEach { backupServer ->
            val server = backupServer.server
            secretStore.clear(server.id)
            if (server.authorizationType == McpAuthorizationType.BEARER && !backupServer.bearerToken.isNullOrBlank()) {
                secretStore.setBearerToken(server.id, backupServer.bearerToken)
            }
            if (backupServer.envVars.isNotEmpty()) {
                secretStore.setEnvVars(server.id, backupServer.envVars)
            }
        }
    }

    fun rollbackSecrets(
        context: Context,
        currentIds: Set<String>,
        snapshot: Map<String, Pair<String?, Map<String, String>>>,
        failure: Throwable,
    ) {
        val secretStore = McpSecretStore(context)
        currentIds.forEach { id ->
            runCatching { secretStore.clear(id) }.onFailure { failure.addSuppressed(it) }
        }
        snapshot.forEach { (id, secrets) ->
            val (token, env) = secrets
            runCatching {
                secretStore.clear(id)
                if (!token.isNullOrBlank()) {
                    secretStore.setBearerToken(id, token)
                }
                if (env.isNotEmpty()) {
                    secretStore.setEnvVars(id, env)
                }
            }.onFailure { failure.addSuppressed(it) }
        }
    }
}

internal class StagedSkillSwap(
    private val context: Context,
    private val stagingDir: File,
    private val backupDir: File,
    private val skillsRoot: File,
) : AutoCloseable {
    private var committed = false

    fun commit() {
        committed = true
        deleteDirectoryRecursively(backupDir)
        deleteDirectoryRecursively(stagingDir)
        SkillRuntime.createIndexService(context).listSkillsForManagement(forceRefresh = true)
    }

    fun rollback(failure: Throwable) {
        if (committed) return
        try {
            SkillRuntime.createIndexService(context).withMutationLock {
                deleteDirectoryRecursively(skillsRoot)
                if (backupDir.exists()) {
                    copyDirectoryRecursively(backupDir, skillsRoot)
                }
                SkillRuntime.createIndexService(context).listSkillsForManagement(forceRefresh = true)
            }
        } catch (restoreFailure: Throwable) {
            failure.addSuppressed(restoreFailure)
        } finally {
            deleteDirectoryRecursively(backupDir)
            deleteDirectoryRecursively(stagingDir)
        }
    }

    override fun close() {
        if (!committed) {
            deleteDirectoryRecursively(stagingDir)
            deleteDirectoryRecursively(backupDir)
        }
    }
}

internal object SkillBackupTransfer {
    const val MAX_SKILL_ID_LENGTH = 64
    const val MAX_SKILL_FILE_BYTES = 512L * 1024L
    const val MAX_SINGLE_FILE_BYTES = 32L * 1024L * 1024L
    const val MAX_EXTRACTED_BYTES = 128L * 1024L * 1024L
    const val MAX_PATH_DEPTH = 16
    const val MAX_PATH_SEGMENT_LENGTH = 255
    val SKILL_ID_REGEX = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
    val WINDOWS_DRIVE_PREFIX = Regex("^[A-Za-z]:")

    suspend fun snapshot(context: Context): SkillBackupData {
        val indexService = SkillRuntime.createIndexService(context)
        indexService.seedBuiltinSkillsIfNeeded()
        val registry = EtaDatabase.get(context).skillDao().registryEntries()
        return indexService.withMutationLock {
            val skillsRoot = SkillRuntime.skillsRoot(context)
            if (!skillsRoot.exists() || !skillsRoot.isDirectory) {
                return@withMutationLock SkillBackupData(registry = registry, packages = emptyList())
            }
            val packages = skillsRoot.listFiles().orEmpty()
                .filter { it.isDirectory && !Files.isSymbolicLink(it.toPath()) }
                .filter { it.name.length in 1..MAX_SKILL_ID_LENGTH && SKILL_ID_REGEX.matches(it.name) }
                .filter {
                    val skillMd = File(it, "SKILL.md")
                    skillMd.isFile && !Files.isSymbolicLink(skillMd.toPath())
                }
                .map { dir ->
                    val files = dir.walkTopDown()
                        .onEnter { !Files.isSymbolicLink(it.toPath()) }
                        .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
                        .map { file ->
                            val relPath = file.relativeTo(dir).invariantSeparatorsPath
                            SkillBackupFile(
                                relativePath = relPath,
                                contentBase64 = Base64.getEncoder().encodeToString(file.readBytes()),
                            )
                        }
                        .sortedBy { it.relativePath }
                        .toList()
                    SkillBackupPackage(skillId = dir.name, files = files)
                }
                .sortedBy { it.skillId }
                .toList()
            SkillBackupData(registry = registry, packages = packages)
        }
    }

    fun validate(data: SkillBackupData) {
        val regIds = data.registry.map { it.skillId }
        require(regIds.size == regIds.toSet().size) { "备份中的 Skill 注册表 ID 重复" }
        require(regIds.all { it.length in 1..MAX_SKILL_ID_LENGTH && SKILL_ID_REGEX.matches(it) }) {
            "备份中的 Skill 注册表 ID 无效"
        }
        data.registry.forEach { entry ->
            require(entry.source in setOf(BUILTIN_SKILL_SOURCE, USER_SKILL_SOURCE)) {
                "备份中的 Skill 来源无效: ${entry.source}"
            }
            require(entry.installState in setOf("installed", "removed_builtin")) {
                "备份中的 Skill 安装状态无效: ${entry.installState}"
            }
            if (entry.source == USER_SKILL_SOURCE) {
                require(entry.installState == "installed") {
                    "用户 Skill 的安装状态必须为 installed"
                }
            }
        }
        val pkgIds = data.packages.map { it.skillId }
        require(pkgIds.size == pkgIds.toSet().size) { "备份中的 Skill 安装包 ID 重复" }
        require(pkgIds.all { it.length in 1..MAX_SKILL_ID_LENGTH && SKILL_ID_REGEX.matches(it) }) {
            "备份中的 Skill 安装包 ID 无效"
        }
        var totalExtractedBytes = 0L
        for (pkg in data.packages) {
            require(pkg.files.any { it.relativePath == "SKILL.md" }) {
                "Skill ${pkg.skillId} 缺少 SKILL.md"
            }
            val fileCollisionKeys = mutableSetOf<String>()
            val dirCollisionKeys = mutableSetOf<String>()
            for (file in pkg.files) {
                val rawPath = file.relativePath
                require(
                    rawPath.isNotBlank() &&
                        !rawPath.startsWith('/') &&
                        !rawPath.startsWith('\\') &&
                        !WINDOWS_DRIVE_PREFIX.containsMatchIn(rawPath) &&
                        '\\' !in rawPath &&
                        '\u0000' !in rawPath &&
                        rawPath.none { it.isISOControl() }
                ) {
                    "Skill 文件相对路径不安全: $rawPath"
                }
                val segments = rawPath.split('/')
                require(segments.size <= MAX_PATH_DEPTH) {
                    "Skill 文件路径层级超过 $MAX_PATH_DEPTH 层: $rawPath"
                }
                require(
                    segments.none { segment ->
                        segment.isBlank() || segment == "." || segment == ".." || segment.length > MAX_PATH_SEGMENT_LENGTH
                    }
                ) {
                    "Skill 文件路径包含非法段: $rawPath"
                }
                val collisionKey = collisionKey(rawPath)
                require(fileCollisionKeys.add(collisionKey)) {
                    "Skill 包含重复文件: $rawPath"
                }
                val ancestorKeys = segments.indices.drop(1).map { index ->
                    collisionKey(segments.take(index).joinToString("/"))
                }
                require(ancestorKeys.none { it in fileCollisionKeys }) {
                    "Skill 文件与目录冲突: $rawPath"
                }
                ancestorKeys.forEach { dirCollisionKeys.add(it) }
            }
            require(fileCollisionKeys.none { it in dirCollisionKeys }) {
                "Skill 文件与目录冲突: ${pkg.skillId}"
            }
            for (file in pkg.files) {
                val bytes = runCatching { Base64.getDecoder().decode(file.contentBase64) }
                    .getOrElse { throw IllegalArgumentException("Skill 文件 Base64 解码失败: ${file.relativePath}", it) }
                require(bytes.size <= MAX_SINGLE_FILE_BYTES) {
                    "Skill 单个文件超过 32 MiB 限制: ${file.relativePath}"
                }
                totalExtractedBytes += bytes.size
                require(totalExtractedBytes <= MAX_EXTRACTED_BYTES) {
                    "Skill 总文件大小超过 128 MiB 限制"
                }
                if (file.relativePath == "SKILL.md") {
                    require(bytes.size <= MAX_SKILL_FILE_BYTES) {
                        "SKILL.md 超过 512 KiB 限制: ${pkg.skillId}"
                    }
                    val utf8Text = runCatching {
                        val decoder = Charsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                        decoder.decode(ByteBuffer.wrap(bytes)).toString()
                    }.getOrElse { throw IllegalArgumentException("SKILL.md 必须是有效 UTF-8 文本: ${pkg.skillId}", it) }
                    val parsed = SkillParser.parseSkillContent(utf8Text)
                    val skillName = parsed?.frontmatter?.get("name")?.trim().orEmpty()
                    require(skillName.isNotBlank()) {
                        "SKILL.md 缺少有效的 name: ${pkg.skillId}"
                    }
                }
            }
        }
    }

    fun stageAndSwapDirectories(context: Context, data: SkillBackupData): StagedSkillSwap {
        val skillsRoot = SkillRuntime.skillsRoot(context)
        val stagingDir = File(context.cacheDir, "eta_backup_skills_stage_${UUID.randomUUID()}")
        val backupDir = File(context.cacheDir, "eta_backup_skills_backup_${UUID.randomUUID()}")
        if (!stagingDir.mkdirs()) {
            throw IOException("无法创建 Skill 恢复暂存目录")
        }
        try {
            for (pkg in data.packages) {
                val pkgDir = File(stagingDir, pkg.skillId)
                if (!pkgDir.mkdirs() && !pkgDir.isDirectory) {
                    throw IOException("无法创建 Skill 包暂存目录: ${pkg.skillId}")
                }
                for (file in pkg.files) {
                    val targetFile = File(pkgDir, file.relativePath)
                    targetFile.parentFile?.let { parent ->
                        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
                            throw IOException("无法创建 Skill 目标文件父目录: ${parent.absolutePath}")
                        }
                    }
                    val bytes = Base64.getDecoder().decode(file.contentBase64)
                    targetFile.writeBytes(bytes)
                }
            }

            SkillRuntime.createIndexService(context).withMutationLock {
                if (skillsRoot.exists()) {
                    if (!backupDir.mkdirs() && !backupDir.isDirectory) {
                        throw IOException("无法创建 Skill 备份目录")
                    }
                    copyDirectoryRecursively(skillsRoot, backupDir)
                    deleteDirectoryRecursively(skillsRoot)
                }
                if (!skillsRoot.exists() && !skillsRoot.mkdirs() && !skillsRoot.isDirectory) {
                    throw IOException("无法创建 Skills 目录")
                }
                copyDirectoryRecursively(stagingDir, skillsRoot)
                SkillRuntime.createIndexService(context).listSkillsForManagement(forceRefresh = true)
            }
            return StagedSkillSwap(context, stagingDir, backupDir, skillsRoot)
        } catch (failure: Throwable) {
            deleteDirectoryRecursively(stagingDir)
            deleteDirectoryRecursively(backupDir)
            throw failure
        }
    }

    private fun collisionKey(path: String): String = Normalizer
        .normalize(path, Normalizer.Form.NFC)
        .lowercase(Locale.ROOT)
}

private fun copyDirectoryRecursively(source: File, target: File) {
    if (!source.exists() || !source.isDirectory) return
    if (!target.exists() && !target.mkdirs() && !target.isDirectory) {
        throw IOException("无法创建目录: ${target.absolutePath}")
    }
    source.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }.forEach { file ->
        if (!Files.isSymbolicLink(file.toPath())) {
            val relative = file.relativeTo(source)
            val dest = File(target, relative.path)
            if (file.isDirectory) {
                if (!dest.exists() && !dest.mkdirs() && !dest.isDirectory) {
                    throw IOException("无法创建目录: ${dest.absolutePath}")
                }
            } else if (file.isFile) {
                dest.parentFile?.let { parent ->
                    if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
                        throw IOException("无法创建目录: ${parent.absolutePath}")
                    }
                }
                file.copyTo(dest, overwrite = true)
            }
        }
    }
}

private fun deleteDirectoryRecursively(directory: File) {
    if (!directory.exists()) return
    directory.walkBottomUp().forEach { file ->
        Files.deleteIfExists(file.toPath())
    }
}
