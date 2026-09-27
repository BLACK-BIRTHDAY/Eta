package io.github.mangi.eta.ui.voice

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.agent.voice.SpeechInputController
import io.github.mangi.eta.agent.voice.SpeechPlaybackController
import io.github.mangi.eta.agent.voice.validateSpeechSettings
import io.github.mangi.eta.data.model.SpeechCredentialField
import io.github.mangi.eta.data.model.SpeechCredentials
import io.github.mangi.eta.data.model.SpeechSettings
import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.data.repository.SpeechCredentialsUnavailable
import io.github.mangi.eta.data.repository.SpeechSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal enum class SpeechSettingsPane { OVERVIEW, RECOGNITION, SYNTHESIS, OSS }

internal class SpeechSettingsStore(private val context: Context, private val scope: CoroutineScope) {
    var settings by mutableStateOf(SpeechSettings())
        private set
    var credentials by mutableStateOf(SpeechCredentials())
        private set
    var pane by mutableStateOf(SpeechSettingsPane.OVERVIEW)
        private set
    var loaded by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    val recognition = SpeechInputController(context, scope, onResult = { message = "识别结果：$it" })
    val playback = SpeechPlaybackController(context, scope)

    init {
        scope.launch {
            try {
                settings = SpeechSettingsRepository.settings()
                try { credentials = SpeechSettingsRepository.credentials(context) }
                catch (_: SpeechCredentialsUnavailable) { message = "语音凭据无法解密，请重新填写并保存" }
                loaded = true
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                message = "无法读取语音设置，请返回后重试"
            }
        }
    }

    fun edit(value: SpeechSettings) { stop(); settings = value; message = null }
    fun credential(field: SpeechCredentialField, value: String) { stop(); credentials = credentials.withValue(field, value); message = null }
    fun navigate(value: SpeechSettingsPane) { stop(); pane = value; message = null }
    fun stop() { recognition.cancel(); playback.stop() }

    fun save() {
        if (!loaded || saving) return
        val config = settings
        val secrets = credentials
        stop()
        saving = true
        scope.launch {
            try {
                validateSpeechSettings(config, secrets, synthesis = false)
                if (config.tts != TtsProvider.NONE) validateSpeechSettings(config, secrets, synthesis = true)
                SpeechSettingsRepository.saveCredentials(context, secrets)
                SpeechSettingsRepository.save(config)
                message = if (settings === config && credentials === secrets) "已保存" else "已保存，仍有未保存的修改"
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                message = (error as? io.github.mangi.eta.agent.voice.SpeechFailure)?.userMessage ?: "语音设置保存失败，请重试"
            } finally { saving = false }
        }
    }
}
