package io.github.mangi.eta.ui.screens.skills

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
import io.github.mangi.eta.R
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiButtonRole
import io.github.mangi.eta.ui.components.coui.CouiConfirmDialog
import io.github.mangi.eta.ui.components.coui.CouiNoticeDialog
import io.github.mangi.eta.ui.components.coui.CouiPageBottomSpacer
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiTextField
import io.github.mangi.eta.ui.model.AgentSkillsAction
import io.github.mangi.eta.ui.model.AgentSkillsUiState
import io.github.mangi.eta.ui.model.SkillItemUi
import io.github.mangi.eta.ui.model.canDeleteUserSkill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
fun AgentSkillsScreen(
    state: AgentSkillsUiState,
    onAction: (AgentSkillsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var deleteTarget by remember { mutableStateOf<SkillItemUi?>(null) }
    val scope = rememberCoroutineScope()
    val githubToken by SettingsDataStore.githubTokenFlow().collectAsState(initial = null)
    var showTokenDialog by remember { mutableStateOf(false) }
    var editingToken by remember { mutableStateOf("") }
    val operationPending = state.isImporting || state.isCheckingUpdates || state.isUpdatingSkills || state.busySkillId != null
    val zipPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) onAction(AgentSkillsAction.ImportZip(uri.toString()))
    }
    val openZipPicker = {
        zipPicker.launch(
            arrayOf(
                "application/zip",
                "application/x-zip-compressed",
                "application/octet-stream",
            ),
        )
    }

    val installed = state.skills.filter { it.installed }
    val builtinInstalled = installed.filter { it.source == "builtin" }
    val userInstalled = installed.filter { it.canDeleteUserSkill }
    val removed = state.skills.filter { !it.installed }

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiScaffoldPage(
                title = stringResource(R.string.route_skills),
                onBack = { onAction(AgentSkillsAction.NavigateBack) },
                modifier = modifier,
            ) {
                Group(title = stringResource(R.string.ui_install_087db6)) {
                    CouiPreference(
                        title = if (state.isImporting) stringResource(R.string.skills_checking_package) else stringResource(R.string.skills_import_zip),
                        summary = if (state.isImporting) {
                            stringResource(R.string.skills_installing_package)
                        } else {
                            stringResource(R.string.skills_choose_package)
                        },
                        trailing = if (state.isImporting) {
                            { InfiniteProgressIndicator(size = 20.dp) }
                        } else {
                            null
                        },
                        enabled = !operationPending,
                        onClick = openZipPicker,
                    )
                    Divider()
                    CouiPreference(
                        title = "GitHub 访问令牌 (Token)",
                        summary = if (githubToken.isNullOrBlank()) {
                            "未配置（受每小时 60 次 API 限流限制）"
                        } else {
                            "已配置访问令牌（享受 5,000 次/小时高配额）"
                        },
                        enabled = !operationPending,
                        onClick = {
                            editingToken = githubToken.orEmpty()
                            showTokenDialog = true
                        },
                    )
                    Divider()
                    CouiPreference(
                        title = if (state.isCheckingUpdates) {
                            "正在检查技能更新…"
                        } else if (state.isUpdatingSkills) {
                            "正在更新技能…"
                        } else {
                            "检查技能更新"
                        },
                        summary = "检查通过 GitHub 安装的技能是否有新版本",
                        trailing = if (state.isCheckingUpdates || state.isUpdatingSkills) {
                            { InfiniteProgressIndicator(size = 20.dp) }
                        } else {
                            null
                        },
                        enabled = !operationPending,
                        onClick = { onAction(AgentSkillsAction.CheckSkillUpdates(null)) },
                    )
                }

                if (builtinInstalled.isNotEmpty()) {
                    Group(title = stringResource(R.string.ui_built_in_skills_1ceedf)) {
                        builtinInstalled.forEachIndexed { index, skill ->
                            SkillSwitchRow(
                                skill = skill,
                                enabled = !operationPending,
                                onToggle = { enabled ->
                                    onAction(AgentSkillsAction.ToggleSkill(skill.id, enabled))
                                },
                                onEditSkill = { onAction(AgentSkillsAction.EditSkill(skill.id)) },
                                onResetBuiltin = { onAction(AgentSkillsAction.ResetBuiltin(skill.id)) },
                            )
                            if (index < builtinInstalled.lastIndex) {
                                Divider()
                            }
                        }
                    }
                }

                if (userInstalled.isNotEmpty()) {
                    Group(title = stringResource(R.string.ui_user_skills_748e7f)) {
                        userInstalled.forEachIndexed { index, skill ->
                            SkillSwitchRow(
                                skill = skill,
                                enabled = !operationPending,
                                onToggle = { enabled ->
                                    onAction(AgentSkillsAction.ToggleSkill(skill.id, enabled))
                                },
                                onEditSkill = { onAction(AgentSkillsAction.EditSkill(skill.id)) },
                                onCheckUpdate = if (skill.canCheckUpdate) ({
                                    onAction(AgentSkillsAction.CheckSkillUpdates(skill.id))
                                }) else null,
                                onDelete = { deleteTarget = skill },
                            )
                            if (index < userInstalled.lastIndex) {
                                Divider()
                            }
                        }
                    }
                }

                if (removed.isNotEmpty()) {
                    Group(title = stringResource(R.string.ui_removed_4e5c49)) {
                        removed.forEachIndexed { index, skill ->
                            CouiPreference(
                                title = skill.name,
                                summary = stringResource(R.string.ui_click_to_reinstall_dc60de),
                                enabled = !operationPending,
                                onClick = {
                                    onAction(AgentSkillsAction.ReinstallBuiltin(skill.id))
                                },
                            )
                            if (index < removed.lastIndex) {
                                Divider()
                            }
                        }
                    }
                }

                if (state.skills.isEmpty() && state.isLoading) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        InfiniteProgressIndicator(size = 24.dp)
                    }
                }
                if (state.skills.isEmpty() && !state.isLoading) {
                    ListEmptyState(
                        title = stringResource(R.string.ui_no_skills_installed_yet_4e960f),
                        summary = stringResource(R.string.skills_choose_package),
                        icon = Icons.Rounded.FolderZip,
                        action = {
                            CouiButton(
                                text = stringResource(R.string.skills_import_zip),
                                enabled = !operationPending,
                                role = CouiButtonRole.Primary,
                                onClick = openZipPicker,
                            )
                        },
                    )
                }

                CouiPageBottomSpacer()
            }
        }

        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(
                title = stringResource(R.string.route_skills),
                onBack = { onAction(AgentSkillsAction.NavigateBack) },
                modifier = modifier,
            ) {
                item(key = "zip-import") {
                    SmallTitle(stringResource(R.string.ui_install_087db6))
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        ArrowPreference(
                            title = if (state.isImporting) stringResource(R.string.skills_checking_package) else stringResource(R.string.skills_import_zip),
                            summary = if (state.isImporting) {
                                stringResource(R.string.skills_installing_package)
                            } else {
                                stringResource(R.string.skills_choose_package)
                            },
                            endActions = {
                                if (state.isImporting) {
                                    InfiniteProgressIndicator(size = 20.dp)
                                }
                            },
                            enabled = !operationPending,
                            onClick = openZipPicker,
                        )
                        ArrowPreference(
                            title = "GitHub 访问令牌 (Token)",
                            summary = if (githubToken.isNullOrBlank()) {
                                "未配置（受每小时 60 次 API 限流限制）"
                            } else {
                                "已配置访问令牌（享受 5,000 次/小时高配额）"
                            },
                            enabled = !operationPending,
                            onClick = {
                                editingToken = githubToken.orEmpty()
                                showTokenDialog = true
                            },
                        )
                        ArrowPreference(
                            title = if (state.isCheckingUpdates) {
                                "正在检查技能更新…"
                            } else if (state.isUpdatingSkills) {
                                "正在更新技能…"
                            } else {
                                "检查技能更新"
                            },
                            summary = "检查通过 GitHub 安装的技能是否有新版本",
                            endActions = {
                                if (state.isCheckingUpdates || state.isUpdatingSkills) {
                                    InfiniteProgressIndicator(size = 20.dp)
                                }
                            },
                            enabled = !operationPending,
                            onClick = { onAction(AgentSkillsAction.CheckSkillUpdates(null)) },
                        )
                    }
                }

                if (builtinInstalled.isNotEmpty()) {
                    item(key = "builtin") {
                        SmallTitle(stringResource(R.string.ui_built_in_skills_1ceedf))
                        Card(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 12.dp),
                        ) {
                            builtinInstalled.forEach { skill ->
                                SkillSwitchRow(
                                    skill = skill,
                                    enabled = !operationPending,
                                    onToggle = { enabled ->
                                        onAction(AgentSkillsAction.ToggleSkill(skill.id, enabled))
                                    },
                                    onEditSkill = { onAction(AgentSkillsAction.EditSkill(skill.id)) },
                                    onResetBuiltin = { onAction(AgentSkillsAction.ResetBuiltin(skill.id)) },
                                )
                            }
                        }
                    }
                }

                if (userInstalled.isNotEmpty()) {
                    item(key = "user") {
                        SmallTitle(stringResource(R.string.ui_user_skills_748e7f))
                        Card(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 12.dp),
                        ) {
                            userInstalled.forEach { skill ->
                                SkillSwitchRow(
                                    skill = skill,
                                    enabled = !operationPending,
                                    onToggle = { enabled ->
                                        onAction(AgentSkillsAction.ToggleSkill(skill.id, enabled))
                                    },
                                    onEditSkill = { onAction(AgentSkillsAction.EditSkill(skill.id)) },
                                    onCheckUpdate = if (skill.canCheckUpdate) ({
                                        onAction(AgentSkillsAction.CheckSkillUpdates(skill.id))
                                    }) else null,
                                    onDelete = { deleteTarget = skill },
                                )
                            }
                        }
                    }
                }

                if (removed.isNotEmpty()) {
                    item(key = "removed") {
                        SmallTitle(stringResource(R.string.ui_removed_4e5c49))
                        Card(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 12.dp),
                        ) {
                            removed.forEach { skill ->
                                BasicComponent(
                                    title = skill.name,
                                    summary = stringResource(R.string.ui_click_to_reinstall_dc60de),
                                    enabled = !operationPending,
                                    onClick = {
                                        onAction(AgentSkillsAction.ReinstallBuiltin(skill.id))
                                    },
                                )
                            }
                        }
                    }
                }

                if (state.skills.isEmpty() && state.isLoading) {
                    item(key = "loading") {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            InfiniteProgressIndicator(size = 24.dp)
                        }
                    }
                }
                if (state.skills.isEmpty() && !state.isLoading) {
                    item(key = "empty") {
                        ListEmptyState(
                            title = stringResource(R.string.ui_no_skills_installed_yet_4e960f),
                            summary = stringResource(R.string.skills_choose_package),
                            icon = Icons.Rounded.FolderZip,
                            action = {
                                TextButton(
                                    text = stringResource(R.string.skills_import_zip),
                                    enabled = !operationPending,
                                    onClick = openZipPicker,
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    state.replacement?.let { replacement ->
        when (LocalUiStyle.current) {
            AppearanceUiStyle.COUI -> {
                CouiConfirmDialog(
                    visible = true,
                    title = stringResource(R.string.ui_replace_user_skills_250e98),
                    message = stringResource(R.string.skills_replace_summary, replacement.name, replacement.id),
                    confirmText = stringResource(R.string.skills_replace),
                    confirmEnabled = !operationPending,
                    onConfirm = { onAction(AgentSkillsAction.ConfirmZipReplacement) },
                    onDismissRequest = { onAction(AgentSkillsAction.CancelZipReplacement) },
                )
            }
            AppearanceUiStyle.MIUIX -> {
                WindowDialog(
                    show = true,
                    title = stringResource(R.string.ui_replace_user_skills_250e98),
                    summary = stringResource(R.string.skills_replace_summary, replacement.name, replacement.id),
                    onDismissRequest = { onAction(AgentSkillsAction.CancelZipReplacement) },
                ) {
                    MiuixDialogActions(
                        confirmText = stringResource(R.string.skills_replace),
                        confirmEnabled = !operationPending,
                        onCancel = { onAction(AgentSkillsAction.CancelZipReplacement) },
                        onConfirm = { onAction(AgentSkillsAction.ConfirmZipReplacement) },
                    )
                }
            }
        }
    }

    if (state.pendingUpdates.isNotEmpty()) {
        val summaryText = buildString {
            append("发现以下技能有新版本可用：\n")
            state.pendingUpdates.forEach { candidate ->
                val oldSha = candidate.currentCommitSha.take(7)
                val newSha = candidate.remoteCommitSha.take(7)
                append("\n• ${candidate.name} (${candidate.repository}): $oldSha → $newSha")
            }
            if (state.pendingUpdates.any { it.isLocallyModified }) {
                append("\n\n⚠️ 检测到本地修改，更新将覆盖本地修改的内容")
            }
        }
        when (LocalUiStyle.current) {
            AppearanceUiStyle.COUI -> {
                CouiConfirmDialog(
                    visible = true,
                    title = "发现技能更新",
                    message = summaryText,
                    confirmText = "立即更新",
                    confirmEnabled = !operationPending,
                    cancelEnabled = !operationPending,
                    onConfirm = { onAction(AgentSkillsAction.ConfirmSkillUpdates) },
                    onDismissRequest = { onAction(AgentSkillsAction.CancelSkillUpdates) },
                )
            }
            AppearanceUiStyle.MIUIX -> {
                WindowDialog(
                    show = true,
                    title = "发现技能更新",
                    summary = summaryText,
                    onDismissRequest = { onAction(AgentSkillsAction.CancelSkillUpdates) },
                ) {
                    MiuixDialogActions(
                        confirmText = "立即更新",
                        confirmEnabled = !operationPending,
                        onCancel = { onAction(AgentSkillsAction.CancelSkillUpdates) },
                        onConfirm = { onAction(AgentSkillsAction.ConfirmSkillUpdates) },
                    )
                }
            }
        }
    }

    if (showTokenDialog) {
        val tokenSummary = "配置 GitHub Personal Access Token (PAT)，用于 skills_inspect_github 与 skills_install_from_github 解除 60 次/小时匿名 API 限流。保存空白内容可清除令牌。"
        when (LocalUiStyle.current) {
            AppearanceUiStyle.COUI -> {
                CouiConfirmDialog(
                    visible = true,
                    title = "GitHub 访问令牌",
                    message = tokenSummary,
                    confirmText = stringResource(R.string.action_save),
                    onConfirm = {
                        val tokenToSave = editingToken.trim().takeIf(String::isNotBlank)
                        scope.launch(Dispatchers.IO) {
                            SettingsDataStore.setGithubToken(tokenToSave)
                        }
                        showTokenDialog = false
                    },
                    onDismissRequest = { showTokenDialog = false },
                ) {
                    CouiTextField(
                        value = editingToken,
                        onValueChange = { editingToken = it },
                        label = "GitHub Token (ghp_... / github_pat_...)",
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                }
            }
            AppearanceUiStyle.MIUIX -> {
                WindowDialog(
                    show = true,
                    title = "GitHub 访问令牌",
                    summary = tokenSummary,
                    onDismissRequest = { showTokenDialog = false },
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextField(
                            value = editingToken,
                            onValueChange = { editingToken = it },
                            label = "GitHub Token (ghp_... / github_pat_...)",
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        MiuixDialogActions(
                            confirmText = stringResource(R.string.action_save),
                            onCancel = { showTokenDialog = false },
                            onConfirm = {
                                val tokenToSave = editingToken.trim().takeIf(String::isNotBlank)
                                scope.launch(Dispatchers.IO) {
                                    SettingsDataStore.setGithubToken(tokenToSave)
                                }
                                showTokenDialog = false
                            },
                        )
                    }
                }
            }
        }
    }

    deleteTarget?.let { skill ->
        val onConfirmDelete: () -> Unit = {
            deleteTarget = null
            onAction(AgentSkillsAction.DeleteSkill(skill.id))
        }
        when (LocalUiStyle.current) {
            AppearanceUiStyle.COUI -> {
                CouiConfirmDialog(
                    visible = true,
                    title = stringResource(R.string.ui_delete_user_skills_f319a9),
                    message = stringResource(R.string.skills_delete_summary, skill.name),
                    confirmText = stringResource(R.string.ui_delete_3755f5),
                    confirmEnabled = !operationPending,
                    destructive = true,
                    onConfirm = onConfirmDelete,
                    onDismissRequest = { deleteTarget = null },
                )
            }
            AppearanceUiStyle.MIUIX -> {
                WindowDialog(
                    show = true,
                    title = stringResource(R.string.ui_delete_user_skills_f319a9),
                    summary = stringResource(R.string.skills_delete_summary, skill.name),
                    onDismissRequest = { deleteTarget = null },
                ) {
                    MiuixDialogActions(
                        confirmText = stringResource(R.string.ui_delete_3755f5),
                        destructive = true,
                        confirmEnabled = !operationPending,
                        onCancel = { deleteTarget = null },
                        onConfirm = onConfirmDelete,
                    )
                }
            }
        }
    }

    state.notice?.let { notice ->
        when (LocalUiStyle.current) {
            AppearanceUiStyle.COUI -> {
                CouiNoticeDialog(
                    visible = true,
                    title = notice.title,
                    message = notice.message,
                    confirmText = stringResource(R.string.ui_knew_cb63c6),
                    onDismissRequest = { onAction(AgentSkillsAction.DismissNotice) },
                )
            }
            AppearanceUiStyle.MIUIX -> {
                WindowDialog(
                    show = true,
                    title = notice.title,
                    summary = notice.message,
                    onDismissRequest = { onAction(AgentSkillsAction.DismissNotice) },
                ) {
                    TextButton(
                        text = stringResource(R.string.ui_knew_cb63c6),
                        onClick = { onAction(AgentSkillsAction.DismissNotice) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = if (notice.isError) {
                            ButtonDefaults.textButtonColors()
                        } else {
                            ButtonDefaults.textButtonColorsPrimary()
                        },
                    )
                }
            }
        }
    }
}
