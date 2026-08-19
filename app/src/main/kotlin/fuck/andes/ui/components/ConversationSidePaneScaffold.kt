package fuck.andes.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.composables.icons.lucide.R as LucideR
import fuck.andes.R
import fuck.andes.ui.model.ConversationPaneUiState
import fuck.andes.ui.model.ConversationSummaryUi
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.Surface
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowListPopup

private object DrawerMetrics {
    val PaneMaxWidth = 340.dp
    val PaneWidthFraction = 0.84f
    val EdgeSwipeWidth = 36.dp
    val PaneHorizontalPadding = 16.dp
    val TopInset = 16.dp
    val AfterActionBar = 18.dp
    val ListBottomPadding = 20.dp
    val BottomInset = 12.dp
    val ActionIconSize = 20.dp
    val SectionTopPadding = 8.dp
    val SectionBottomPadding = 10.dp
    val SectionIconSize = 14.dp
    val SectionIconGap = 8.dp
    val SectionCountGap = 12.dp
    val RowMinHeight = 48.dp
    val RowGap = 4.dp
    val RowCornerRadius = 12.dp
    val RowHorizontalPadding = 32.dp
    val RowVerticalPadding = 12.dp
    val ActiveDotSize = 6.dp
    val ActiveDotGap = 10.dp
    val EmptyVerticalPadding = 28.dp
    val DockTopGap = 14.dp
}

@Composable
fun ConversationSidePaneScaffold(
    state: ConversationPaneUiState,
    visible: Boolean,
    backHandlerEnabled: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onSearchChange: (String) -> Unit,
    onConversationSelected: (String) -> Unit,
    onConversationRename: (ConversationSummaryUi) -> Unit,
    onConversationDelete: (ConversationSummaryUi) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModelProviders: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenPermissions: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val sceneLifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val navigationEventState = rememberNavigationEventState(NavigationEventInfo.None)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val paneWidth = minOf(maxWidth * DrawerMetrics.PaneWidthFraction, DrawerMetrics.PaneMaxWidth)
        val edgeSwipeWidthPx = with(density) { DrawerMetrics.EdgeSwipeWidth.toPx() }
        val paneWidthPx = with(density) { paneWidth.toPx() }
        var dragging by remember { mutableStateOf(false) }
        var dragOffsetPx by remember { mutableFloatStateOf(0f) }
        var acceptsDrag by remember { mutableStateOf(false) }
        val animatedOffsetPx by animateFloatAsState(
            targetValue = if (visible) paneWidthPx else 0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
            label = "ConversationPaneOffset",
        )
        val offsetPx = if (dragging) dragOffsetPx else animatedOffsetPx
        val progress = if (paneWidthPx > 0f) {
            (offsetPx / paneWidthPx).coerceIn(0f, 1f)
        } else {
            0f
        }

        // NavDisplay 的退出条目在转场期间仍会保留组合；仅允许已稳定显示的首页
        // 处理侧栏返回，避免它抢先消费二级页面的第一次返回事件。
        NavigationBackHandler(
            state = navigationEventState,
            isBackEnabled = visible &&
                backHandlerEnabled &&
                sceneLifecycleState == Lifecycle.State.RESUMED,
            onBackCompleted = onDismiss,
        )

        ConversationPanePanel(
            state = state,
            width = paneWidth,
            onSearchChange = onSearchChange,
            onConversationSelected = onConversationSelected,
            onConversationRename = onConversationRename,
            onConversationDelete = onConversationDelete,
            onOpenSettings = onOpenSettings,
            onOpenModelProviders = onOpenModelProviders,
            onOpenTools = onOpenTools,
            onOpenSkills = onOpenSkills,
            onOpenPermissions = onOpenPermissions,
            modifier = Modifier.zIndex(0f),
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(offsetPx.roundToInt(), 0) }
                .pointerInput(visible, paneWidthPx) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            // 打开时仅在主内容区（右侧）接受拖拽关闭，避免拦截会话列表的长按；
                            // 关闭时仅从左缘拖拽打开。
                            acceptsDrag = if (visible) {
                                offset.x >= paneWidthPx - edgeSwipeWidthPx
                            } else {
                                offset.x <= edgeSwipeWidthPx
                            }
                            if (acceptsDrag) {
                                dragging = true
                                dragOffsetPx = animatedOffsetPx
                            }
                        },
                        onHorizontalDrag = { change: PointerInputChange, dragAmount: Float ->
                            if (acceptsDrag) {
                                change.consume()
                                dragOffsetPx = (dragOffsetPx + dragAmount).coerceIn(0f, paneWidthPx)
                            }
                        },
                        onDragEnd = {
                            if (acceptsDrag) {
                                if (dragOffsetPx >= paneWidthPx * 0.44f) {
                                    onOpen()
                                } else {
                                    onDismiss()
                                }
                            }
                            dragging = false
                            acceptsDrag = false
                        },
                        onDragCancel = {
                            dragging = false
                            acceptsDrag = false
                        },
                    )
                }
                .zIndex(1f),
        ) {
            content()
            if (progress > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            MiuixTheme.colorScheme.windowDimming.copy(
                                alpha = MiuixTheme.colorScheme.windowDimming.alpha * progress,
                            ),
                        )
                        .clickable(onClick = onDismiss),
                )
            }
        }
    }
}

