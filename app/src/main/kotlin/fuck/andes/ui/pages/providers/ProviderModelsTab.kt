package fuck.andes.ui.pages.providers

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import fuck.andes.FuckAndesApp
import fuck.andes.data.model.Model
import fuck.andes.data.model.ModelReasoningCapabilities
import fuck.andes.data.model.ProviderSetting
import fuck.andes.data.model.ReasoningEffort
import fuck.andes.data.repository.ModelRepository
import fuck.andes.data.repository.RemoteModelFetcher
import fuck.andes.data.repository.RuntimeConfigRepository
import fuck.andes.ui.components.MiuixDialogActions
import fuck.andes.ui.components.StatusError
import fuck.andes.ui.components.StatusSuccess
import fuck.andes.ui.model.formatCompactTokenCount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.CheckboxLocation
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

private val modelSearchSeparators = Regex("""[^\p{L}\p{N}]+""")
private val editableReasoningEfforts = listOf(
    ReasoningEffort.OFF,
    ReasoningEffort.MINIMAL,
    ReasoningEffort.LOW,
    ReasoningEffort.MEDIUM,
    ReasoningEffort.HIGH,
    ReasoningEffort.XHIGH,
    ReasoningEffort.MAX,
)

internal fun contextWindowInputError(value: String): String? {
    val normalized = value.trim()
    if (normalized.isEmpty()) return null
    return if (normalized.toIntOrNull()?.let { it > 0 } == true) {
        null
    } else {
        "上下文长度必须是正整数"
    }
}

internal fun filterProviderModels(models: List<Model>, query: String): List<Model> {
    val queryTokens = query.lowercase().split(modelSearchSeparators).filter(String::isNotBlank)
    return models
        .sortedBy { it.sortOrder }
        .filter { model ->
            if (queryTokens.isEmpty()) {
                true
            } else {
                val searchableFields = listOf(model.displayName, model.modelId).map { field ->
                    field.lowercase().filter(Char::isLetterOrDigit)
                }
                queryTokens.all { token ->
                    searchableFields.any { field -> field.containsCharactersInOrder(token) }
                }
            }
        }
}

private fun String.containsCharactersInOrder(query: String): Boolean {
    var queryIndex = 0
    for (character in this) {
        if (character == query[queryIndex]) {
            queryIndex++
            if (queryIndex == query.length) return true
        }
    }
    return false
}

