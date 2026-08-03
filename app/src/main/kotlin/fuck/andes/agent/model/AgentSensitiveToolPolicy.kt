package fuck.andes.agent.model

/** 标记原始参数或结果不得进入持久会话的工具。 */
internal object AgentSensitiveToolPolicy {
    fun isSensitive(toolName: String): Boolean = toolName in sensitiveTools

    private val sensitiveTools = setOf(
        "get_setting",
        "wifi_credentials",
        "recent_notifications",
        "read_sms_code",
        "get_logcat",
        "set_setting",
        "memory_get",
        "memory_write",
    )
}
