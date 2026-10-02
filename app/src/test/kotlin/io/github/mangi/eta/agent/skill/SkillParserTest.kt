package io.github.mangi.eta.agent.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillParserTest {

    @Test
    fun parseSkillContentWithValidFrontmatter() {
        val raw = """
            ---
            name: my-sample-skill
            description: A test skill for testing parser.
            ---

            # Heading 1
            This is the body text.
        """.trimIndent()

        val parsed = SkillParser.parseSkillContent(raw)
        assertNotNull(parsed)
        assertEquals("my-sample-skill", parsed?.frontmatter?.get("name"))
        assertEquals("A test skill for testing parser.", parsed?.frontmatter?.get("description"))
        assertTrue(parsed?.body?.contains("# Heading 1") == true)
        assertTrue(parsed?.body?.contains("This is the body text.") == true)
    }

    @Test
    fun parseSkillContentWithoutFrontmatter() {
        val raw = """
            # Just Markdown
            No YAML frontmatter here.
        """.trimIndent()

        val parsed = SkillParser.parseSkillContent(raw)
        assertNotNull(parsed)
        assertTrue(parsed?.frontmatter?.isEmpty() == true)
        assertEquals(raw.trim(), parsed?.body)
    }

    @Test
    fun parseSkillContentWithFoldedScalar() {
        val raw = """
            ---
            name: folded-skill
            description: >
              This is a long description
              that spans multiple lines
              folded together.
            ---
            Body
        """.trimIndent()

        val parsed = SkillParser.parseSkillContent(raw)
        assertNotNull(parsed)
        assertEquals("folded-skill", parsed?.frontmatter?.get("name"))
        assertEquals(
            "This is a long description that spans multiple lines folded together.",
            parsed?.frontmatter?.get("description")
        )
    }

    @Test
    fun parseSkillContentWithLeadingHorizontalRuleAndMarkdownHeadings() {
        val raw = """
            ---

            ## 🔬 个股深度穿透标准工作流 (Single Stock Deep-Dive Workflow)

            **凡是用户要求分析日股个股，直接在对话中分析与回复：**
            ```bash
            cd /storage/emulated/0/Download/jpx_quant && python3 analyze_stock.py <代码或Ticker>
            ```

            ---

            ## 🛠 全量运维与守护进程管理
        """.trimIndent()

        // 验证：不应将两个 --- 之间的 markdown 正文误判为 yaml frontmatter
        val parsed = SkillParser.parseSkillContent(raw)
        assertNotNull(parsed)
        assertTrue(parsed?.frontmatter?.isEmpty() == true)
        assertTrue(parsed?.body?.contains("个股深度穿透标准工作流") == true)
        assertTrue(parsed?.body?.contains("全量运维与守护进程管理") == true)

        // 验证标题提取
        val extractedTitle = SkillParser.extractTitleFromMarkdown(parsed?.body.orEmpty())
        assertEquals("个股深度穿透标准工作流 (Single Stock Deep-Dive Workflow)", extractedTitle)

        // 验证自动补齐规范化的 frontmatter
        val normalized = SkillParser.ensureSkillFrontmatter(raw, fallbackName = "jpx-stock")
        val parsedNormalized = SkillParser.parseSkillContent(normalized)
        assertNotNull(parsedNormalized)
        assertEquals("个股深度穿透标准工作流 (Single Stock Deep-Dive Workflow)", parsedNormalized?.frontmatter?.get("name"))
        assertTrue(parsedNormalized?.frontmatter?.get("description")?.isNotBlank() == true)
        assertTrue(parsedNormalized?.body?.contains("个股深度穿透标准工作流") == true)
        assertTrue(parsedNormalized?.body?.contains("全量运维与守护进程管理") == true)
    }

    @Test
    fun ensureSkillFrontmatterWithMissingNameAutoCompletes() {
        val raw = """
            ---
            description: 这是一个只有描述没有名字的技能
            ---

            # 自动提取的主标题
            正文内容在此。
        """.trimIndent()

        val normalized = SkillParser.ensureSkillFrontmatter(raw, fallbackName = "fallback-id")
        val parsed = SkillParser.parseSkillContent(normalized)
        assertNotNull(parsed)
        assertEquals("自动提取的主标题", parsed?.frontmatter?.get("name"))
        assertEquals("这是一个只有描述没有名字的技能", parsed?.frontmatter?.get("description"))
        assertTrue(parsed?.body?.contains("正文内容在此。") == true)
    }
}