@Composable
internal fun ProviderModelsTab(
    provider: ProviderSetting,
    scope: CoroutineScope,
    scrollBehavior: ScrollBehavior,
) {
    val selectedModelId by RuntimeConfigRepository.selectedModelIdFlow().collectAsState(initial = null)
    var isFetching by remember { mutableStateOf(false) }
    var isMutatingModel by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var editingModel by remember { mutableStateOf<Model?>(null) }
    var isCreatingModel by remember { mutableStateOf(false) }
    var editorError by remember { mutableStateOf<String?>(null) }
    var modelPendingDelete by remember { mutableStateOf<Model?>(null) }
    var selectionMode by remember(provider.id) { mutableStateOf(false) }
    var selectedModelIds by remember(provider.id) { mutableStateOf(setOf<String>()) }
    var showBatchDeleteDialog by remember { mutableStateOf(false) }
    var modelSearchQuery by remember(provider.id) { mutableStateOf("") }
    val normalizedModelSearchQuery = modelSearchQuery.trim()
    val filteredModels = remember(provider.models, modelSearchQuery) {
        filterProviderModels(provider.models, modelSearchQuery)
    }

    BackHandler(enabled = selectionMode) {
        selectionMode = false
        selectedModelIds = emptySet()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical()
                .scrollEndHaptic()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            overscrollEffect = null,
        ) {
            item(key = "actions", contentType = "section") {
                ProviderSection(title = "模型管理") {
                    ArrowPreference(
                        title = if (isFetching) "拉取中..." else "从远端自动拉取",
                        summary = "读取 ${provider.baseUrl} 的 /models 列表",
                        enabled = !isFetching && !isMutatingModel,
                        startAction = {
                            ProviderRoundIcon(
                                icon = LucideR.drawable.lucide_ic_cloud_download,
                                tint = MiuixTheme.colorScheme.primary,
                            )
                        },
                        onClick = {
                            scope.launch {
                                isFetching = true
                                message = null
                                try {
                                    val models = RemoteModelFetcher.fetch(provider).getOrElse { throwable ->
                                        message = "失败：${throwable.message ?: throwable.javaClass.simpleName}"
                                        return@launch
                                    }
                                    val chatModels = models.filter(RemoteModelFetcher::isChatCapableModel)
                                    val sync = ModelRepository.syncRemoteModels(provider.id, chatModels)
                                    if (sync.applied) {
                                        RuntimeConfigRepository.syncToRemotePreferences(FuckAndesApp.serviceInstance)
                                    }
                                    val filteredCount = models.size - chatModels.size
                                    message = if (!sync.applied) {
                                        "远端未返回可用对话模型，已保留现有模型"
                                    } else if (filteredCount > 0) {
                                        "已拉取 ${chatModels.size} 个模型，过滤 $filteredCount 个非对话模型"
                                    } else {
                                        "已拉取 ${chatModels.size} 个模型"
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (throwable: Throwable) {
                                    message = "失败：${throwable.message ?: "同步失败"}"
                                } finally {
                                    isFetching = false
                                }
                            }
                        },
                    )
                    ProviderDivider()
                    ArrowPreference(
                        title = "添加自定义模型",
                        summary = "手动填写展示名称与 Model ID",
                        enabled = !isFetching && !isMutatingModel,
                        startAction = {
                            ProviderRoundIcon(
                                icon = LucideR.drawable.lucide_ic_plus,
                                tint = MiuixTheme.colorScheme.primary,
                            )
                        },
                        onClick = {
                            editorError = null
                            isCreatingModel = true
                            editingModel = Model(
                                id = "",
                                modelId = "",
                                displayName = "自定义模型",
                            )
                        },
                    )
                    message?.let {
                        HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                        Text(
                            text = it,
                            style = MiuixTheme.textStyles.footnote2,
                            color = if (it.startsWith("失败")) StatusError else StatusSuccess,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
            }

            item(key = "model_search", contentType = "search") {
                InputField(
                    query = modelSearchQuery,
                    onQueryChange = { modelSearchQuery = it },
                    onSearch = {},
                    expanded = false,
                    onExpandedChange = {},
                    label = "搜索模型",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(top = 12.dp, bottom = 8.dp),
                )
            }

            val modelListTitle = if (normalizedModelSearchQuery.isBlank()) {
                "模型列表 (共 ${provider.models.size} 个)"
            } else {
                "模型列表 (匹配 ${filteredModels.size} / ${provider.models.size} 个)"
            }
            if (provider.models.isEmpty() || filteredModels.isEmpty()) {
                item(key = "models_empty", contentType = "empty") {
                    ProviderSection(
                        title = modelListTitle,
                        modifier = Modifier.padding(bottom = 24.dp),
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (provider.models.isEmpty()) {
                                    "暂无模型，请从远端拉取或手动添加"
                                } else {
                                    "未找到匹配的模型"
                                },
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                }
            } else {
                item(key = "models_title", contentType = "section_title") {
                    SmallTitle(modelListTitle)
                }
                itemsIndexed(
                    items = filteredModels,
                    key = { _, model -> "model:${model.id}" },
                    contentType = { _, _ -> "model" },
                ) { index, model ->
                    ModelListGroupItem(
                        isFirst = index == 0,
                        isLast = index == filteredModels.lastIndex,
                    ) {
                        ModelListItem(
                            model = model,
                            enabled = !isFetching && !isMutatingModel,
                            isSelected = model.id == selectedModelId,
                            selectionMode = selectionMode,
                            checked = model.id in selectedModelIds,
                            onToggleChecked = {
                                selectedModelIds = if (model.id in selectedModelIds) {
                                    selectedModelIds - model.id
                                } else {
                                    selectedModelIds + model.id
                                }
                            },
                            onEnterSelection = {
                                selectionMode = true
                                selectedModelIds = setOf(model.id)
                            },
                            onEdit = {
                                editorError = null
                                isCreatingModel = false
                                editingModel = model
                            },
                            onSetCurrent = {
                                scope.launch {
                                    RuntimeConfigRepository.setSelectedModelId(model.id)
                                    RuntimeConfigRepository.syncToRemotePreferences(FuckAndesApp.serviceInstance)
                                }
                            },
                        )
                    }
                }
                item(key = "models_section_gap", contentType = "spacer") {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }

            item(key = "bottom_spacer", contentType = "spacer") {
                // 多选操作栏悬浮在底部时，预留高度避免遮挡最后一个列表项；其余情况与大圆角屏幕下沿保持间距
                Spacer(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .height(if (selectionMode) 88.dp else 24.dp),
                )
            }
        }

        AnimatedVisibility(
            visible = selectionMode,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            ModelSelectionBar(
                selectedCount = selectedModelIds.size,
                totalCount = provider.models.size,
                enabled = !isFetching && !isMutatingModel,
                onToggleAll = {
                    selectedModelIds = if (selectedModelIds.size == provider.models.size) {
                        emptySet()
                    } else {
                        provider.models.mapTo(mutableSetOf()) { it.id }
                    }
                },
                onDelete = { showBatchDeleteDialog = true },
                onExit = {
                    selectionMode = false
                    selectedModelIds = emptySet()
                },
            )
        }
    }

    editingModel?.let { model ->
        ModelEditDialog(
            model = model,
            isNew = isCreatingModel,
            isSaving = isMutatingModel,
            error = editorError,
            onDismiss = {
                if (!isMutatingModel) editingModel = null
            },
            onSubmit = { updated ->
                if (isMutatingModel) return@ModelEditDialog
                scope.launch {
                    isMutatingModel = true
                    editorError = null
                    try {
                        val saved = ModelRepository.saveModel(provider.id, updated)
                        RuntimeConfigRepository.syncToRemotePreferences(FuckAndesApp.serviceInstance)
                        editingModel = null
                        message = "已保存：${saved.displayName}"
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (throwable: Throwable) {
                        editorError = throwable.message ?: "保存失败"
                    } finally {
                        isMutatingModel = false
                    }
                }
            },
            onDelete = if (isCreatingModel) null else {
                {
                    modelPendingDelete = model
                    editingModel = null
                }
            },
        )
    }

    modelPendingDelete?.let { model ->
        OverlayDialog(
            show = true,
            title = "删除模型",
            summary = "删除「${model.displayName}」后将不可恢复。",
            onDismissRequest = { if (!isMutatingModel) modelPendingDelete = null },
        ) {
            MiuixDialogActions(
                confirmText = if (isMutatingModel) "删除中..." else "删除",
                cancelEnabled = !isMutatingModel,
                confirmEnabled = !isMutatingModel,
                destructive = true,
                onCancel = { modelPendingDelete = null },
                onConfirm = {
                    scope.launch {
                        isMutatingModel = true
                        try {
                            ModelRepository.deleteModel(provider.id, model.id)
                            RuntimeConfigRepository.syncToRemotePreferences(FuckAndesApp.serviceInstance)
                            message = "已删除：${model.displayName}"
                            modelPendingDelete = null
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (throwable: Throwable) {
                            message = "失败：${throwable.message ?: "删除失败"}"
                            modelPendingDelete = null
                        } finally {
                            isMutatingModel = false
                        }
                    }
                },
            )
        }
    }

    if (showBatchDeleteDialog) {
        OverlayDialog(
            show = true,
            title = "删除模型",
            summary = "删除选中的 ${selectedModelIds.size} 个模型后将不可恢复。",
            onDismissRequest = { if (!isMutatingModel) showBatchDeleteDialog = false },
        ) {
            MiuixDialogActions(
                confirmText = if (isMutatingModel) "删除中..." else "删除",
                cancelEnabled = !isMutatingModel,
                confirmEnabled = !isMutatingModel,
                destructive = true,
                onCancel = { showBatchDeleteDialog = false },
                onConfirm = {
                    scope.launch {
                        val deletedCount = selectedModelIds.size
                        isMutatingModel = true
                        try {
                            ModelRepository.deleteModels(provider.id, selectedModelIds)
                            RuntimeConfigRepository.syncToRemotePreferences(FuckAndesApp.serviceInstance)
                            message = "已删除 $deletedCount 个模型"
                            showBatchDeleteDialog = false
                            selectionMode = false
                            selectedModelIds = emptySet()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (throwable: Throwable) {
                            message = "失败：${throwable.message ?: "删除失败"}"
                            showBatchDeleteDialog = false
                        } finally {
                            isMutatingModel = false
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun ModelListGroupItem(
    isFirst: Boolean,
    isLast: Boolean,
    content: @Composable () -> Unit,
) {
    val surfaceColor = MiuixTheme.colorScheme.surfaceContainer
    val contentColor = MiuixTheme.colorScheme.onSurfaceContainer
    val cornerRadius = CardDefaults.CornerRadius
    val surfaceModifier = if (isFirst || isLast) {
        Modifier.squircleSurface(
            color = surfaceColor,
            topStart = if (isFirst) cornerRadius else 0.dp,
            topEnd = if (isFirst) cornerRadius else 0.dp,
            bottomEnd = if (isLast) cornerRadius else 0.dp,
            bottomStart = if (isLast) cornerRadius else 0.dp,
        )
    } else {
        Modifier.background(surfaceColor)
    }

    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .then(surfaceModifier),
        ) {
            content()
            if (!isLast) {
                HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            }
        }
    }
}

/** 多选模式底部悬浮操作栏：退出在左，已选数量其次，全选与删除在右；删除沿用统一破坏性配色。 */
@Composable
private fun ModelSelectionBar(
    selectedCount: Int,
    totalCount: Int,
    enabled: Boolean,
    onToggleAll: () -> Unit,
    onDelete: () -> Unit,
    onExit: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onExit, enabled = enabled) {
                Icon(
                    painter = painterResource(LucideR.drawable.lucide_ic_x),
                    contentDescription = "退出多选",
                    tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
            }
            Text(
                text = "已选 $selectedCount 个",
                style = MiuixTheme.textStyles.body2,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                text = if (selectedCount == totalCount) "全不选" else "全选",
                enabled = enabled,
                onClick = onToggleAll,
            )
            TextButton(
                text = "删除",
                enabled = selectedCount > 0 && enabled,
                colors = ButtonDefaults.textButtonColorsPrimary(
                    color = MiuixTheme.colorScheme.error,
                    textColor = MiuixTheme.colorScheme.onError,
                ),
                onClick = onDelete,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModelListItem(
    model: Model,
    enabled: Boolean,
    isSelected: Boolean,
    selectionMode: Boolean,
    checked: Boolean,
    onToggleChecked: () -> Unit,
    onEnterSelection: () -> Unit,
    onEdit: () -> Unit,
    onSetCurrent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                enabled = enabled,
                onClick = if (selectionMode) onToggleChecked else onSetCurrent,
                onLongClick = {
                    if (selectionMode) onToggleChecked() else onEnterSelection()
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.displayName,
                style = MiuixTheme.textStyles.headline1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = model.modelId,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 6.dp),
            ) {
                capabilityTags(model).forEach { tag ->
                    TagChip(text = tag)
                }
                if (isSelected) {
                    TagChip(text = "当前", tone = TagChipTone.Emphasized)
                }
            }
        }
        if (selectionMode) {
            Checkbox(
                state = if (checked) ToggleableState.On else ToggleableState.Off,
                onClick = onToggleChecked,
                enabled = enabled,
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onEdit, enabled = enabled) {
                    Icon(
                        painter = painterResource(LucideR.drawable.lucide_ic_sliders_horizontal),
                        contentDescription = "编辑模型参数",
                        tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                }
                IconButton(onClick = onSetCurrent, enabled = enabled) {
                    Icon(
                        painter = painterResource(
                            if (isSelected) LucideR.drawable.lucide_ic_check
                            else LucideR.drawable.lucide_ic_circle
                        ),
                        contentDescription = if (isSelected) "当前模型" else "设为当前模型",
                        tint = if (isSelected) {
                            MiuixTheme.colorScheme.primary
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantActions
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelEditDialog(
    model: Model,
    isNew: Boolean,
    isSaving: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (Model) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var displayName by remember(model.id, isNew) { mutableStateOf(model.displayName) }
    var modelId by remember(model.id, isNew) { mutableStateOf(model.modelId) }
    var contextWindowOverrideText by remember(model.id, isNew) {
        mutableStateOf(model.contextWindowOverride?.toString().orEmpty())
    }
    var reasoningOverrideActive by remember(model.id, isNew) {
        mutableStateOf(
            model.reasoningOverride != null || model.reasoningCapabilitiesOverride != null
        )
    }
    var reasoningEnabled by remember(model.id, isNew) {
        mutableStateOf(model.supportsReasoning)
    }
    var selectedReasoningEfforts by remember(model.id, isNew) {
        mutableStateOf(
            model.effectiveReasoningCapabilities
                ?.selectableEfforts
                ?.toSet()
                .orEmpty() + ReasoningEffort.DEFAULT
        )
    }
    val contextError = contextWindowInputError(contextWindowOverrideText)

    fun resetAutomaticReasoning() {
        reasoningOverrideActive = false
        reasoningEnabled = model.reasoning == true
        selectedReasoningEfforts = model.reasoningCapabilities
            ?.selectableEfforts
            ?.toSet()
            .orEmpty() + ReasoningEffort.DEFAULT
    }

    fun updated(): Model = model.copy(
        displayName = displayName.trim(),
        modelId = modelId.trim(),
        contextWindowOverride = contextWindowOverrideText.trim()
            .takeIf(String::isNotEmpty)
            ?.toInt(),
        reasoningOverride = reasoningEnabled.takeIf { reasoningOverrideActive },
        reasoningCapabilitiesOverride = if (reasoningOverrideActive && reasoningEnabled) {
            val canDisable = ReasoningEffort.OFF in selectedReasoningEfforts
            (model.effectiveReasoningCapabilities ?: ModelReasoningCapabilities()).copy(
                supportedEfforts = editableReasoningEfforts.filter { effort ->
                    effort != ReasoningEffort.OFF && effort in selectedReasoningEfforts
                },
                defaultEffort = model.effectiveReasoningCapabilities
                    ?.defaultEffort
                    ?.takeIf { it in selectedReasoningEfforts },
                defaultEnabled = true,
                mandatory = !canDisable,
                canDisable = canDisable,
            )
        } else {
            null
        },
    )

    OverlayDialog(
        show = true,
        title = if (isNew) "添加模型" else "编辑模型",
        onDismissRequest = { if (!isSaving) onDismiss() },
    ) {
        Column {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                TextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = "展示名称",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(12.dp))
                TextField(
                    value = modelId,
                    onValueChange = { modelId = it },
                    label = "Model ID",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(12.dp))
                TextField(
                    value = contextWindowOverrideText,
                    onValueChange = { contextWindowOverrideText = it },
                    label = "上下文长度（tokens）",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = when {
                            contextWindowOverrideText.isNotBlank() -> "已覆盖，将优先于远端元数据"
                            model.contextWindow != null ->
                                "自动：${formatCompactTokenCount(model.contextWindow)} tokens"
                            else -> "自动：远端未提供上下文上限"
                        },
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.weight(1f),
                    )
                    if (contextWindowOverrideText.isNotBlank()) {
                        TextButton(
                            text = "恢复自动",
                            enabled = !isSaving,
                            onClick = { contextWindowOverrideText = "" },
                        )
                    }
                }
                contextError?.let { validationError ->
                    Text(
                        text = validationError,
                        style = MiuixTheme.textStyles.footnote2,
                        color = StatusError,
                    )
                }
                Text(
                    text = "该值用于会话裁剪和上下文预算。留空时使用远端或官方目录值。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                )
                Card(modifier = Modifier.fillMaxWidth()) {
                    SwitchPreference(
                        checked = reasoningEnabled,
                        onCheckedChange = { enabled ->
                            reasoningOverrideActive = true
                            reasoningEnabled = enabled
                        },
                        title = "支持思考",
                        summary = if (reasoningOverrideActive) {
                            "已覆盖模型自动能力"
                        } else {
                            "自动：${if (model.reasoning == true) "支持" else "不支持或未知"}"
                        },
                        enabled = !isSaving,
                    )
                    if (reasoningEnabled) {
                        HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                        CheckboxPreference(
                            title = ReasoningEffort.DEFAULT.displayName,
                            summary = "由模型或 Provider 决定",
                            checked = true,
                            onCheckedChange = null,
                            checkboxLocation = CheckboxLocation.End,
                            enabled = false,
                        )
                        editableReasoningEfforts.forEach { effort ->
                            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                            CheckboxPreference(
                                title = effort.displayName,
                                summary = if (effort == ReasoningEffort.OFF) {
                                    "允许在对话中关闭思考"
                                } else {
                                    null
                                },
                                checked = effort in selectedReasoningEfforts,
                                onCheckedChange = { checked ->
                                    reasoningOverrideActive = true
                                    selectedReasoningEfforts = if (checked) {
                                        selectedReasoningEfforts + effort
                                    } else {
                                        selectedReasoningEfforts - effort
                                    }
                                },
                                checkboxLocation = CheckboxLocation.End,
                                enabled = !isSaving,
                            )
                        }
                    }
                }
                Text(
                    text = "仅勾选模型或网关实际支持的档位；不支持的组合会在请求前明确报错。",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 8.dp),
                )
                error?.let { message ->
                    Text(
                        text = message,
                        style = MiuixTheme.textStyles.footnote2,
                        color = StatusError,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (reasoningOverrideActive || onDelete != null) {
                    Row(
                        modifier = Modifier.padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (reasoningOverrideActive) {
                            Text(
                                text = "恢复自动",
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable(
                                        enabled = !isSaving,
                                        onClick = ::resetAutomaticReasoning,
                                    )
                                    .padding(vertical = 4.dp),
                            )
                        }
                        onDelete?.let { delete ->
                            Text(
                                text = "删除模型",
                                style = MiuixTheme.textStyles.body2,
                                color = MiuixTheme.colorScheme.error,
                                modifier = Modifier
                                    .clickable(enabled = !isSaving, onClick = delete)
                                    .padding(vertical = 4.dp),
                            )
                        }
                    }
                }
            }
        }
        MiuixDialogActions(
            confirmText = if (isSaving) "保存中..." else "保存",
            confirmEnabled = !isSaving &&
                displayName.isNotBlank() &&
                modelId.isNotBlank() &&
                contextError == null,
            cancelEnabled = !isSaving,
            onCancel = onDismiss,
            onConfirm = { onSubmit(updated()) },
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

private fun capabilityTags(model: Model): List<String> = buildList {
    add(
        model.effectiveContextWindow?.let { contextWindow ->
            "${formatCompactTokenCount(contextWindow)} 上下文"
        } ?: "上下文未知"
    )
    if (model.supportsReasoning) add("支持思考")
}
