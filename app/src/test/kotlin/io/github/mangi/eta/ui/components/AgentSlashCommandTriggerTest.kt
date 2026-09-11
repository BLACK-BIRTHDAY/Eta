package io.github.mangi.eta.ui.components

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange
import io.github.mangi.eta.ui.model.SkillItemUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSlashCommandTriggerTest {

    private val sampleSkills = listOf(
        SkillItemUi(
            id = "web-search",
            name = "Web Search",
            description = "Search the web for information",
            source = "builtin",
            enabled = true,
            installed = true,
            capabilities = listOf("search"),
        ),
        SkillItemUi(
            id = "code-review",
            name = "Code Review",
            description = "Review code changes for quality",
            source = "builtin",
            enabled = true,
            installed = true,
            capabilities = listOf("review"),
        ),
        SkillItemUi(
            id = "disabled-skill",
            name = "Disabled Skill",
            description = "This is disabled",
            source = "user",
            enabled = false,
            installed = true,
            capabilities = emptyList(),
        ),
    )

    @Test
    fun testDetectSlashTriggerAtStart() {
        val trigger = detectSlashCommandTrigger("/", TextRange(1))
        assertNotNull(trigger)
        assertEquals("", trigger?.query)
        assertEquals(0, trigger?.tokenStartIndex)
        assertEquals(1, trigger?.tokenEndIndex)
    }

    @Test
    fun testDetectSlashTriggerWithQuery() {
        val trigger = detectSlashCommandTrigger("/web", TextRange(4))
        assertNotNull(trigger)
        assertEquals("web", trigger?.query)
        assertEquals(0, trigger?.tokenStartIndex)
        assertEquals(4, trigger?.tokenEndIndex)
    }

    @Test
    fun testDetectSlashTriggerAfterWhitespace() {
        val trigger = detectSlashCommandTrigger("Please /code", TextRange(12))
        assertNotNull(trigger)
        assertEquals("code", trigger?.query)
        assertEquals(7, trigger?.tokenStartIndex)
        assertEquals(12, trigger?.tokenEndIndex)
    }

    @Test
    fun testDetectSlashTriggerIgnoresUrl() {
        val trigger = detectSlashCommandTrigger("https://example.com/test", TextRange(24))
        assertNull(trigger)
    }

    @Test
    fun testDetectSlashTriggerIgnoresSpaceAfterSlash() {
        val trigger = detectSlashCommandTrigger("/web search", TextRange(11))
        assertNull(trigger)
    }

    @Test
    fun testFilterCandidateSkills() {
        val candidatesEmptyQuery = filterCandidateSkills(sampleSkills, "")
        assertEquals(2, candidatesEmptyQuery.size)
        assertTrue(candidatesEmptyQuery.none { !it.enabled })

        val candidatesWeb = filterCandidateSkills(sampleSkills, "web")
        assertEquals(1, candidatesWeb.size)
        assertEquals("web-search", candidatesWeb[0].id)

        val candidatesByDesc = filterCandidateSkills(sampleSkills, "quality")
        assertEquals(1, candidatesByDesc.size)
        assertEquals("code-review", candidatesByDesc[0].id)

        val candidatesDisabled = filterCandidateSkills(sampleSkills, "disabled")
        assertEquals(0, candidatesDisabled.size)
    }

    @Test
    fun testDetectSlashTriggerInsideToken() {
        val trigger = detectSlashCommandTrigger("/web", TextRange(2))
        assertNotNull(trigger)
        assertEquals("w", trigger?.query)
        assertEquals(0, trigger?.tokenStartIndex)
        assertEquals(2, trigger?.tokenEndIndex)
    }

    @Test
    fun testDetectSlashTriggerNonCollapsedSelection() {
        val trigger = detectSlashCommandTrigger("/web", TextRange(0, 4))
        assertNull(trigger)
    }

    @Test
    fun testReplaceSlashCommandToken() {
        val state = TextFieldState("/web and more")
        // Cursor at index 4 (after /web)
        val trigger = detectSlashCommandTrigger(state.text, TextRange(4))
        assertNotNull(trigger)

        state.edit {
            val replacement = "/${sampleSkills[0].id} "
            val textBuffer = asCharSequence()
            var replaceEnd = trigger!!.tokenEndIndex
            while (replaceEnd < length && !textBuffer[replaceEnd].isWhitespace()) {
                replaceEnd++
            }
            if (replaceEnd < length && textBuffer[replaceEnd] == ' ') {
                replaceEnd++
            }
            replace(trigger.tokenStartIndex, replaceEnd, replacement)
            selection = TextRange(trigger.tokenStartIndex + replacement.length)
        }

        assertEquals("/web-search and more", state.text.toString())
        assertEquals(12, state.selection.start)
    }
}
