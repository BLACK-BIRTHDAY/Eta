package fuck.andes.agent.voice

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.R as LucideR
import fuck.andes.ui.components.AgentConversationMessages
import fuck.andes.ui.model.AgentChatMessageUi
import fuck.andes.ui.model.AgentMessageUi
import fuck.andes.ui.model.ThinkingMessageUi
import fuck.andes.ui.model.ToolActivityMessageUi
import fuck.andes.ui.model.UserMessageUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.ceil
import kotlin.math.max

internal enum class EtaVoicePhase {
    READY,
    PROCESSING,
    ERROR,
}

internal data class EtaVoiceUiState(
    val messages: List<AgentChatMessageUi> = emptyList(),
    val phase: EtaVoicePhase = EtaVoicePhase.READY,
    val status: String = "输入请求",
)

private data class EtaVoicePanelColors(
    val content: Color,
    val input: Color,
    val primary: Color,
    val tertiary: Color,
    val outline: Color,
    val scrim: Color,
)

private val WakeColors = listOf(
    Color(0xFF79E7FF),
    Color(0xFFA7F3C8),
    Color(0xFFFFD27A),
    Color(0xFFFF9FBA),
    Color(0xFFB9A2FF),
    Color(0xFF79E7FF),
)

@Composable
private fun rememberEtaVoicePanelColors(): EtaVoicePanelColors {
    val dark = isSystemInDarkTheme()
    return remember(dark) {
        if (dark) {
            EtaVoicePanelColors(
                content = Color(0xF52B2C2F),
                input = Color(0xFA36373B),
                primary = Color(0xF2FFFFFF),
                tertiary = Color(0x66FFFFFF),
                outline = Color(0x2EFFFFFF),
                scrim = Color(0x52000000),
            )
        } else {
            EtaVoicePanelColors(
                content = Color(0xFAF7F7F9),
                input = Color(0xFCFFFFFF),
                primary = Color(0xEB000000),
                tertiary = Color(0x52000000),
                outline = Color(0x18000000),
                scrim = Color(0x30000000),
            )
        }
    }
}

@Composable
internal fun EtaVoicePanel(
    state: EtaVoiceUiState,
    input: String,
    inputFocusRequestKey: Int,
    canOpenConversation: Boolean,
    exitRequested: Boolean,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    onOpenConversation: () -> Unit,
) {
    val colors = rememberEtaVoicePanelColors()
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val focusRequester = remember { FocusRequester() }
    val wakeProgress = remember { Animatable(0f) }
    val entryProgress = remember { Animatable(0f) }
    val exitAlpha by animateFloatAsState(
        targetValue = if (exitRequested) 0f else 1f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "assistant_exit",
    )

    LaunchedEffect(Unit) {
        launch { entryProgress.animateTo(1f, tween(360, easing = FastOutSlowInEasing)) }
        wakeProgress.animateTo(1f, tween(180, easing = FastOutSlowInEasing))
        wakeProgress.animateTo(0f, tween(620, easing = FastOutSlowInEasing))
    }

    LaunchedEffect(inputFocusRequestKey) {
        if (inputFocusRequestKey >= 0 && state.phase != EtaVoicePhase.PROCESSING) {
            delay(120)
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = exitAlpha }
            .background(colors.scrim.copy(alpha = colors.scrim.alpha * entryProgress.value))
            .drawBehind {
                if (wakeProgress.value <= 0f) return@drawBehind
                val strokeWidth = 12.dp.toPx()
                val inset = strokeWidth / 2f
                drawRoundRect(
                    brush = Brush.sweepGradient(WakeColors),
                    topLeft = Offset(inset, inset),
                    size = Size(size.width - strokeWidth, size.height - strokeWidth),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(30.dp.toPx()),
                    alpha = wakeProgress.value * 0.82f,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
                )
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose,
                ),
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = entryProgress.value
                    translationY = (1f - entryProgress.value) * with(density) { 22.dp.toPx() }
                },
        ) {
            val imeBottom = WindowInsets.ime.getBottom(density)
            val navigationBottom = WindowInsets.navigationBars.getBottom(density)
            val statusTop = WindowInsets.statusBars.getTop(density)
            val bottomInset = max(imeBottom, navigationBottom)
            val maxContentHeightPx = with(density) {
                (maxHeight - 88.dp).toPx() - statusTop - bottomInset
            }.coerceAtLeast(with(density) { 220.dp.toPx() })
            AssistantPanel(
                state = state,
                input = input,
                colors = colors,
                focusRequester = focusRequester,
                canOpenConversation = canOpenConversation,
                baseContentHeightPx = assistantBaseHeightPx(
                    messages = state.messages,
                    maxHeightPx = maxContentHeightPx,
                    density = density.density,
                ),
                maxContentHeightPx = maxContentHeightPx,
                bottomInsetPx = bottomInset,
                onInputChange = onInputChange,
                onSubmit = {
                    keyboard?.hide()
                    onSubmit()
                },
                onStop = onStop,
                onClose = onClose,
                onOpenConversation = onOpenConversation,
            )
        }
    }
}

