package io.github.mangi.eta.ui.screens.skills

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.m3.Markdown
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.skill.SkillParser
import io.github.mangi.eta.ui.app.AgentAppState
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

private val CardHorizontalPadding = 12.dp
private val CardBottomPadding = 12.dp

/**
 * SKILL.md 编辑与预览双模页面。
 */
@Composable
internal fun SkillEditorScreen(
    skillId: String,
    agentState: AgentAppState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val initialContent = remember(skillId) {
        agentState.loadSkillFileContent(skillId).orEmpty()
    }
    val skillItem = agentState.skillsState.skills.firstOrNull { it.id == skillId }
    val skillName = skillItem?.name?.takeIf { it.isNotBlank() } ?: skillId

    SkillEditorScreen(
        skillId = skillId,
        skillName = skillName,
        initialContent = initialContent,
        onSave = { newContent -> agentState.saveSkillFileContent(skillId, newContent) },
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
fun SkillEditorScreen(
    skillId: String,
    skillName: String = skillId,
    initialContent: String,
    onSave: suspend (String) -> Result<Unit>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var textFieldValue by remember(initialContent) {
        mutableStateOf(TextFieldValue(initialContent))
    }
    var hasChanges by remember { mutableStateOf(false) }
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showSuccessNotice by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    val parsed = remember(textFieldValue.text) {
        SkillParser.parseSkillContent(textFieldValue.text)
    }

    val pageTitle = if (skillName.isNotBlank() && skillName != skillId) {
        "编辑 $skillName"
    } else {
        "编辑 SKILL.md"
    }

    MiuixScaffoldPage(
        title = pageTitle,
        onBack = onBack,
        actions = {
            if (isSaving) {
                Box(
                    modifier = Modifier
                        .size(width = 48.dp, height = 36.dp)
                        .padding(end = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    InfiniteProgressIndicator(size = 20.dp)
                }
            } else {
                TextButton(
                    text = "保存",
                    enabled = hasChanges && !isSaving,
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        scope.launch {
                            isSaving = true
                            errorMessage = null
                            val result = onSave(textFieldValue.text)
                            isSaving = false
                            if (result.isSuccess) {
                                hasChanges = false
                                showSuccessNotice = true
                            } else {
                                errorMessage = result.exceptionOrNull()?.message ?: "保存失败"
                            }
                        }
                    },
                )
            }
        },
        modifier = modifier,
    ) {
        item(key = "mode-switcher") {
            TabRow(
                tabs = listOf("编辑", "预览"),
                selectedTabIndex = selectedTabIndex,
                onTabSelected = { selectedTabIndex = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CardHorizontalPadding, vertical = 6.dp),
            )
        }

        if (selectedTabIndex == 0) {
            // 编辑模式
            val lines = textFieldValue.text.lines()
            val lineCount = lines.size.coerceAtLeast(1)

            item(key = "editor-title") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = CardHorizontalPadding + 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SmallTitle("SKILL.md")
                    Text(
                        text = "$lineCount 行 · ${textFieldValue.text.length} 字",
                        style = MiuixTheme.textStyles.body2.copy(fontSize = 12.sp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }

            item(key = "editor-card") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = CardHorizontalPadding)
                        .padding(bottom = CardBottomPadding)
                        .imePadding(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                    ) {
                        // 行号边栏
                        val gutterNumbers = (1..lineCount).joinToString("\n")
                        Text(
                            text = gutterNumbers,
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                lineHeight = 20.sp,
                                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                            ),
                            modifier = Modifier
                                .padding(start = 12.dp, end = 10.dp),
                        )

                        // 多行输入区
                        BasicTextField(
                            value = textFieldValue,
                            onValueChange = {
                                textFieldValue = it
                                hasChanges = true
                            },
                            textStyle = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                lineHeight = 20.sp,
                                color = MiuixTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = 12.dp)
                                .defaultMinSize(minHeight = 400.dp),
                        )
                    }
                }
            }
        } else {
            // 预览模式
            item(key = "preview-metadata") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = CardHorizontalPadding)
                        .padding(bottom = 8.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        val parsedName = parsed?.frontmatter?.get("name")
                        val parsedDesc = parsed?.frontmatter?.get("description")
                        val hasValidName = !parsedName.isNullOrBlank()

                        Text(
                            text = if (hasValidName) "技能名称: $parsedName" else "⚠️ 缺少有效的 name 字段",
                            style = MiuixTheme.textStyles.headline1,
                            fontWeight = FontWeight.Medium,
                            color = if (hasValidName) MiuixTheme.colorScheme.onBackground else MiuixTheme.colorScheme.error,
                        )

                        if (!parsedDesc.isNullOrBlank()) {
                            Text(
                                text = parsedDesc,
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }

                        val otherMetadata = parsed?.frontmatter?.filterKeys { it != "name" && it != "description" }.orEmpty()
                        if (otherMetadata.isNotEmpty()) {
                            Text(
                                text = otherMetadata.entries.joinToString(" | ") { "${it.key}: ${it.value}" },
                                style = MiuixTheme.textStyles.body2.copy(fontSize = 12.sp),
                                color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }

            item(key = "preview-body-title") {
                SmallTitle("正文预览")
            }

            item(key = "preview-card") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = CardHorizontalPadding)
                        .padding(bottom = CardBottomPadding),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        val bodyText = parsed?.body?.ifBlank { null }
                            ?: textFieldValue.text.ifBlank { "（内容为空）" }
                        Markdown(
                            content = bodyText,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }

    if (errorMessage != null) {
        WindowDialog(
            show = true,
            title = "保存失败",
            summary = errorMessage ?: "",
            onDismissRequest = { errorMessage = null },
        ) {
            TextButton(
                text = stringResource(R.string.ui_knew_cb63c6),
                onClick = { errorMessage = null },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (showSuccessNotice) {
        WindowDialog(
            show = true,
            title = "保存成功",
            summary = "SKILL.md 已成功保存并更新索引",
            onDismissRequest = { showSuccessNotice = false },
        ) {
            TextButton(
                text = stringResource(R.string.ui_knew_cb63c6),
                onClick = { showSuccessNotice = false },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }
    }
}
