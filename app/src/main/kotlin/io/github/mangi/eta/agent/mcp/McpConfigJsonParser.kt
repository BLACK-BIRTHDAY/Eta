package io.github.mangi.eta.agent.mcp

import io.github.mangi.eta.data.model.McpAuthorizationType
import io.github.mangi.eta.data.model.McpProtocolMode
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpStdioEnvironment
import io.github.mangi.eta.data.model.McpTransportType
import org.json.JSONArray
import org.json.JSONObject

/**
 * 解析主流客户端（Claude Desktop / Cursor / Cherry Studio 等）的 MCP JSON 配置。
 *
 * 支持格式：
 * 1. `{"mcpServers": {"server-name": {"command": "...", "args": [...], "env": {...}}}}`
 * 2. `{"server-name": {"command": "...", "args": [...]}}`
 * 3. `{"name": "...", "command": "...", "args": [...], "env": {...}}`
 * 4. HTTP 远程配置：`{"mcpServers": {"remote": {"url": "https://...", "headers": {"Authorization": "Bearer ..."}}}}`
 */
internal object McpConfigJsonParser {
    data class ParsedServerDraft(
        val setting: McpServerSetting,
        val bearerToken: String = "",
        val envVars: Map<String, String> = emptyMap(),
    )

    fun parse(
        rawJson: String,
        defaultName: String = "",
        defaultStdioEnvironment: String = McpStdioEnvironment.LINUX,
    ): List<ParsedServerDraft> {
        val trimmed = rawJson.trim()
        require(trimmed.isNotBlank()) { "JSON 内容不能为空" }
        val root = runCatching { JSONObject(trimmed) }.getOrElse {
            throw IllegalArgumentException("JSON 格式无效：${it.message}")
        }

        val serversContainer = root.optJSONObject("mcpServers")
            ?: root.optJSONObject("servers")

        if (serversContainer != null) {
            val drafts = mutableListOf<ParsedServerDraft>()
            extractOrderedKeys(trimmed, serversContainer).forEach { key ->
                val obj = serversContainer.optJSONObject(key) ?: return@forEach
                drafts += parseSingleServerObject(
                    name = obj.optString("name").trim().ifBlank { key.trim() },
                    node = obj,
                    defaultStdioEnvironment = defaultStdioEnvironment,
                )
            }
            require(drafts.isNotEmpty()) { "mcpServers 中未找到有效的服务器配置" }
            return drafts
        }

        // 判断根节点本身是不是一个直接的 server 配置对象
        if (root.has("command") || root.has("url") || root.has("serverUrl") || root.has("baseUrl")) {
            val resolvedName = root.optString("name").trim()
                .ifBlank { defaultName.trim() }
                .ifBlank { inferNameFromCommandOrUrl(root) }
            return listOf(
                parseSingleServerObject(
                    name = resolvedName,
                    node = root,
                    defaultStdioEnvironment = defaultStdioEnvironment,
                )
            )
        }

        // 判断根节点是不是 {"my-server": {"command": ...}} 的无包裹键值映射
        val drafts = mutableListOf<ParsedServerDraft>()
        extractOrderedKeys(trimmed, root).forEach { key ->
            val child = root.optJSONObject(key) ?: return@forEach
            if (child.has("command") || child.has("url") || child.has("serverUrl") || child.has("baseUrl")) {
                drafts += parseSingleServerObject(
                    name = child.optString("name").trim().ifBlank { key.trim() },
                    node = child,
                    defaultStdioEnvironment = defaultStdioEnvironment,
                )
            }
        }
        require(drafts.isNotEmpty()) {
            "未识别到 MCP 配置字段（需要包含 mcpServers、command 或 url）"
        }
        return drafts
    }

    private fun extractOrderedKeys(rawJson: String, container: JSONObject): List<String> {
        val keys = mutableListOf<String>()
        val iter = container.keys()
        while (iter.hasNext()) {
            keys += iter.next()
        }
        return keys.sortedBy { key ->
            val idx = rawJson.indexOf("\"$key\"")
            if (idx >= 0) idx else Int.MAX_VALUE
        }
    }