@Composable
private fun BoxScope.AssistantPanel(
    state: EtaVoiceUiState,
    input: String,
    colors: EtaVoicePanelColors,
    focusRequester: FocusRequester,
    canOpenConversation: Boolean,
    baseContentHeightPx: Float,
    maxContentHeightPx: Float,
    bottomInsetPx: Int,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    onOpenConversation: () -> Unit,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var settledHeightPx by remember { mutableFloatStateOf(baseContentHeightPx) }
    var draggedHeightPx by remember { mutableStateOf<Float?>(null) }
    var dismissPullPx by remember { mutableFloatStateOf(0f) }
    var handoffPullPx by remember { mutableFloatStateOf(0f) }
    var directHandoffPullPx by remember { mutableFloatStateOf(0f) }
    var thresholdHapticSent by remember { mutableStateOf(false) }
    var handoffRunning by remember { mutableStateOf(false) }
    var keepBottomAnchored by remember { mutableStateOf(true) }
    val handoffThresholdPx = with(density) { 72.dp.toPx() }
    val directHandoffThresholdPx = with(density) { 48.dp.toPx() }
    val dismissThresholdPx = with(density) { 92.dp.toPx() }
    val handoffVelocityPx = with(density) { 900.dp.toPx() }
    val animatedHeightPx by animateFloatAsState(
        targetValue = settledHeightPx.coerceIn(baseContentHeightPx, maxContentHeightPx),
        animationSpec = spring(
            dampingRatio = 0.88f,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "assistant_content_height",
    )
    val currentAnimatedHeight = rememberUpdatedState(animatedHeightPx)
    val sheetHeightPx = draggedHeightPx ?: animatedHeightPx
    val nearFullscreen = sheetHeightPx >= maxContentHeightPx * 0.88f
    val handoffReady = canOpenConversation && nearFullscreen &&
        (handoffPullPx >= handoffThresholdPx ||
            directHandoffPullPx >= directHandoffThresholdPx)
    val contentTranslationPx = dismissPullPx * 0.28f - handoffPullPx.coerceAtMost(
        with(density) { 28.dp.toPx() },
    ) * 0.12f

    LaunchedEffect(baseContentHeightPx, maxContentHeightPx) {
        settledHeightPx = settledHeightPx.coerceIn(baseContentHeightPx, maxContentHeightPx)
    }
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) keepBottomAnchored = true
    }
    LaunchedEffect(handoffReady) {
        if (handoffReady && !thresholdHapticSent) {
            thresholdHapticSent = true
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        } else if (!handoffReady) {
            thresholdHapticSent = false
        }
    }

    fun triggerHandoff() {
        if (handoffRunning || !canOpenConversation) return
        handoffRunning = true
        settledHeightPx = maxContentHeightPx
        draggedHeightPx = null
        scope.launch {
            delay(120)
            onOpenConversation()
        }
    }

    fun dragBy(deltaY: Float): Float {
        if (handoffRunning || state.messages.isEmpty()) return 0f
        val current = draggedHeightPx ?: currentAnimatedHeight.value
        val requested = current - deltaY
        return when {
            requested > maxContentHeightPx -> {
                draggedHeightPx = maxContentHeightPx
                if (deltaY < 0f) handoffPullPx += -deltaY
                deltaY
            }
            requested < baseContentHeightPx -> {
                draggedHeightPx = baseContentHeightPx
                if (deltaY > 0f) dismissPullPx += deltaY
                deltaY
            }
            else -> {
                draggedHeightPx = requested
                dismissPullPx = 0f
                handoffPullPx = 0f
                deltaY
            }
        }
    }

    fun finishDrag(velocityY: Float = 0f) {
        val current = draggedHeightPx ?: currentAnimatedHeight.value
        when {
            dismissPullPx >= dismissThresholdPx -> onClose()
            canOpenConversation && current >= maxContentHeightPx * 0.88f &&
                (handoffReady || velocityY <= -handoffVelocityPx) -> triggerHandoff()
            else -> {
                val medium = baseContentHeightPx +
                    (maxContentHeightPx - baseContentHeightPx) * 0.58f
                val anchors = floatArrayOf(baseContentHeightPx, medium, maxContentHeightPx)
                settledHeightPx = anchors.minBy { kotlin.math.abs(it - current) }
                draggedHeightPx = null
                dismissPullPx = 0f
                handoffPullPx = 0f
            }
        }
    }

    val dragByState = rememberUpdatedState<(Float) -> Float>(::dragBy)
    val finishDragState = rememberUpdatedState<(Float) -> Unit>(::finishDrag)
    val nestedScrollConnection = remember(
        baseContentHeightPx,
        maxContentHeightPx,
        canOpenConversation,
        listState,
    ) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val current = draggedHeightPx ?: currentAnimatedHeight.value
                if (
                    available.y < 0f &&
                    canOpenConversation &&
                    current >= maxContentHeightPx * 0.88f
                ) {
                    directHandoffPullPx += -available.y
                    if (directHandoffPullPx >= directHandoffThresholdPx) {
                        triggerHandoff()
                    }
                    // 第二段上滑由父容器在 pre-scroll 阶段完整消费，避免列表或
                    // overscroll 先截走事件后，接管手势永远达不到阈值。
                    return Offset(0f, available.y)
                }
                val shouldResize = (available.y < 0f && current < maxContentHeightPx) ||
                    (available.y > 0f && current > baseContentHeightPx && !listState.canScrollBackward)
                return if (shouldResize) {
                    Offset(0f, dragByState.value(available.y))
                } else {
                    Offset.Zero
                }
            }

            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (available.y == 0f) return Offset.Zero
                val current = draggedHeightPx ?: currentAnimatedHeight.value
                val atUpperEdge = available.y < 0f && current >= maxContentHeightPx * 0.88f
                val atLowerEdge = available.y > 0f && current <= baseContentHeightPx
                return if (atUpperEdge || atLowerEdge) {
                    Offset(0f, dragByState.value(available.y))
                } else {
                    Offset.Zero
                }
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                finishDragState.value(available.y)
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                finishDragState.value(available.y)
                return Velocity.Zero
            }
        }
    }

    Column(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .padding(
                start = 10.dp,
                end = 10.dp,
                bottom = with(density) { bottomInsetPx.toDp() } + 8.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.messages.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = with(density) { contentTranslationPx.toDp() })
                    .height(with(density) { sheetHeightPx.toDp() })
                    .dropShadow(
                        shape = RoundedCornerShape(28.dp),
                        shadow = Shadow(radius = 20.dp, color = Color.Black, alpha = 0.18f),
                    )
                    .squircleSurface(color = colors.content, cornerRadius = 28.dp)
                    .squircleBorder(width = 0.8.dp, color = colors.outline, cornerRadius = 28.dp)
                    .nestedScroll(nestedScrollConnection),
            ) {
                DragHandle(
                    colors = colors,
                    modifier = Modifier.pointerInput(baseContentHeightPx, maxContentHeightPx) {
                        detectVerticalDragGestures(
                            onDragStart = { draggedHeightPx = currentAnimatedHeight.value },
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                dragBy(dragAmount)
                            },
                            onDragEnd = { finishDrag() },
                            onDragCancel = { finishDrag() },
                        )
                    },
                )
                Box(modifier = Modifier.weight(1f)) {
                    AgentConversationMessages(
                        visibleMessages = state.messages,
                        scrollState = listState,
                        isStreaming = state.phase == EtaVoicePhase.PROCESSING,
                        bottomInset = if (nearFullscreen) 46.dp else 8.dp,
                        keepBottomAnchored = keepBottomAnchored,
                        onBottomAnchorChanged = { keepBottomAnchored = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (nearFullscreen || handoffPullPx > 0f) {
                        HandoffHint(
                            ready = handoffReady,
                            enabled = canOpenConversation,
                            colors = colors,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }
            }
        }

        AssistantInputBar(
            state = state,
            input = input,
            colors = colors,
            focusRequester = focusRequester,
            onInputChange = onInputChange,
            onSubmit = onSubmit,
            onStop = onStop,
            modifier = Modifier
                .fillMaxWidth()
                .dropShadow(
                    shape = RoundedCornerShape(23.dp),
                    shadow = Shadow(radius = 15.dp, color = Color.Black, alpha = 0.16f),
                ),
        )
    }
}

