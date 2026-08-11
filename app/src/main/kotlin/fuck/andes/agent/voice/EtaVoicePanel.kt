package fuck.andes.agent.voice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fitInside
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.RectRulers
import androidx.compose.ui.layout.WindowInsetsRulers
import androidx.compose.ui.layout.innermostOf
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.R as LucideR
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class EtaVoicePhase {
    PROCESSING,
    READY,
    ERROR,
}

internal data class EtaVoiceUiState(
    val phase: EtaVoicePhase = EtaVoicePhase.READY,
    val query: String = "",
    val response: String = "",
    val status: String = "输入请求",
)

/**
 * 面板悬浮在任意第三方应用之上，底色必须对未知背景内容保持可读，
 * 因此不跟随 Miuix 页面 token，使用接近系统助理浮层的高不透明度固定色。
 */
private class EtaVoicePanelColors(
    val barBackground: Color,
    val panelBackground: Color,
    val labelPrimary: Color,
    val labelSecondary: Color,
    val labelTertiary: Color,
)

@Composable
private fun rememberEtaVoicePanelColors(): EtaVoicePanelColors {
    val dark = isSystemInDarkTheme()
    return remember(dark) {
        if (dark) {
            EtaVoicePanelColors(
                barBackground = Color(0xF2404040),
                panelBackground = Color(0xFA2E2E2E),
                labelPrimary = Color(0xE6FFFFFF),
                labelSecondary = Color(0x8AFFFFFF),
                labelTertiary = Color(0x4DFFFFFF),
            )
        } else {
            EtaVoicePanelColors(
                barBackground = Color(0xF2FFFFFF),
                panelBackground = Color(0xFAF3F4F6),
                labelPrimary = Color(0xE6000000),
                labelSecondary = Color(0x8A000000),
                labelTertiary = Color(0x42000000),
            )
        }
    }
}

@Composable
internal fun EtaVoicePanel(
    state: EtaVoiceUiState,
    input: String,
    inputFocusRequestKey: Int,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = rememberEtaVoicePanelColors()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val canSubmit = input.isNotBlank() && state.phase != EtaVoicePhase.PROCESSING
    val submit = {
        if (canSubmit) {
            keyboardController?.hide()
            onSubmit()
        }
    }
    LaunchedEffect(inputFocusRequestKey) {
        if (inputFocusRequestKey >= 0 && state.phase != EtaVoicePhase.PROCESSING) {
            delay(120)
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // 底部渐变压暗；点击面板外空白处收起
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.5f to Color.Transparent,
                        1.0f to Color.Black.copy(alpha = 0.32f),
                    )
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose,
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .fitInside(
                    RectRulers.innermostOf(
                        WindowInsetsRulers.NavigationBars.current,
                        WindowInsetsRulers.Ime.current,
                    ),
                ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 640.dp)
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AnimatedVisibility(
                    visible = state.query.isNotBlank() || state.response.isNotBlank(),
                    enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
                    exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
                ) {
                    EtaVoiceResultCard(state = state, colors = colors)
                }
                EtaVoiceInputBar(
                    state = state,
                    colors = colors,
                    input = input,
                    canSubmit = canSubmit,
                    focusRequester = focusRequester,
                    onInputChange = onInputChange,
                    onSubmit = submit,
                    onStop = onStop,
                )
            }
        }
    }
}

@Composable
private fun EtaVoiceResultCard(
    state: EtaVoiceUiState,
    colors: EtaVoicePanelColors,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 360.dp)
            .squircleBackground(colors.panelBackground, 24.dp)
            // 吞掉点击，避免穿透到 scrim 触发收起
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (state.phase != EtaVoicePhase.READY) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.phase == EtaVoicePhase.PROCESSING) {
                    InfiniteProgressIndicator(size = 14.dp)
                } else {
                    Icon(
                        painter = painterResource(LucideR.drawable.lucide_ic_triangle_alert),
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MiuixTheme.colorScheme.error,
                    )
                }
                Text(
                    text = state.status,
                    color = if (state.phase == EtaVoicePhase.ERROR) {
                        MiuixTheme.colorScheme.error
                    } else {
                        colors.labelSecondary
                    },
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (state.query.isNotBlank()) {
            Text(
                text = state.query,
                color = colors.labelSecondary,
                fontSize = 14.sp,
            )
        }
        if (state.response.isNotBlank()) {
            Text(
                text = state.response,
                color = colors.labelPrimary,
                fontSize = 16.sp,
            )
        }
    }
}

@Composable
private fun EtaVoiceInputBar(
    state: EtaVoiceUiState,
    colors: EtaVoicePanelColors,
    input: String,
    canSubmit: Boolean,
    focusRequester: FocusRequester,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .squircleBackground(colors.barBackground, 24.dp)
            // 吞掉点击，避免穿透到 scrim 触发收起
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 6.dp)
                .focusRequester(focusRequester),
            enabled = state.phase != EtaVoicePhase.PROCESSING,
            textStyle = TextStyle(
                color = colors.labelPrimary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
            cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSubmit() }),
            maxLines = 4,
            minLines = 1,
            decorationBox = { innerTextField ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (input.isEmpty()) {
                        Text(
                            text = "发消息问 Eta…",
                            color = colors.labelTertiary,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
            },
        )
        if (state.phase == EtaVoicePhase.PROCESSING) {
            IconButton(
                onClick = onStop,
                minWidth = 36.dp,
                minHeight = 36.dp,
                cornerRadius = 18.dp,
                backgroundColor = MiuixTheme.colorScheme.error,
            ) {
                Icon(
                    painter = painterResource(LucideR.drawable.lucide_ic_square),
                    contentDescription = "停止",
                    modifier = Modifier.size(15.dp),
                    tint = Color.White,
                )
            }
        } else {
            IconButton(
                onClick = onSubmit,
                enabled = canSubmit,
                minWidth = 36.dp,
                minHeight = 36.dp,
                cornerRadius = 18.dp,
                backgroundColor = if (canSubmit) {
                    MiuixTheme.colorScheme.primary
                } else {
                    colors.labelTertiary.copy(alpha = 0.35f)
                },
            ) {
                Icon(
                    painter = painterResource(LucideR.drawable.lucide_ic_send),
                    contentDescription = "发送",
                    modifier = Modifier.size(15.dp),
                    tint = if (canSubmit) Color.White else colors.labelSecondary,
                )
            }
        }
    }
}
