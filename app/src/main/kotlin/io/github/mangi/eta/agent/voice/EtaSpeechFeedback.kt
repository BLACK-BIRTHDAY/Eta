package io.github.mangi.eta.agent.voice

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class EtaSpeechPhase { IDLE, STARTING, LISTENING, RECOGNIZING }

internal data class EtaSpeechState(
    val phase: EtaSpeechPhase = EtaSpeechPhase.IDLE,
    val errorRes: Int? = null,
) {
    val active: Boolean get() = phase != EtaSpeechPhase.IDLE
}

@Composable
internal fun EtaSpeechFeedback(speech: EtaSpeechState) {
    val errorRes = speech.errorRes ?: return
    val dark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(
            text = stringResource(errorRes),
            color = if (dark) Color(0xFFFF9E99) else Color(0xFFB42318),
            modifier = Modifier
                .background(
                    if (dark) Color(0xFF34363B) else Color(0xFFF2F3F5),
                    RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}