@Composable
private fun DragHandle(colors: EtaVoicePanelColors, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(34.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 4.dp)
                .clip(CircleShape)
                .background(colors.tertiary),
        )
    }
}

@Composable
private fun HandoffHint(
    ready: Boolean,
    enabled: Boolean,
    colors: EtaVoicePanelColors,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(42.dp)
            .background(colors.content.copy(alpha = 0.94f)),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(LucideR.drawable.lucide_ic_chevron_up),
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = colors.tertiary,
        )
        Spacer(modifier = Modifier.size(4.dp))
        AnimatedContent(
            targetState = ready,
            transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
            label = "assistant_handoff_hint",
        ) { isReady ->
            Text(
                text = when {
                    !enabled -> "完成后可进入 Eta"
                    isReady -> "松手进入 Eta"
                    else -> "继续上滑进入 Eta"
                },
                color = colors.tertiary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun AssistantInputBar(
    state: EtaVoiceUiState,
    input: String,
    colors: EtaVoicePanelColors,
    focusRequester: FocusRequester,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canSubmit = input.isNotBlank() && state.phase != EtaVoicePhase.PROCESSING
    Row(
        modifier = modifier
            .heightIn(min = 58.dp)
            .squircleSurface(color = colors.input, cornerRadius = 23.dp)
            .squircleBorder(width = 0.6.dp, color = colors.outline, cornerRadius = 23.dp)
            .padding(start = 10.dp, end = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(LucideR.drawable.lucide_ic_sparkles),
            contentDescription = null,
            modifier = Modifier.padding(horizontal = 6.dp).size(19.dp),
            tint = MiuixTheme.colorScheme.primary,
        )
        BasicTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 7.dp)
                .focusRequester(focusRequester),
            enabled = state.phase != EtaVoicePhase.PROCESSING,
            textStyle = TextStyle(
                color = colors.primary,
                fontSize = 15.sp,
                lineHeight = 21.sp,
            ),
            cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (canSubmit) onSubmit() }),
            maxLines = 4,
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (input.isEmpty()) {
                        Text(
                            text = if (state.phase == EtaVoicePhase.PROCESSING) {
                                state.status
                            } else {
                                "发消息问 Eta"
                            },
                            color = colors.tertiary,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
            },
        )
        IconButton(
            onClick = if (state.phase == EtaVoicePhase.PROCESSING) onStop else onSubmit,
            enabled = state.phase == EtaVoicePhase.PROCESSING || canSubmit,
            minWidth = 42.dp,
            minHeight = 42.dp,
            cornerRadius = 21.dp,
            backgroundColor = when {
                state.phase == EtaVoicePhase.PROCESSING -> MiuixTheme.colorScheme.error
                canSubmit -> MiuixTheme.colorScheme.primary
                else -> Color.Transparent
            },
        ) {
            Icon(
                painter = painterResource(
                    if (state.phase == EtaVoicePhase.PROCESSING) {
                        LucideR.drawable.lucide_ic_square
                    } else {
                        LucideR.drawable.lucide_ic_arrow_up
                    },
                ),
                contentDescription = if (state.phase == EtaVoicePhase.PROCESSING) "停止" else "发送",
                modifier = Modifier.size(16.dp),
                tint = if (state.phase == EtaVoicePhase.PROCESSING || canSubmit) {
                    Color.White
                } else {
                    colors.tertiary
                },
            )
        }
    }
}

private fun assistantBaseHeightPx(
    messages: List<AgentChatMessageUi>,
    maxHeightPx: Float,
    density: Float,
): Float {
    if (messages.isEmpty()) return 0f
    val estimatedLines = messages.sumOf { message ->
        when (message) {
            is UserMessageUi -> ceil(message.content.length / 22f).toInt().coerceAtLeast(1)
            is AgentMessageUi -> ceil(message.content.length / 24f).toInt().coerceAtLeast(1)
            is ThinkingMessageUi -> 2
            is ToolActivityMessageUi -> 2
            else -> 1
        }
    }
    val estimatedDp = 92f + estimatedLines * 23f + messages.size * 12f
    return max(230f, estimatedDp)
        .times(density)
        .coerceAtMost(maxHeightPx * 0.68f)
}
