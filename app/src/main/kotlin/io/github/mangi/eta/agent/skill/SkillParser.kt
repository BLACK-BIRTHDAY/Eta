package io.github.mangi.eta.agent.skill

import java.io.File

/**
 * SKILL.md 解析器——从 YAML frontmatter + Markdown body 中提取结构化信息。
 *
 * 支持 `>` / `|` 多行块、缩进子块，以及普通的 `key: value` 行。
 * 纯字符串处理，不依赖外部 YAML 库。
 */
internal object SkillParser {

    /**
     * 读取并解析 [skillFile]，返回 frontmatter map + body string。
     * 文件不存在或不是文件时返回 null。
     */
    fun parseSkillFile(skillFile: File): ParsedSkillFile? {
        if (!skillFile.exists() || !skillFile.isFile) return null
        return parseSkillContent(skillFile.readText())
    }

    /**
     * 解析 SKILL.md 字符串内容，返回 frontmatter map + body string。
     */
    fun parseSkillContent(raw: String): ParsedSkillFile? {
        val trimmed = raw.trimStart()
        if (!trimmed.startsWith("---")) {
            return ParsedSkillFile(frontmatter = emptyMap(), body = raw.trim())
        }
        val firstLineEnd = trimmed.indexOf('\n')
        if (firstLineEnd < 0) {
            return ParsedSkillFile(frontmatter = emptyMap(), body = raw.trim())
        }
        val firstLine = trimmed.substring(0, firstLineEnd).trim()
        if (firstLine != "---") {
            return ParsedSkillFile(frontmatter = emptyMap(), body = raw.trim())
        }

        val afterFirstLine = trimmed.substring(firstLineEnd + 1)
        val closingRegex = Regex("""(?m)^---\s*$""")
        val match = closingRegex.find(afterFirstLine)
        if (match == null) {
            return ParsedSkillFile(frontmatter = emptyMap(), body = raw.trim())
        }

        val frontmatterCandidate = afterFirstLine.substring(0, match.range.first).trim('\r', '\n')
        val lines = frontmatterCandidate.lines()
        // 关键防御：若两段 --- 之间包含 Markdown 标题（例如 # ），或完全不含合法键值对，则说明这只是正文水平分割线而非 YAML frontmatter
        val hasMarkdownHeading = lines.any { it.trimStart().startsWith("#") }
        val parsedMap = parseSimpleFrontmatter(frontmatterCandidate)
        if (hasMarkdownHeading || (frontmatterCandidate.isNotBlank() && parsedMap.isEmpty())) {
            return ParsedSkillFile(frontmatter = emptyMap(), body = raw.trim())
        }

        val body = afterFirstLine.substring(match.range.last + 1).trim()
        return ParsedSkillFile(
            frontmatter = parsedMap,
            body = body,
        )
    }

    /**
     * 从 Markdown 正文中提取首个标题作为技能名称。
     */
    fun extractTitleFromMarkdown(markdown: String): String? {
        val headingRegex = Regex("""(?m)^#{1,6}\s+(.+)$""")
        val match = headingRegex.find(markdown) ?: return null
        val rawTitle = match.groupValues[1].trim()
        val cleaned = rawTitle.replace(Regex("""^[\p{So}\p{Sk}\p{Sm}\p{Sc}\p{C}\s\-_—·]+"""), "").trim()
        return cleaned.ifBlank { rawTitle }
    }

