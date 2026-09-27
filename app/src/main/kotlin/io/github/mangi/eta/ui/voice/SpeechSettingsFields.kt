package io.github.mangi.eta.ui.voice

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.model.SpeechCredentialField
import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.model.SpeechRegion
import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaDropdownPreference
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.TextField

@Composable
internal fun SpeechField(label: String, value: String, secret: Boolean = false, onChange: (String) -> Unit) {
    var visible by remember(label) { mutableStateOf(false) }
    TextField(value = value, onValueChange = onChange, label = label, singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        visualTransformation = if (secret && !visible) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (secret) {{
            IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = if (visible) "隐藏凭据" else "显示凭据")
            }
        }} else null)
}

@Composable
internal fun SpeechConnectionFields(store: SpeechSettingsStore, synthesis: Boolean) {
    val settings = store.settings
    val qwen = if (synthesis) settings.tts == TtsProvider.QWEN else settings.asr != AsrProvider.DOUBAO
    val keyField = if (qwen) { if (synthesis) SpeechCredentialField.QWEN_TTS else SpeechCredentialField.QWEN_ASR } else { if (synthesis) SpeechCredentialField.DOUBAO_TTS else SpeechCredentialField.DOUBAO_ASR }
    val secret = when (keyField) {
        SpeechCredentialField.QWEN_TTS -> store.credentials.qwenTts
        SpeechCredentialField.QWEN_ASR -> store.credentials.qwenAsr
        SpeechCredentialField.DOUBAO_TTS -> store.credentials.doubaoTts
        SpeechCredentialField.DOUBAO_ASR -> store.credentials.doubaoAsr
        else -> error("Not a provider credential")
    }
    var advanced by remember(keyField) { mutableStateOf(false) }
    Column {
        EtaPreferenceGroupTitle("连接")
        if (qwen) {
            val config = if (synthesis) settings.qwenTts else settings.qwenAsr
            val change: (io.github.mangi.eta.data.model.QwenSpeechConfig) -> Unit = {
                store.edit(if (synthesis) settings.copy(qwenTts = it) else settings.copy(qwenAsr = it))
            }
            EtaPreferenceGroup {
                EtaDropdownPreference(title = "地域", items = listOf(DropdownItem("北京"), DropdownItem("新加坡")),
                    selectedIndex = config.region.ordinal, onSelectedIndexChange = { change(config.copy(region = SpeechRegion.entries[it])) })
            }
            SpeechField("API Key", secret, secret = true) { store.credential(keyField, it.trim()) }
            EtaArrowPreference("高级设置", summary = if (advanced) "收起" else "自定义服务地址", onClick = { advanced = !advanced })
            if (advanced) {
                SpeechField("服务地址（留空使用地域默认地址）", config.baseUrl) { change(config.copy(baseUrl = it.trim())) }
                SpeechNote("填写服务根地址，不包含 /api/v1 或 /compatible-mode/v1。地域须与 API Key 匹配。")
            }
        } else {
            val config = if (synthesis) settings.doubaoTts else settings.doubaoAsr
            val change: (io.github.mangi.eta.data.model.DoubaoSpeechConfig) -> Unit = {
                store.edit(if (synthesis) settings.copy(doubaoTts = it) else settings.copy(doubaoAsr = it))
            }
            SpeechField(if (config.legacyAuth) "Access Token" else "API Key", secret, secret = true) { store.credential(keyField, it.trim()) }
            if (config.legacyAuth) SpeechField("App ID", config.appId) { change(config.copy(appId = it.trim())) }
            EtaArrowPreference("高级设置", summary = if (advanced) "收起" else "地址、资源 ID 与旧版鉴权", onClick = { advanced = !advanced })
            if (advanced) {
                EtaSwitchPreference("使用旧版鉴权", config.legacyAuth, { change(config.copy(legacyAuth = it)) })
                SpeechField("服务地址", config.baseUrl) { change(config.copy(baseUrl = it.trim())) }
                SpeechField("资源 ID（留空使用默认值）", config.resourceId) { change(config.copy(resourceId = it.trim())) }
                SpeechNote(if (synthesis) "默认使用 seed-tts-2.0。" else "默认使用 volc.seedasr.sauc.duration，按时长计费。")
            }
        }
    }
}

@Composable
internal fun SpeechVoiceFields(store: SpeechSettingsStore) {
    val settings = store.settings
    val qwen = settings.tts == TtsProvider.QWEN
    val voice = if (qwen) settings.qwenVoice else settings.doubaoVoice
    val preset = if (qwen) "Cherry" else "zh_female_vv_uranus_bigtts"
    var custom by remember(qwen) { mutableStateOf(voice != preset) }
    val change: (String) -> Unit = { store.edit(if (qwen) settings.copy(qwenVoice = it) else settings.copy(doubaoVoice = it)) }
    EtaPreferenceGroupTitle("声音")
    EtaPreferenceGroup {
        EtaDropdownPreference("音色", items = listOf(DropdownItem(if (qwen) "Cherry" else "VV"), DropdownItem("自定义音色")),
            selectedIndex = if (custom) 1 else 0, onSelectedIndexChange = {
                custom = it == 1
                if (!custom) change(preset)
            })
    }
    if (custom) SpeechField("音色 ID", voice) { change(it.trim()) }
    SpeechNote(if (qwen) "模型：qwen3-tts-flash。音色须支持该模型。" else "模型：豆包语音合成 2.0。音色 ID 可从控制台音色库复制。")
}
