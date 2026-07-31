package fuck.andes.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** 常用设备能力的结构化 schema；按风险组决定是否向模型公开。 */
internal object AgentDeviceToolCatalog {
    fun appendTo(
        tools: JSONArray,
        directTools: Boolean,
        sensitiveReadTools: Boolean,
        sensitiveActionTools: Boolean,
    ) {
        if (directTools) appendDirectTools(tools)
        if (sensitiveReadTools) appendSensitiveReadTools(tools)
        if (sensitiveActionTools) appendSensitiveActionTools(tools)
    }

    private fun appendDirectTools(tools: JSONArray) {
        tools
            .put(
                function(
                    "set_alarm",
                    "直接创建系统闹钟，不要用 GUI。涉及相对日期时先用 get_current_context 换算；hour/minute 使用设备本地时间。系统不接受直达操作时可能只打开时钟页面。",
                    properties(
                        "hour" to integer("0 到 23", 0, 23),
                        "minute" to integer("0 到 59", 0, 59),
                        "label" to string("闹钟标签，最多 100 字", 100),
                        "repeat_days" to stringArray(
                            "重复星期；不提供表示仅下一次",
                            "mon", "tue", "wed", "thu", "fri", "sat", "sun",
                        ),
                        "vibrate" to boolean("是否振动，默认 true"),
                    ),
                    "hour", "minute",
                ),
            )
            .put(
                function(
                    "set_timer",
                    "直接创建系统计时器，不要用 GUI。duration_seconds 必须是 1 到 86400 秒。",
                    properties(
                        "duration_seconds" to integer("计时秒数", 1, 86_400),
                        "label" to string("计时器标签，最多 100 字", 100),
                    ),
                    "duration_seconds",
                ),
            )
            .put(emptyFunction("device_status", "读取电池、内存、存储、系统版本与开机时长。"))
            .put(emptyFunction("network_info", "读取当前联网方式、联网验证状态和当前 Wi‑Fi 基本信息，不返回保存的密码。"))
            .put(limitFunction("top_memory_apps", "按当前 RSS 列出内存占用最高的进程。"))
            .put(limitFunction("top_storage_apps", "按应用、数据与缓存合计列出存储占用最高的应用。"))
            .put(
                function(
                    "media_control",
                    "直接控制当前媒体会话，不要操作播放器 GUI。",
                    properties(
                        "action" to enumString(
                            "媒体动作",
                            "play", "pause", "play_pause", "next", "previous", "stop",
                        ),
                    ),
                    "action",
                ),
            )
            .put(
                function(
                    "set_volume",
                    "直接设置系统音量，不要操作音量 GUI。",
                    properties(
                        "stream" to enumString("音量通道", "media", "alarm", "ring", "notification"),
                        "percent" to integer("0 到 100 的音量百分比", 0, 100),
                    ),
                    "stream", "percent",
                ),
            )
    }

    private fun appendSensitiveReadTools(tools: JSONArray) {
        tools
            .put(
                function(
                    "get_setting",
                    "读取一个 Android Settings 值。结果可能包含设备标识等敏感信息，原始结果不会持久化。",
                    properties(
                        "namespace" to enumString("设置命名空间", "system", "secure", "global"),
                        "key" to string("精确设置键", 200),
                    ),
                    "namespace", "key",
                ),
            )
            .put(
                function(
                    "wifi_credentials",
                    "读取手机保存的 Wi‑Fi 名称与密码。原始结果不会持久化。",
                    properties(
                        "ssid" to string("可选的精确 Wi‑Fi 名称", 128),
                        "limit" to integer("最多返回数量，默认 20", 1, 50),
                    ),
                ),
            )
            .put(
                function(
                    "recent_notifications",
                    "读取当前通知栏中的通知标题与正文。结果不写入持久会话。",
                    properties(
                        "package_name" to string("可选的精确应用包名过滤", 255),
                        "limit" to integer("最多返回数量，默认 10", 1, 20),
                    ),
                ),
            )
            .put(
                function(
                    "read_sms_code",
                    "从最近短信中只提取 4 到 8 位验证码、发送方和时间，不返回完整短信正文。",
                    properties(
                        "max_age_minutes" to integer("只检查多少分钟内的短信，默认 10", 1, 1_440),
                    ),
                ),
            )
            .put(
                function(
                    "get_logcat",
                    "读取最近系统日志。query 只在已读取日志中做文本过滤，不会进入 Shell。",
                    properties(
                        "query" to string("可选过滤文本", 200),
                        "max_lines" to integer("最多日志行数，默认 200", 20, 500),
                    ),
                ),
            )
    }

    private fun appendSensitiveActionTools(tools: JSONArray) {
        tools
            .put(
                function(
                    "set_setting",
                    "修改一个非安全关键 Android Settings 值。无障碍、ADB、设备初始化等关键键会被拒绝。",
                    properties(
                        "namespace" to enumString("设置命名空间", "system", "secure", "global"),
                        "key" to string("精确设置键", 200),
                        "value" to string("新值", 2_000),
                    ),
                    "namespace", "key", "value",
                ),
            )
            .put(
                function(
                    "set_device_state",
                    "直接启用或关闭 Wi‑Fi/蓝牙，不要操作设置 GUI。",
                    properties(
                        "target" to enumString("设备能力", "wifi", "bluetooth"),
                        "enabled" to boolean("true 启用，false 关闭"),
                    ),
                    "target", "enabled",
                ),
            )
            .put(
                function(
                    "app_state_control",
                    "停止、冻结或解冻一个精确包名。核心系统包受保护；freeze 不允许系统应用。",
                    properties(
                        "package_name" to string("精确 Android 包名", 255),
                        "action" to enumString("动作", "force_stop", "freeze", "unfreeze"),
                    ),
                    "package_name", "action",
                ),
            )
    }

    private fun emptyFunction(name: String, description: String): JSONObject =
        function(name, description, properties())

    private fun limitFunction(name: String, description: String): JSONObject =
        function(
            name,
            description,
            properties("limit" to integer("最多返回数量，默认 10", 1, 30)),
        )

    private fun function(
        name: String,
        description: String,
        properties: JSONObject,
        vararg required: String,
    ): JSONObject =
        AgentToolSchema.function(
            name = name,
            description = description,
            parameters = JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .also { schema ->
                    if (required.isNotEmpty()) schema.put("required", JSONArray(required.toList()))
                },
        )

    private fun properties(vararg entries: Pair<String, JSONObject>): JSONObject =
        JSONObject().also { target -> entries.forEach { (name, schema) -> target.put(name, schema) } }

    private fun string(description: String, maxLength: Int? = null): JSONObject =
        JSONObject()
            .put("type", "string")
            .put("description", description)
            .also { schema -> maxLength?.let { schema.put("maxLength", it) } }

    private fun boolean(description: String): JSONObject =
        JSONObject().put("type", "boolean").put("description", description)

    private fun integer(description: String, minimum: Int, maximum: Int): JSONObject =
        JSONObject()
            .put("type", "integer")
            .put("minimum", minimum)
            .put("maximum", maximum)
            .put("description", description)

    private fun enumString(description: String, vararg values: String): JSONObject =
        string(description).put("enum", JSONArray(values.toList()))

    private fun stringArray(description: String, vararg values: String): JSONObject =
        JSONObject()
            .put("type", "array")
            .put("items", enumString(description, *values))
            .put("uniqueItems", true)
            .put("maxItems", 7)
            .put("description", description)
}
