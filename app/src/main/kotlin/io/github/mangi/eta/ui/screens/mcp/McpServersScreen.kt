package io.github.mangi.eta.ui.screens.mcp

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.mcp.McpConfigJsonParser
import io.github.mangi.eta.agent.mcp.McpServerManager
import io.github.mangi.eta.agent.mcp.McpStdioClient
import io.github.mangi.eta.agent.mcp.validateMcpEndpoint
import io.github.mangi.eta.data.model.McpAuthorizationType
import io.github.mangi.eta.data.model.McpProtocolMode
import io.github.mangi.eta.data.model.McpServerSetting
import io.github.mangi.eta.data.model.McpStdioEnvironment
import io.github.mangi.eta.data.model.McpToolDefinition
import io.github.mangi.eta.data.model.McpTransportType
import io.github.mangi.eta.data.repository.McpServerRepository
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaChoiceChip
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDefaults
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.navigation.AppRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponentColors
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class AddMcpMode {
    HTTP,
    STDIO,
    JSON_IMPORT,
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun McpServersScreen(
    onNavigate: (AppRoute) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val servers by McpServerRepository.serversFlow().collectAsState(initial = emptyList())
    var showAdd by remember { mutableStateOf(false) }
    var addMode by remember { mutableStateOf(AddMcpMode.HTTP) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var argsText by remember { mutableStateOf("") }
    var cwd by remember { mutableStateOf("") }
    var envText by remember { mutableStateOf("") }
    var stdioEnvironment by remember { mutableStateOf(McpStdioEnvironment.LINUX) }
    var jsonInput by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun resetDialogState() {
        name = ""
        url = ""
        token = ""
        command = ""
        argsText = ""
        cwd = ""
        envText = ""
        stdioEnvironment = McpStdioEnvironment.LINUX
        jsonInput = ""
        error = null
    }

    MiuixScaffoldPage(
        title = stringResource(R.string.route_mcp_servers),
        onBack = onBack,
        actions = {
            IconButton(onClick = { showAdd = true }) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = stringResource(R.string.mcp_add_server),
                )
            }
        },
    ) {
        item(key = "servers") {
            EtaPreferenceGroupTitle(stringResource(R.string.mcp_configured_servers, servers.size))
            EtaPreferenceGroup {
                if (servers.isEmpty()) {
                    ListEmptyState(
                        title = stringResource(R.string.mcp_empty_title),
                        summary = stringResource(R.string.mcp_empty_summary),
                        icon = Icons.Rounded.Hub,
                        action = {
                            EtaTextButton(
                                text = stringResource(R.string.mcp_add_server),
                                onClick = { showAdd = true },
                            )
                        },
                    )
                } else {
                    servers.forEachIndexed { index, server ->
                        if (index > 0) EtaPreferenceDivider(hasLeading = false)
                        val transportBadge = if (server.isStdio) "STDIO" else "HTTP"
                        EtaArrowPreference(
                            title = server.name,
                            summary = stringResource(
                                R.string.mcp_server_row_summary_with_transport,
                                server.activeTools.size,
                                server.tools.size,
                                transportBadge,
                            ),
                            onClick = { onNavigate(AppRoute.McpServerDetail(server.id)) },
                        )
                    }
                }
            }
        }
    }

    EtaWindowDialog(
        show = showAdd,
        title = stringResource(R.string.mcp_add_server),
        onDismissRequest = { if (!working) showAdd = false },
    ) {
        // 只有表单滚动，按钮固定在底部：输入法弹出或错误较长时确认操作仍然可达。
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    EtaChoiceChip(
                        text = stringResource(R.string.mcp_transport_http),
                        selected = addMode == AddMcpMode.HTTP,
                        enabled = !working,
                        onClick = {
                            addMode = AddMcpMode.HTTP
                            error = null
                        },
                    )
                    EtaChoiceChip(
                        text = stringResource(R.string.mcp_transport_stdio),
                        selected = addMode == AddMcpMode.STDIO,
                        enabled = !working,
                        onClick = {
                            addMode = AddMcpMode.STDIO
                            error = null
                        },
                    )
                    EtaChoiceChip(
                        text = stringResource(R.string.mcp_mode_json_import),
                        selected = addMode == AddMcpMode.JSON_IMPORT,
                        enabled = !working,
                        onClick = {
                            addMode = AddMcpMode.JSON_IMPORT
                            error = null
                        },
                    )
                }

                when (addMode) {
                    AddMcpMode.HTTP -> {
                        TextField(
                            value = name,
                            onValueChange = { name = it },
                            label = stringResource(R.string.mcp_server_name),
                            singleLine = true,
                            enabled = !working,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextField(
                            value = url,
                            onValueChange = { url = it },
                            label = stringResource(R.string.mcp_server_url),
                            singleLine = true,
                            enabled = !working,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextField(
                            value = token,
                            onValueChange = { token = it },
                            label = stringResource(R.string.mcp_bearer_optional),
                            singleLine = true,
                            enabled = !working,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    AddMcpMode.STDIO -> {
                        TextField(
                            value = name,
                            onValueChange = { name = it },
                            label = stringResource(R.string.mcp_server_name),
                            singleLine = true,
                            enabled = !working,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextField(
                            value = command,
                            onValueChange = { command = it },
                            label = stringResource(R.string.mcp_stdio_command),
                            singleLine = true,
                            enabled = !working,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextField(
                            value = argsText,
                            onValueChange = { argsText = it },
                            label = stringResource(R.string.mcp_stdio_args),
                            singleLine = false,
                            enabled = !working,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = stringResource(R.string.mcp_stdio_env_label),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        StdioEnvironmentSelector(
                            selected = stdioEnvironment,
                            enabled = !working,
                            onSelect = { stdioEnvironment = it },
                        )
                        TextField(
                            value = cwd,
                            onValueChange = { cwd = it },
                            label = stringResource(R.string.mcp_stdio_cwd),
                            singleLine = true,
                            enabled = !working,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextField(
                            value = envText,
                            onValueChange = { envText = it },
                            label = stringResource(R.string.mcp_stdio_env),
                            singleLine = false,
                            enabled = !working,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    AddMcpMode.JSON_IMPORT -> {
                        Text(
                            text = stringResource(R.string.mcp_stdio_env_label),
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        StdioEnvironmentSelector(
                            selected = stdioEnvironment,
                            enabled = !working,
                            onSelect = { stdioEnvironment = it },
                        )
                        TextField(
                            value = jsonInput,
                            onValueChange = { jsonInput = it },
                            label = stringResource(R.string.mcp_json_input_label),
                            singleLine = false,
                            enabled = !working,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                        )
                    }
                }

                error?.let {
                    Text(
                        text = it,
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.error,
                    )
                }
            }
            MiuixDialogActions(
                modifier = Modifier.padding(top = 16.dp),
                confirmText = if (working) {
                    stringResource(R.string.mcp_connecting)
                } else {
                    stringResource(R.string.mcp_add_server)
                },
                confirmEnabled = !working,
                onCancel = { showAdd = false },
                onConfirm = {
                    when (addMode) {
                        AddMcpMode.HTTP -> {
                            val normalizedName = name.trim()
                            val normalizedUrl = url.trim()
                            error = when {
                                normalizedName.isBlank() -> resources.getString(R.string.mcp_name_required)
                                else -> runCatching { validateMcpEndpoint(normalizedUrl) }
                                    .exceptionOrNull()?.message
                            }
                            if (error == null) scope.launch {
                                working = true
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val draft = McpServerSetting(
                                            id = "",
                                            name = normalizedName,
                                            url = normalizedUrl,
                                            transportType = McpTransportType.HTTP,
                                            protocolMode = McpProtocolMode.AUTO,
                                            authorizationType = if (token.isBlank()) {
                                                McpAuthorizationType.NONE
                                            } else {
                                                McpAuthorizationType.BEARER
                                            },
                                        )
                                        val discovered = McpServerManager.discover(draft, token)
                                        McpServerRepository.add(discovered, token)
                                    }
                                }
                                working = false
                                result.onSuccess {
                                    showAdd = false
                                    resetDialogState()
                                    Toast.makeText(
                                        context,
                                        resources.getString(R.string.mcp_server_added, it.tools.size),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }.onFailure {
                                    error = it.message ?: resources.getString(R.string.mcp_connection_failed)
                                }
                            }
                        }

                        AddMcpMode.STDIO -> {
                            val normalizedName = name.trim()
                            val normalizedCommand = command.trim()
                            val parsedEnvResult = runCatching { McpConfigJsonParser.parseEnvLines(envText) }
                            error = when {
                                normalizedName.isBlank() -> resources.getString(R.string.mcp_name_required)
                                parsedEnvResult.isFailure -> parsedEnvResult.exceptionOrNull()?.message
                                else -> runCatching { McpStdioClient.validateStdioCommand(normalizedCommand) }
                                    .exceptionOrNull()?.message
                            }
                            if (error == null) scope.launch {
                                val envMap = parsedEnvResult.getOrDefault(emptyMap())
                                val parsedArgs = if (argsText.isBlank()) {
                                    emptyList()
                                } else {
                                    McpStdioClient.splitCommandLineArgs(argsText)
                                }
                                working = true
                                val result = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val draft = McpServerSetting(
                                            id = "",
                                            name = normalizedName,
                                            transportType = McpTransportType.STDIO,
                                            command = normalizedCommand,
                                            args = parsedArgs,
                                            stdioEnvironment = stdioEnvironment,
                                            cwd = cwd.trim(),
                                            hasEnvVars = envMap.isNotEmpty(),
                                            protocolMode = McpProtocolMode.LEGACY,
                                            authorizationType = McpAuthorizationType.NONE,
                                        )
                                        val discovered = McpServerManager.discover(
                                            server = draft,
                                            bearerToken = null,
                                            envVars = envMap,
                                        )
                                        McpServerRepository.add(
                                            server = discovered,
                                            bearerToken = "",
                                            envVars = envMap,
                                        )
                                    }
                                }
                                working = false
                                result.onSuccess {
                                    showAdd = false
                                    resetDialogState()
                                    Toast.makeText(
                                        context,
                                        resources.getString(R.string.mcp_server_added, it.tools.size),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }.onFailure {
                                    error = it.message ?: resources.getString(R.string.mcp_connection_failed)
                                }
                            }
                        }

                        AddMcpMode.JSON_IMPORT -> {
                            val parsedResult = runCatching {
                                McpConfigJsonParser.parse(
                                    rawJson = jsonInput,
                                    defaultStdioEnvironment = stdioEnvironment,
                                )
                            }
                            if (parsedResult.isFailure) {
                                error = parsedResult.exceptionOrNull()?.message
                            } else {
                                val drafts = parsedResult.getOrThrow()
                                error = null
                                scope.launch {
                                    working = true
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching {
                                            var totalTools = 0
                                            drafts.forEach { draft ->
                                                val discovered = McpServerManager.discover(
                                                    server = draft.setting,
                                                    bearerToken = draft.bearerToken,
                                                    envVars = draft.envVars,
                                                )
                                                val added = McpServerRepository.add(
                                                    server = discovered,
                                                    bearerToken = draft.bearerToken,
                                                    envVars = draft.envVars,
                                                )
                                                totalTools += added.tools.size
                                            }
                                            drafts.size to totalTools
                                        }
                                    }
                                    working = false
                                    result.onSuccess { (serverCount, toolCount) ->
                                        showAdd = false
                                        resetDialogState()
                                        Toast.makeText(
                                            context,
                                            resources.getString(
                                                R.string.mcp_json_import_added,
                                                serverCount,
                                                toolCount,
                                            ),
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }.onFailure {
                                        error = it.message ?: resources.getString(R.string.mcp_connection_failed)
                                    }
                                }
                            }
                        }
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StdioEnvironmentSelector(
    selected: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        EtaChoiceChip(
            text = stringResource(R.string.mcp_stdio_env_linux),
            selected = selected == McpStdioEnvironment.LINUX,
            enabled = enabled,
            onClick = { onSelect(McpStdioEnvironment.LINUX) },
        )
        EtaChoiceChip(
            text = stringResource(R.string.mcp_stdio_env_android_root),
            selected = selected == McpStdioEnvironment.ANDROID_ROOT,
            enabled = enabled,
            onClick = { onSelect(McpStdioEnvironment.ANDROID_ROOT) },
        )
        EtaChoiceChip(
            text = stringResource(R.string.mcp_stdio_env_android_user),
            selected = selected == McpStdioEnvironment.ANDROID_USER,
            enabled = enabled,
            onClick = { onSelect(McpStdioEnvironment.ANDROID_USER) },
        )
    }
}

@Composable
internal fun McpServerDetailScreen(
    serverId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val servers by McpServerRepository.serversFlow().collectAsState(initial = emptyList())
    val server = servers.firstOrNull { it.id == serverId }
    var working by remember { mutableStateOf(false) }
    var pendingRiskyTool by remember { mutableStateOf<McpToolDefinition?>(null) }
    var showDelete by remember { mutableStateOf(false) }
    var showToken by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var showEditStdio by remember { mutableStateOf(false) }
    var editCommand by remember { mutableStateOf("") }
    var editArgs by remember { mutableStateOf("") }
    var editCwd by remember { mutableStateOf("") }
    var editStdioEnv by remember { mutableStateOf(McpStdioEnvironment.LINUX) }
    var showEditEnv by remember { mutableStateOf(false) }
    var editEnvText by remember { mutableStateOf("") }
    var editError by remember { mutableStateOf<String?>(null) }

    MiuixScaffoldPage(
        title = server?.name ?: stringResource(R.string.route_mcp_server_detail),
        onBack = onBack,
        actions = {
            IconButton(
                enabled = server != null && !working,
                onClick = {
                    scope.launch {
                        working = true
                        val result = withContext(Dispatchers.IO) {
                            runCatching { McpServerManager.refresh(serverId) }
                        }
                        working = false
                        Toast.makeText(
                            context,
                            result.fold(
                                onSuccess = { resources.getString(R.string.mcp_refreshed, it.tools.size) },
                                onFailure = { it.message ?: resources.getString(R.string.mcp_refresh_failed) },
                            ),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.mcp_refresh_tools),
                )
            }
        },
    ) {
        if (server == null) {
            item(key = "missing") {
                ListEmptyState(title = stringResource(R.string.mcp_server_missing))
            }
            return@MiuixScaffoldPage
        }
        item(key = "server") {
            EtaPreferenceGroupTitle(stringResource(R.string.mcp_server_settings))
            EtaPreferenceGroup {
                EtaSwitchPreference(
                    title = stringResource(R.string.mcp_enable_server),
                    summary = server.endpointSummary,
                    checked = server.enabled,
                    onCheckedChange = { enabled ->
                        scope.launch(Dispatchers.IO) {
                            McpServerRepository.update(server.copy(enabled = enabled))
                        }
                    },
                )

                if (server.isStdio) {
                    EtaPreferenceDivider(hasLeading = false)
                    EtaArrowPreference(
                        title = stringResource(R.string.mcp_edit_stdio_config),
                        summary = stdioEnvironmentSummary(server.stdioEnvironment),
                        onClick = {
                            editCommand = server.command
                            editArgs = server.args.joinToString(" ")
                            editCwd = server.cwd
                            editStdioEnv = server.stdioEnvironment
                            editError = null
                            showEditStdio = true
                        },
                    )
                    EtaPreferenceDivider(hasLeading = false)
                    EtaArrowPreference(
                        title = stringResource(R.string.mcp_update_env_vars),
                        summary = stringResource(
                            if (server.hasEnvVars) {
                                R.string.mcp_env_vars_configured
                            } else {
                                R.string.mcp_no_env_vars
                            }
                        ),
                        onClick = {
                            scope.launch {
                                val loaded = withContext(Dispatchers.IO) {
                                    McpServerRepository.envVars(server.id)
                                }
                                editEnvText = McpConfigJsonParser.formatEnvLines(loaded)
                                editError = null
                                showEditEnv = true
                            }
                        },
                    )
                } else {
                    EtaPreferenceDivider(hasLeading = false)
                    EtaArrowPreference(
                        title = stringResource(R.string.mcp_update_token),
                        summary = stringResource(
                            if (server.authorizationType == McpAuthorizationType.BEARER) {
                                R.string.mcp_token_configured
                            } else {
                                R.string.mcp_no_authentication
                            }
                        ),
                        onClick = { showToken = true },
                    )
                }
            }
        }
        item(key = "tools") {
            EtaPreferenceGroupTitle(stringResource(R.string.mcp_tools_count, server.tools.size))
            EtaPreferenceGroup {
                if (server.tools.isEmpty()) {
                    ListEmptyState(
                        title = stringResource(R.string.mcp_no_tools),
                        summary = stringResource(R.string.mcp_refresh_tools_hint),
                        icon = Icons.Rounded.Refresh,
                    )
                } else {
                    server.tools.forEachIndexed { index, tool ->
                        if (index > 0) EtaPreferenceDivider(hasLeading = false)
                        val checked = tool.name in server.enabledToolNames
                        EtaSwitchPreference(
                            title = tool.title.ifBlank { tool.name },
                            summary = toolSummary(tool),
                            checked = checked,
                            onCheckedChange = { enabled ->
                                if (enabled && tool.readOnlyHint != true) {
                                    pendingRiskyTool = tool
                                } else {
                                    scope.launch(Dispatchers.IO) {
                                        McpServerRepository.setToolEnabled(serverId, tool.name, enabled)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
        item(key = "delete") {
            EtaPreferenceGroup(
                modifier = Modifier
                    .padding(horizontal = EtaPreferenceDefaults.SidePadding)
                    .padding(top = 8.dp, bottom = EtaPreferenceDefaults.GroupSpacing)
            ) {
                EtaPreference(
                    title = stringResource(R.string.mcp_delete_server),
                    titleColor = BasicComponentColors(
                        color = MiuixTheme.colorScheme.error,
                        disabledColor = MiuixTheme.colorScheme.disabledOnSurface,
                    ),
                    summary = stringResource(R.string.mcp_delete_server_summary),
                    onClick = { showDelete = true },
                )
            }
        }
    }

    pendingRiskyTool?.let { tool ->
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.mcp_enable_risky_tool),
            summary = stringResource(R.string.mcp_enable_risky_tool_summary, tool.name, server?.name.orEmpty()),
            onDismissRequest = { pendingRiskyTool = null },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.mcp_enable_tool),
                destructive = true,
                onCancel = { pendingRiskyTool = null },
                onConfirm = {
                    scope.launch(Dispatchers.IO) {
                        McpServerRepository.setToolEnabled(serverId, tool.name, true)
                    }
                    pendingRiskyTool = null
                },
            )
        }
    }

    EtaWindowDialog(
        show = showToken,
        title = stringResource(R.string.mcp_update_token),
        summary = stringResource(R.string.mcp_update_token_summary),
        onDismissRequest = { showToken = false },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(
                value = token,
                onValueChange = { token = it },
                label = stringResource(R.string.mcp_bearer_token),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            MiuixDialogActions(
                confirmText = stringResource(R.string.action_save),
                onCancel = { showToken = false },
                onConfirm = {
                    server?.let { current ->
                        scope.launch(Dispatchers.IO) {
                            McpServerRepository.update(
                                current.copy(
                                    authorizationType = if (token.isBlank()) {
                                        McpAuthorizationType.NONE
                                    } else {
                                        McpAuthorizationType.BEARER
                                    },
                                ),
                                bearerToken = token,
                            )
                        }
                        token = ""
                        showToken = false
                    }
                },
            )
        }
    }

    EtaWindowDialog(
        show = showEditStdio,
        title = stringResource(R.string.mcp_edit_stdio_config),
        onDismissRequest = { showEditStdio = false },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(
                value = editCommand,
                onValueChange = { editCommand = it },
                label = stringResource(R.string.mcp_stdio_command),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextField(
                value = editArgs,
                onValueChange = { editArgs = it },
                label = stringResource(R.string.mcp_stdio_args),
                singleLine = false,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.mcp_stdio_env_label),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            StdioEnvironmentSelector(
                selected = editStdioEnv,
                enabled = true,
                onSelect = { editStdioEnv = it },
            )
            TextField(
                value = editCwd,
                onValueChange = { editCwd = it },
                label = stringResource(R.string.mcp_stdio_cwd),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            editError?.let {
                Text(
                    text = it,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.error,
                )
            }
            MiuixDialogActions(
                confirmText = stringResource(R.string.action_save),
                onCancel = { showEditStdio = false },
                onConfirm = {
                    val cmdResult = runCatching { McpStdioClient.validateStdioCommand(editCommand) }
                    if (cmdResult.isFailure) {
                        editError = cmdResult.exceptionOrNull()?.message
                    } else {
                        server?.let { current ->
                            val parsedArgs = if (editArgs.isBlank()) {
                                emptyList()
                            } else {
                                McpStdioClient.splitCommandLineArgs(editArgs)
                            }
                            scope.launch(Dispatchers.IO) {
                                McpServerRepository.update(
                                    current.copy(
                                        command = cmdResult.getOrThrow(),
                                        args = parsedArgs,
                                        stdioEnvironment = editStdioEnv,
                                        cwd = editCwd.trim(),
                                    )
                                )
                            }
                            showEditStdio = false
                        }
                    }
                },
            )
        }
    }

    EtaWindowDialog(
        show = showEditEnv,
        title = stringResource(R.string.mcp_update_env_vars),
        summary = stringResource(R.string.mcp_update_env_vars_summary),
        onDismissRequest = { showEditEnv = false },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextField(
                value = editEnvText,
                onValueChange = { editEnvText = it },
                label = stringResource(R.string.mcp_stdio_env),
                singleLine = false,
                modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
            )
            editError?.let {
                Text(
                    text = it,
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.error,
                )
            }
            MiuixDialogActions(
                confirmText = stringResource(R.string.action_save),
                onCancel = { showEditEnv = false },
                onConfirm = {
                    val envResult = runCatching { McpConfigJsonParser.parseEnvLines(editEnvText) }
                    if (envResult.isFailure) {
                        editError = envResult.exceptionOrNull()?.message
                    } else {
                        server?.let { current ->
                            val envMap = envResult.getOrThrow()
                            scope.launch(Dispatchers.IO) {
                                McpServerRepository.update(
                                    server = current.copy(hasEnvVars = envMap.isNotEmpty()),
                                    envVars = envMap,
                                )
                            }
                            showEditEnv = false
                        }
                    }
                },
            )
        }
    }

    EtaWindowDialog(
        show = showDelete,
        title = stringResource(R.string.mcp_delete_server),
        summary = stringResource(R.string.mcp_delete_confirm, server?.name.orEmpty()),
        onDismissRequest = { showDelete = false },
    ) {
        MiuixDialogActions(
            confirmText = stringResource(R.string.ui_delete_3755f5),
            destructive = true,
            onCancel = { showDelete = false },
            onConfirm = {
                scope.launch {
                    withContext(Dispatchers.IO) { McpServerRepository.delete(serverId) }
                    onBack()
                }
            },
        )
    }
}

@Composable
private fun stdioEnvironmentSummary(stdioEnv: String): String = when (stdioEnv) {
    McpStdioEnvironment.ANDROID_ROOT -> stringResource(R.string.mcp_stdio_env_android_root)
    McpStdioEnvironment.ANDROID_USER -> stringResource(R.string.mcp_stdio_env_android_user)
    else -> stringResource(R.string.mcp_stdio_env_linux)
}

@Composable
private fun toolSummary(tool: McpToolDefinition): String = when {
    tool.readOnlyHint == true -> tool.description.ifBlank { stringResource(R.string.mcp_read_only_tool) }
    else -> tool.description.ifBlank { stringResource(R.string.mcp_may_modify_data) }
}
