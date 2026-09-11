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
}