@Composable
private fun ConversationPanePanel(
    state: ConversationPaneUiState,
    width: androidx.compose.ui.unit.Dp,
    onSearchChange: (String) -> Unit,
    onConversationSelected: (String) -> Unit,
    onConversationRename: (ConversationSummaryUi) -> Unit,
    onConversationDelete: (ConversationSummaryUi) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModelProviders: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenPermissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val query = state.searchQuery.trim()
    val visibleConversations = remember(state.conversations, query) {
        if (query.isBlank()) {
            state.conversations
        } else {
            state.conversations.filter { conversation ->
                conversation.title.contains(query, ignoreCase = true) ||
                    conversation.preview.contains(query, ignoreCase = true)
            }
        }
    }
    val groups = remember(visibleConversations) { visibleConversations.groupForDrawer() }

    Surface(
        modifier = modifier
            .width(width)
            .fillMaxHeight(),
        color = MiuixTheme.colorScheme.surface,
        contentColor = MiuixTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .safeDrawingPadding()
                .padding(horizontal = DrawerMetrics.PaneHorizontalPadding),
        ) {
            Spacer(modifier = Modifier.height(DrawerMetrics.TopInset))
            PaneActionBar(
                query = state.searchQuery,
                onSearchChange = onSearchChange,
            )
            Spacer(modifier = Modifier.height(DrawerMetrics.AfterActionBar))
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .scrollEndHaptic()
                    .overScrollVertical(),
                contentPadding = PaddingValues(bottom = DrawerMetrics.ListBottomPadding),
                verticalArrangement = Arrangement.spacedBy(DrawerMetrics.RowGap),
                overscrollEffect = null,
            ) {
                if (visibleConversations.isEmpty()) {
                    item {
                        EmptyConversations(isSearching = query.isNotBlank())
                    }
                } else {
                    groups.forEach { group ->
                        item(key = "section-${group.section}") {
                            ConversationSectionHeader(group = group)
                        }
                        items(
                            items = group.items,
                            key = { it.id },
                        ) { conversation ->
                            ConversationTextRow(
                                conversation = conversation,
                                selected = conversation.id == state.selectedConversationId,
                                onClick = { onConversationSelected(conversation.id) },
                                onRename = { onConversationRename(conversation) },
                                onDelete = { onConversationDelete(conversation) },
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(DrawerMetrics.DockTopGap))
            PaneDock(
                onOpenSettings = onOpenSettings,
                onOpenModelProviders = onOpenModelProviders,
                onOpenTools = onOpenTools,
                onOpenSkills = onOpenSkills,
                onOpenPermissions = onOpenPermissions,
            )
            Spacer(modifier = Modifier.height(DrawerMetrics.BottomInset))
        }
    }
}

@Composable
private fun PaneActionBar(
    query: String,
    onSearchChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchBar(
            modifier = Modifier.weight(1f),
            expanded = false,
            onExpandedChange = {},
            inputField = {
                InputField(
                    query = query,
                    onQueryChange = onSearchChange,
                    onSearch = onSearchChange,
                    expanded = false,
                    onExpandedChange = {},
                    label = stringResource(R.string.conversation_search_hint),
                )
            },
            content = {},
        )
    }
}

@Composable
private fun ConversationSectionHeader(
    group: ConversationDrawerGroup,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = DrawerMetrics.SectionTopPadding,
                bottom = DrawerMetrics.SectionBottomPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(LucideR.drawable.lucide_ic_clock),
            contentDescription = null,
            modifier = Modifier.size(DrawerMetrics.SectionIconSize),
            tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
        )
        Spacer(modifier = Modifier.width(DrawerMetrics.SectionIconGap))
        Text(
            text = group.localizedLabel(),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.footnote1,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.width(DrawerMetrics.SectionCountGap))
        Text(
            text = group.items.size.toString(),
            color = MiuixTheme.colorScheme.onSurfaceVariantActions,
            style = MiuixTheme.textStyles.footnote1,
            fontWeight = FontWeight.Medium,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationTextRow(
    conversation: ConversationSummaryUi,
    selected: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var showActionMenu by remember { mutableStateOf(false) }
    val hapticFeedback = LocalHapticFeedback.current

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = DrawerMetrics.RowMinHeight)
                .clip(RoundedCornerShape(DrawerMetrics.RowCornerRadius))
                .background(
                    if (selected) {
                        MiuixTheme.colorScheme.surfaceContainerHigh
                    } else {
                        Color.Transparent
                    },
                )
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        showActionMenu = true
                    },
                )
                .padding(
                    horizontal = DrawerMetrics.RowHorizontalPadding,
                    vertical = DrawerMetrics.RowVerticalPadding,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = conversation.title.ifBlank { conversation.preview },
                color = if (selected) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurface
                },
                style = MiuixTheme.textStyles.body1,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (conversation.isActiveRun) {
                Box(
                    modifier = Modifier
                        .padding(start = DrawerMetrics.ActiveDotGap)
                        .size(DrawerMetrics.ActiveDotSize)
                        .clip(CircleShape)
                        .background(MiuixTheme.colorScheme.primary),
                )
            }
        }

        WindowListPopup(
            show = showActionMenu,
            popupPositionProvider = ListPopupDefaults.ContextMenuPositionProvider,
            alignment = PopupPositionProvider.Align.BottomEnd,
            onDismissRequest = { showActionMenu = false },
        ) {
            val renameText = stringResource(R.string.action_rename)
            val deleteText = stringResource(R.string.action_delete)
            val renameItem = remember(renameText) {
                DropdownItem(
                    text = renameText,
                    icon = { modifier ->
                        Icon(
                            painter = painterResource(LucideR.drawable.lucide_ic_pencil),
                            contentDescription = null,
                            modifier = modifier.size(DrawerMetrics.ActionIconSize),
                        )
                    },
                )
            }
            val deleteItem = remember(deleteText) {
                DropdownItem(
                    text = deleteText,
                    icon = { modifier ->
                        Icon(
                            painter = painterResource(LucideR.drawable.lucide_ic_trash_2),
                            contentDescription = null,
                            modifier = modifier.size(DrawerMetrics.ActionIconSize),
                            tint = MiuixTheme.colorScheme.error,
                        )
                    },
                )
            }
            val deleteColors = DropdownDefaults.dropdownColors(
                contentColor = MiuixTheme.colorScheme.error,
                selectedContentColor = MiuixTheme.colorScheme.error,
                selectedIndicatorColor = MiuixTheme.colorScheme.error,
            )
            ListPopupColumn {
                DropdownImpl(
                    item = renameItem,
                    optionSize = 2,
                    isSelected = false,
                    index = 0,
                    onSelectedIndexChange = {
                        showActionMenu = false
                        onRename()
                    },
                )
                DropdownImpl(
                    item = deleteItem,
                    optionSize = 2,
                    isSelected = false,
                    index = 1,
                    dropdownColors = deleteColors,
                    onSelectedIndexChange = {
                        showActionMenu = false
                        onDelete()
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyConversations(isSearching: Boolean) {
    Text(
        text = stringResource(
            if (isSearching) R.string.conversation_no_results else R.string.conversation_empty,
        ),
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        style = MiuixTheme.textStyles.body2,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(
            horizontal = DrawerMetrics.RowHorizontalPadding,
            vertical = DrawerMetrics.EmptyVerticalPadding,
        ),
    )
}

@Composable
private fun PaneDock(
    onOpenSettings: () -> Unit,
    onOpenModelProviders: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenPermissions: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DockButton(
            icon = LucideR.drawable.lucide_ic_settings,
            label = stringResource(R.string.route_settings),
            onClick = onOpenSettings,
        )
        DockButton(
            icon = LucideR.drawable.lucide_ic_cpu,
            label = stringResource(R.string.conversation_dock_models),
            onClick = onOpenModelProviders,
        )
        DockButton(
            icon = LucideR.drawable.lucide_ic_package,
            label = stringResource(R.string.route_tools),
            onClick = onOpenTools,
        )
        DockButton(
            icon = LucideR.drawable.lucide_ic_puzzle,
            label = stringResource(R.string.route_skills),
            onClick = onOpenSkills,
        )
        DockButton(
            icon = LucideR.drawable.lucide_ic_lock,
            label = stringResource(R.string.route_permissions),
            onClick = onOpenPermissions,
        )
    }
}

@Composable
private fun DockButton(
    icon: Int,
    label: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        backgroundColor = MiuixTheme.colorScheme.surfaceContainer,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = label,
            modifier = Modifier.size(DrawerMetrics.ActionIconSize),
            tint = MiuixTheme.colorScheme.onSurface,
        )
    }
}

private data class ConversationDrawerGroup(
    val section: ConversationDrawerSection,
    val items: List<ConversationSummaryUi>,
)

private sealed interface ConversationDrawerSection {
    data object Pinned : ConversationDrawerSection
    data object Today : ConversationDrawerSection
    data class Dated(val label: String) : ConversationDrawerSection
}

@Composable
private fun ConversationDrawerGroup.localizedLabel(): String = when (val value = section) {
    ConversationDrawerSection.Pinned -> stringResource(R.string.conversation_section_pinned)
    ConversationDrawerSection.Today -> stringResource(R.string.conversation_section_today)
    is ConversationDrawerSection.Dated -> value.label
}

private fun List<ConversationSummaryUi>.groupForDrawer(): List<ConversationDrawerGroup> {
    if (isEmpty()) return emptyList()
    val groups = mutableListOf<ConversationDrawerGroup>()
    for (conversation in this) {
        val section = conversation.drawerSection()
        val last = groups.lastOrNull()
        if (last?.section == section) {
            groups[groups.lastIndex] = last.copy(items = last.items + conversation)
        } else {
            groups += ConversationDrawerGroup(section = section, items = listOf(conversation))
        }
    }
    return groups
}

private fun ConversationSummaryUi.drawerSection(): ConversationDrawerSection = when {
    isPinned -> ConversationDrawerSection.Pinned
    isActiveRun || isUpdatedToday(updatedAtMillis) -> ConversationDrawerSection.Today
    else -> ConversationDrawerSection.Dated(timeLabel)
}

private fun isUpdatedToday(timestampMillis: Long): Boolean {
    if (timestampMillis <= 0L) return true
    val now = java.util.Calendar.getInstance()
    val target = java.util.Calendar.getInstance().apply { timeInMillis = timestampMillis }
    return now.get(java.util.Calendar.ERA) == target.get(java.util.Calendar.ERA) &&
        now.get(java.util.Calendar.YEAR) == target.get(java.util.Calendar.YEAR) &&
        now.get(java.util.Calendar.DAY_OF_YEAR) == target.get(java.util.Calendar.DAY_OF_YEAR)
}
