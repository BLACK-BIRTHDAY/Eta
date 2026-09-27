package io.github.mangi.eta.ui.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mangi.eta.agent.voice.EtaSpeechPhase
import io.github.mangi.eta.agent.voice.SpeechInputController
import io.github.mangi.eta.agent.voice.SpeechPlaybackController
import io.github.mangi.eta.ui.MainActivity
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal const val ACTION_SPEECH_SETTINGS = "io.github.mangi.eta.OPEN_SPEECH_SETTINGS"
internal fun openSpeechSettings(context: Context) {
    context.startActivity(Intent(context, MainActivity::class.java).setAction(ACTION_SPEECH_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP))
}

@Composable
internal fun SpeechLifecycle(onStop: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val stop by rememberUpdatedState(onStop)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) stop() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); stop() }
    }
}

@Composable
internal fun rememberSpeechInput(onResult: (String) -> Unit): SpeechInputController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val result by rememberUpdatedState(onResult)
    val controller = remember(context, scope) { SpeechInputController(context, scope, onResult = { result(it) }) }
    SpeechLifecycle { controller.cancel() }
    return controller
}

@Composable
internal fun rememberSpeechPermission(onGranted: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val action by rememberUpdatedState(onGranted)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) action() else Toast.makeText(context, "请允许 Eta 使用麦克风", Toast.LENGTH_SHORT).show()
    }
    return {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) action()
        else launcher.launch(Manifest.permission.RECORD_AUDIO)
    }
}

@Composable
internal fun SpeechDictationButton(controller: SpeechInputController, enabled: Boolean) {
    val state by controller.state.collectAsState()
    val request = rememberSpeechPermission { if (enabled) controller.start() }
    IconButton(onClick = { if (state.active) controller.finish() else request() },
        enabled = enabled && state.phase != EtaSpeechPhase.RECOGNIZING, minWidth = 40.dp, minHeight = 40.dp) {
        Icon(if (state.active) Icons.Rounded.Stop else Icons.Rounded.Mic,
            contentDescription = if (state.active) "完成听写" else "语音输入", modifier = Modifier.size(20.dp))
    }
}

@Composable
internal fun SpeechInputFeedback(controller: SpeechInputController) {
    val state by controller.state.collectAsState()
    val context = LocalContext.current
    val message = state.error ?: state.preview.ifBlank { state.progress }
    if (message.isBlank()) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(message, modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        when {
            state.active -> TextButton(text = "取消", onClick = controller::cancel)
            state.downloadAvailable -> TextButton(text = "下载", onClick = controller::downloadModel)
            state.error != null -> TextButton(text = "语音设置", onClick = { openSpeechSettings(context) })
        }
    }
}

internal val LocalSpeechPlayback = staticCompositionLocalOf<SpeechPlaybackController?> { null }

@Composable
internal fun SpeechPlaybackHost(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { SpeechPlaybackController(context, scope) }
    SpeechLifecycle { controller.stop() }
    SpeechPlaybackErrors(controller)
    CompositionLocalProvider(LocalSpeechPlayback provides controller, content = content)
}

@Composable
internal fun SpeechPlaybackErrors(controller: SpeechPlaybackController) {
    val context = LocalContext.current
    val state by controller.state.collectAsState()
    LaunchedEffect(state.error) {
        state.error?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }
}

@Composable
internal fun SpeechReadAloudButton(messageId: String, text: String) {
    val controller = LocalSpeechPlayback.current ?: return
    val state by controller.state.collectAsState()
    val active = state.messageId == messageId
    IconButton(onClick = { if (active) controller.stop() else controller.speak(messageId, text) }, minWidth = 30.dp, minHeight = 30.dp) {
        Icon(if (active) Icons.Rounded.Stop else Icons.AutoMirrored.Rounded.VolumeUp,
            contentDescription = if (active) "停止朗读" else "朗读回答", modifier = Modifier.size(15.dp),
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }
}