    private fun parseSingleServerObject(
        name: String,
        node: JSONObject,
        defaultStdioEnvironment: String,
    ): ParsedServerDraft {
        val command = node.optString("command").trim()
        val url = node.optString("url").trim()
            .ifBlank { node.optString("serverUrl").trim() }
            .ifBlank { node.optString("baseUrl").trim() }

        val explicitType = node.optString("type").trim()
            .ifBlank { node.optString("transportType").trim() }
            .lowercase()

        val isStdio = when {
            explicitType == "stdio" -> true
            explicitType in setOf("http", "sse", "streamable-http", "streamablehttp") -> false
            command.isNotBlank() -> true
            url.isNotBlank() -> false
            else -> throw IllegalArgumentException("服务器「$name」缺少 command 或 url 字段")
        }

        val finalName = name.ifBlank { inferNameFromCommandOrUrl(node) }
        val enabled = !node.optBoolean("disabled", false) && node.optBoolean("enabled", true)

        if (isStdio) {
            McpStdioClient.validateStdioCommand(command)
            val args = parseArgsArray(node.opt("args"))
            val envVars = parseStringMap(node.optJSONObject("env"))
            val cwd = node.optString("cwd").trim()
            val stdioEnv = node.optString("stdioEnvironment").trim()
                .takeIf {
                    it in setOf(
                        McpStdioEnvironment.LINUX,
                        McpStdioEnvironment.ANDROID_ROOT,
                        McpStdioEnvironment.ANDROID_USER,
                    )
                } ?: defaultStdioEnvironment

            return ParsedServerDraft(
                setting = McpServerSetting(
                    id = "",
                    name = finalName,
                    transportType = McpTransportType.STDIO,
                    command = command,
                    args = args,
                    stdioEnvironment = stdioEnv,
                    cwd = cwd,
                    hasEnvVars = envVars.isNotEmpty(),
                    enabled = enabled,
                    protocolMode = McpProtocolMode.LEGACY,
                    authorizationType = McpAuthorizationType.NONE,
                ),
                envVars = envVars,
            )
        } else {
            validateMcpEndpoint(url)
            val headers = parseStringMap(node.optJSONObject("headers"))
            val bearerFromHeader = headers.entries
                .firstOrNull { it.key.equals("Authorization", ignoreCase = true) }
                ?.value
                ?.trim()
                ?.removePrefix("Bearer ")
                ?.removePrefix("bearer ")
                ?.trim()
                .orEmpty()
            val token = node.optString("bearerToken").trim()
                .ifBlank { node.optString("token").trim() }
                .ifBlank { bearerFromHeader }

            return ParsedServerDraft(
                setting = McpServerSetting(
                    id = "",
                    name = finalName,
                    url = url,
                    transportType = McpTransportType.HTTP,
                    enabled = enabled,
                    protocolMode = McpProtocolMode.AUTO,
                    authorizationType = if (token.isBlank()) {
                        McpAuthorizationType.NONE
                    } else {
                        McpAuthorizationType.BEARER
                    },
                ),
                bearerToken = token,
            )
        }
    }

    private fun parseArgsArray(raw: Any?): List<String> = when (raw) {
        is JSONArray -> buildList {
            for (i in 0 until raw.length()) {
                if (!raw.isNull(i)) {
                    add(raw.optString(i))
                }
            }
        }
        is String -> if (raw.isBlank()) emptyList() else McpStdioClient.splitCommandLineArgs(raw)
        else -> emptyList()
    }

    private fun parseStringMap(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        return buildMap {
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next().trim()
                if (key.isNotEmpty() && !obj.isNull(key)) {
                    put(key, obj.optString(key))
                }
            }
        }
    }

    private fun inferNameFromCommandOrUrl(node: JSONObject): String {
        val args = parseArgsArray(node.opt("args"))
        val packageArg = args.lastOrNull { !it.startsWith("-") }
        if (!packageArg.isNullOrBlank()) {
            return packageArg.substringAfterLast('/').substringBefore('@').ifBlank { "MCP Server" }
        }
        val cmd = node.optString("command").trim()
        if (cmd.isNotBlank()) {
            return cmd.substringAfterLast('/').ifBlank { "Stdio MCP" }
        }
        val url = node.optString("url").trim()
        if (url.isNotBlank()) {
            return runCatching { validateMcpEndpoint(url).host }.getOrDefault("HTTP MCP")
        }
        return "MCP Server"
    }

    /**
     * 解析用户在多行文本框中输入的 `KEY=VALUE` 环境变量列表。
     */
    fun parseEnvLines(text: String): Map<String, String> = buildMap {
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val cleanLine = line.removePrefix("export ").trim()
            val eqIndex = cleanLine.indexOf('=')
            require(eqIndex > 0) { "环境变量格式应为 KEY=VALUE：$rawLine" }
            val key = cleanLine.substring(0, eqIndex).trim()
            val rawVal = cleanLine.substring(eqIndex + 1).trim()
            val value = when {
                rawVal.length >= 2 && rawVal.startsWith('"') && rawVal.endsWith('"') ->
                    rawVal.substring(1, rawVal.length - 1)
                rawVal.length >= 2 && rawVal.startsWith('\'') && rawVal.endsWith('\'') ->
                    rawVal.substring(1, rawVal.length - 1)
                else -> rawVal
            }
            put(key, value)
        }
    }

    fun formatEnvLines(envVars: Map<String, String>): String =
        envVars.entries.joinToString("\n") { (k, v) -> "$k=$v" }
}