    /**
     * 从 Markdown 正文中提取首个有效段落作为描述摘要。
     */
    fun extractSummaryFromMarkdown(markdown: String, maxLength: Int = 120): String? {
        val lines = markdown.lines()
        val paragraph = lines
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("```") && !it.startsWith("---") }
            ?: return null
        val cleanText = paragraph.replace(Regex("""[*_`~]"""), "").trim()
        return if (cleanText.length > maxLength) {
            cleanText.take(maxLength) + "…"
        } else {
            cleanText
        }
    }

    /**
     * 将用户编辑的内容规范化为合法的 SKILL.md。
     * 自动补齐缺失的 frontmatter 或必要的 'name' / 'description' 字段。
     */
    fun ensureSkillFrontmatter(
        rawContent: String,
        fallbackName: String,
        fallbackDescription: String? = null,
    ): String {
        val parsed = parseSkillContent(rawContent) ?: ParsedSkillFile(emptyMap(), rawContent.trim())
        val existingFrontmatter = parsed.frontmatter.toMutableMap()

        val effectiveName = existingFrontmatter["name"]?.trim()?.takeIf { it.isNotBlank() }
            ?: extractTitleFromMarkdown(parsed.body)?.takeIf { it.isNotBlank() }
            ?: fallbackName.trim().ifBlank { "custom-skill" }
        existingFrontmatter["name"] = effectiveName

        val effectiveDesc = existingFrontmatter["description"]?.trim()?.takeIf { it.isNotBlank() }
            ?: fallbackDescription?.trim()?.takeIf { it.isNotBlank() }
            ?: extractSummaryFromMarkdown(parsed.body)?.takeIf { it.isNotBlank() }
            ?: effectiveName
        existingFrontmatter["description"] = effectiveDesc

        val body = parsed.body.ifBlank { rawContent.trim() }

        val frontmatterBuilder = StringBuilder()
        frontmatterBuilder.append("---\n")
        frontmatterBuilder.append("name: ").append(yamlEscapeIfNeeded(effectiveName)).append("\n")
        frontmatterBuilder.append("description: ").append(yamlEscapeIfNeeded(effectiveDesc)).append("\n")

        existingFrontmatter.forEach { (k, v) ->
            if (k != "name" && k != "description") {
                if (v.contains('\n')) {
                    frontmatterBuilder.append("$k: |\n")
                    v.lines().forEach { line ->
                        frontmatterBuilder.append("  ").append(line).append('\n')
                    }
                } else {
                    frontmatterBuilder.append("$k: ").append(yamlEscapeIfNeeded(v)).append("\n")
                }
            }
        }
        frontmatterBuilder.append("---\n\n")
        frontmatterBuilder.append(body.trim())
        return frontmatterBuilder.toString().trim()
    }

    private fun yamlEscapeIfNeeded(value: String): String {
        val trimmed = value.trim()
        val needsQuotes = trimmed.contains(':') ||
            trimmed.contains('#') ||
            trimmed.contains('\'') ||
            trimmed.contains('"') ||
            trimmed.contains('\n') ||
            trimmed.startsWith('@') ||
            trimmed.startsWith('`') ||
            trimmed.startsWith('%') ||
            trimmed.startsWith('&') ||
            trimmed.startsWith('*')
        return if (needsQuotes) {
            "\"" + trimmed.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        } else {
            trimmed
        }
    }

    /**
     * 简单 YAML frontmatter 解析。
     *
     * 支持：
     * - `key: value` 单行
     * - `key: >` 折叠多行块
     * - `key: |` 字面多行块
     * - `key:` 后跟缩进子块
     */
    fun parseSimpleFrontmatter(frontmatter: String): Map<String, String> {
        if (frontmatter.isBlank()) return emptyMap()
        val lines = frontmatter.lines()
        val result = linkedMapOf<String, String>()
        var index = 0
        while (index < lines.size) {
            val rawLine = lines[index]
            if (rawLine.isBlank()) {
                index += 1
                continue
            }
            val keyMatch = Regex("^([A-Za-z0-9_-]+):\\s*(.*)$").find(rawLine)
            if (keyMatch == null) {
                index += 1
                continue
            }
            val key = keyMatch.groupValues[1]
            val value = keyMatch.groupValues[2]
            if (YAML_BLOCK_SCALAR.matches(value)) {
                val literal = value.startsWith('|')
                val blockLines = mutableListOf<String>()
                index += 1
                while (index < lines.size && (lines[index].startsWith("  ") || lines[index].isBlank())) {
                    val next = lines[index]
                    blockLines += if (next.isBlank()) "" else next.trim()
                    index += 1
                }
                result[key] = if (literal) {
                    blockLines.joinToString("\n").trim()
                } else {
                    foldYamlLines(blockLines)
                }
                continue
            }
            if (value.isBlank()) {
                val builder = StringBuilder()
                index += 1
                while (index < lines.size && (lines[index].startsWith("  ") || lines[index].startsWith("\t"))) {
                    if (builder.isNotEmpty()) builder.append('\n')
                    builder.append(lines[index].trimEnd())
                    index += 1
                }
                result[key] = builder.toString().trim()
                continue
            }
            result[key] = unquoteScalar(value)
            index += 1
        }
        return result
    }

    /** 支持 YAML 常见的单双引号标量；复杂转义仍交由 Skill 作者避免使用。 */
    private fun unquoteScalar(raw: String): String {
        val value = raw.trim()
        if (value.length < 2) return value
        val quoted = (value.first() == '"' && value.last() == '"') ||
            (value.first() == '\'' && value.last() == '\'')
        return if (quoted) value.substring(1, value.lastIndex) else value
    }

    /** `>` 折叠换行、保留空行形成的段落；尾部 chomp 对元数据没有语义差异。 */
    private fun foldYamlLines(lines: List<String>): String = buildString {
        var pendingBlankLines = 0
        lines.forEach { line ->
            if (line.isBlank()) {
                pendingBlankLines += 1
            } else {
                if (isNotEmpty()) {
                    if (pendingBlankLines == 0) append(' ')
                    else repeat(pendingBlankLines + 1) { append('\n') }
                }
                append(line)
                pendingBlankLines = 0
            }
        }
    }.trim()

    /**
     * 解析缩进子块为 key-value map（用于 metadata 字段）。
     */
    fun parseIndentedBlock(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.lines().mapNotNull { line ->
            val match = Regex("^\\s*([A-Za-z0-9_.-]+):\\s*(.*)$").find(line) ?: return@mapNotNull null
            match.groupValues[1] to match.groupValues[2].trim().trim('"')
        }.toMap()
    }

    /**
     * 从目录名和 frontmatter name 生成规范化的 skill id。
     */
    fun sanitizeSkillId(directoryName: String, frontmatterName: String?): String {
        val candidate = frontmatterName?.trim().takeUnless { it.isNullOrBlank() } ?: directoryName
        return candidate.lowercase()
            .replace(Regex("[^a-z0-9-]+"), "-")
            .trim('-')
            .ifBlank { directoryName.lowercase() }
    }

    /**
     * 规范化查找字符串——用于 id/name/path 匹配。
     */
    fun normalizeSkillLookup(value: String): String =
        value.trim()
            .lowercase()
            .replace('\\', '/')
            .removeSuffix("/skill.md")
            .removeSuffix("/")
            .replace(Regex("\\s+"), "")
            .replace("-", "")
            .replace("_", "")

    private val YAML_BLOCK_SCALAR = Regex("[>|][+-]?")

}
