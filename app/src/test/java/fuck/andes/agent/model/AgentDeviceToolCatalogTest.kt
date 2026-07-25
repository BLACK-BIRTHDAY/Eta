package fuck.andes.agent.model

import org.json.JSONArray
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentDeviceToolCatalogTest {
    @Test
    fun riskGroupsExposeOnlyTheirOwnTools() {
        val none = names(false, false, false)
        val direct = names(true, false, false)
        val reads = names(false, true, false)
        val actions = names(false, false, true)

        assertFalse("set_alarm" in none)
        assertTrue("set_alarm" in direct)
        assertFalse("read_sms_code" in direct)
        assertTrue("read_sms_code" in reads)
        assertFalse("send_message" in reads)
        assertTrue("send_message" in actions)
        assertTrue("app_state_control" in actions)
    }

    @Test
    fun messageSchemaRequiresExplicitModeAndBoundedText() {
        val tools = AgentToolCatalog.build(
            terminalTools = false,
            browserTools = false,
            deviceDirectTools = false,
            deviceSensitiveReadTools = false,
            deviceSensitiveActionTools = true,
        )
        val function = (0 until tools.length())
            .asSequence()
            .map { tools.getJSONObject(it).getJSONObject("function") }
            .first { it.getString("name") == "send_message" }
        val parameters = function.getJSONObject("parameters")
        val required = parameters.getJSONArray("required")
        val names = (0 until required.length()).map(required::getString).toSet()

        assertTrue(names.containsAll(setOf("contact", "message", "mode")))
        assertTrue(
            parameters.getJSONObject("properties")
                .getJSONObject("message")
                .getInt("maxLength") == 2_000,
        )
        assertTrue(function.getString("description").contains("绝不自动重试"))
    }

    private fun names(
        direct: Boolean,
        reads: Boolean,
        actions: Boolean,
    ): Set<String> {
        val tools = AgentToolCatalog.build(
            terminalTools = false,
            browserTools = false,
            deviceDirectTools = direct,
            deviceSensitiveReadTools = reads,
            deviceSensitiveActionTools = actions,
        )
        return tools.toolNames()
    }

    private fun JSONArray.toolNames(): Set<String> =
        (0 until length()).mapTo(mutableSetOf()) { index ->
            getJSONObject(index).getJSONObject("function").getString("name")
        }
}
