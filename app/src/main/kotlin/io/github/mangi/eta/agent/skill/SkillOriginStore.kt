package io.github.mangi.eta.agent.skill

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

/**
 * 负责读写用户技能目录下的 [.eta-skill-origin.json] 旁路文件，
 * 并检测技能的 [SKILL.md] 是否在本地被用户修改。
 */
internal object SkillOriginStore {
    const val ORIGIN_FILE_NAME = ".eta-skill-origin.json"
    private const val SKILL_FILE_NAME = "SKILL.md"

    fun read(skillDir: File): SkillOrigin? {
        val dirPath = skillDir.toPath()
        if (!skillDir.isDirectory || Files.isSymbolicLink(dirPath)) {
            return null
        }
        val originFile = File(skillDir, ORIGIN_FILE_NAME)
        val originPath = originFile.toPath()
        if (!Files.exists(originPath, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(originPath)) {
            return null
        }
        return runCatching {
            val text = originFile.readText(Charsets.UTF_8)
            val json = JSONObject(text)
            val repository = json.optString("repository").trim()
            val trackedRef = json.optString("trackedRef").trim()
            val skillPath = json.optString("skillPath").trim()
            val commitSha = json.optString("commitSha").trim()
            val treeSha = json.optString("treeSha").trim()
            val skillMdSha256 = json.optString("skillMdSha256").trim().lowercase()
            if (repository.isBlank() || trackedRef.isBlank() || skillPath.isBlank() || commitSha.isBlank()) {
                null
            } else {
                SkillOrigin(
                    repository = repository,
                    trackedRef = trackedRef,
                    skillPath = skillPath,
                    commitSha = commitSha,
                    treeSha = treeSha,
                    skillMdSha256 = skillMdSha256,
                )
            }
        }.getOrNull()
    }

    fun write(skillDir: File, origin: SkillOrigin) {
        val dirPath = skillDir.toPath()
        if (Files.isSymbolicLink(dirPath)) {
            throw IOException("Skill 目录是符号链接，禁止写入 origin 旁路文件：${skillDir.absolutePath}")
        }
        if (!skillDir.exists() && !skillDir.mkdirs()) {
            throw IOException("无法创建 Skill 目录：${skillDir.absolutePath}")
        }
        val originFile = File(skillDir, ORIGIN_FILE_NAME)
        val originPath = originFile.toPath()
        if (Files.exists(originPath, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(originPath)) {
            throw IOException("Origin 文件是符号链接，禁止覆盖：${originFile.absolutePath}")
        }
        val json = JSONObject().apply {
            put("repository", origin.repository)
            put("trackedRef", origin.trackedRef)
            put("skillPath", origin.skillPath)
            put("commitSha", origin.commitSha)
            put("treeSha", origin.treeSha)
            put("skillMdSha256", origin.skillMdSha256)
        }
        val tempFile = File(skillDir, "$ORIGIN_FILE_NAME.tmp-${UUID.randomUUID()}")
        try {
            tempFile.writeText(json.toString(2) + "\n", Charsets.UTF_8)
            try {
                Files.move(
                    tempFile.toPath(),
                    originPath,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: IOException) {
                Files.move(
                    tempFile.toPath(),
                    originPath,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    fun sha256Hex(file: File): String? {
        val path = file.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(path) ||
            !file.isFile
        ) {
            return null
        }
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = file.readBytes()
            val hash = digest.digest(bytes)
            hash.joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    fun computeSkillMdSha256(skillDir: File): String? {
        val dirPath = skillDir.toPath()
        if (Files.isSymbolicLink(dirPath)) return null
        return sha256Hex(File(skillDir, SKILL_FILE_NAME))
    }

    fun isLocallyModified(skillDir: File, origin: SkillOrigin): Boolean {
        if (origin.skillMdSha256.isBlank()) return false
        val currentSha = computeSkillMdSha256(skillDir)
        return currentSha == null || !currentSha.equals(origin.skillMdSha256, ignoreCase = true)
    }
}
