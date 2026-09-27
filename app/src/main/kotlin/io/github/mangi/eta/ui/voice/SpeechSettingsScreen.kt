package io.github.mangi.eta.ui.voice

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.model.SpeechCredentialField
import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.ui.components.EtaArrowPreference
import io.github.mangi.eta.ui.components.EtaDropdownPreference
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal fun AsrProvider.label(): String = when (this) {
    AsrProvider.SYSTEM -> "系统语音服务"
    AsrProvider.QWEN_REALTIME -> "千问 · 实时识别"
    AsrProvider.QWEN_FLASH -> "千问 · 短音频识别"
    AsrProvider.QWEN_FILE -> "千问 · 文件转写"
    AsrProvider.DOUBAO -> "豆包 · 流式识别 2.0"
}

internal fun TtsProvider.label(): String = when (this) {
    TtsProvider.NONE -> "关闭"
    TtsProvider.QWEN -> "千问"
    TtsProvider.DOUBAO -> "豆包"
}

@Composable
internal fun SpeechSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SpeechSettingsStore(context, scope) }
    val config = store.settings
    val pane = store.pane
    val goBack = {
        when (pane) {
            SpeechSettingsPane.OVERVIEW -> onBack()
            SpeechSettingsPane.OSS -> store.navigate(SpeechSettingsPane.RECOGNITION)
            else -> store.navigate(SpeechSettingsPane.OVERVIEW)
        }
    }
    BackHandler(pane != SpeechSettingsPane.OVERVIEW, onBack = goBack)
    SpeechLifecycle { store.stop() }
    SpeechPlaybackErrors(store.playback)
    val title = when (pane) {
        SpeechSettingsPane.OVERVIEW -> "语音"
        SpeechSettingsPane.RECOGNITION -> "语音识别"
        SpeechSettingsPane.SYNTHESIS -> "语音播报"
        SpeechSettingsPane.OSS -> "录音存储"
    }
    MiuixScaffoldPage(title = title, onBack = goBack, modifier = Modifier.imePadding(), actions = {
        TextButton(text = if (store.saving) "保存中" else "保存", enabled = store.loaded && !store.saving, onClick = store::save)
    }) {
        if (!store.loaded) {
            item("loading") { SpeechNote(store.message ?: "正在读取设置") }
        } else {
            when (pane) {
                SpeechSettingsPane.OVERVIEW -> {
                    item("services") {
                        EtaPreferenceGroupTitle("服务")
                        EtaPreferenceGroup {
                            EtaArrowPreference(title = "语音识别", summary = config.asr.label(), onClick = { store.navigate(SpeechSettingsPane.RECOGNITION) })
                            EtaArrowPreference(title = "语音播报", summary = config.tts.label(), onClick = { store.navigate(SpeechSettingsPane.SYNTHESIS) })
                        }
                    }
                    item("behavior") {
                        EtaPreferenceGroupTitle("助手")
                        EtaPreferenceGroup {
                            EtaSwitchPreference(title = "自动播报回答", checked = config.autoSpeak,
                                summary = "仅在助手浮窗可见时播报语音提问的最终回答", enabled = config.tts != TtsProvider.NONE,
                                onCheckedChange = { store.edit(config.copy(autoSpeak = it)) })
                        }
                        SpeechNote("聊天中的语音输入会先转成可编辑文字。回答可手动朗读，开始录音会停止播放。")
                    }
                }
                SpeechSettingsPane.RECOGNITION -> {
                    item("provider") {
                        EtaPreferenceGroupTitle("识别服务")
                        EtaPreferenceGroup {
                            EtaDropdownPreference(title = "服务与模型", items = AsrProvider.entries.map { DropdownItem(it.label()) },
                                selectedIndex = config.asr.ordinal, onSelectedIndexChange = { store.edit(config.copy(asr = AsrProvider.entries[it])) })
                        }
                        SpeechNote(when (config.asr) {
                            AsrProvider.SYSTEM -> "使用设备已有的语音服务；云端服务不依赖系统识别引擎。"
                            AsrProvider.QWEN_REALTIME, AsrProvider.DOUBAO -> "边说边显示文字。聊天听写点击完成；助手浮窗可在停顿后自动提交。"
                            AsrProvider.QWEN_FLASH -> "录音完成后上传识别，适合短句输入。"
                            AsrProvider.QWEN_FILE -> "录音先上传自有 OSS，再异步转写，等待时间较长。"
                        })
                    }
                    if (config.asr != AsrProvider.SYSTEM) item("credentials") { SpeechConnectionFields(store, synthesis = false) }
                    if (config.asr in listOf(AsrProvider.QWEN_REALTIME, AsrProvider.QWEN_FLASH, AsrProvider.QWEN_FILE)) item("language") {
                        SpeechField("识别语言（留空自动识别，如 zh、en、yue）", config.language) { store.edit(config.copy(language = it.trim())) }
                    }
                    if (config.asr == AsrProvider.QWEN_FILE) item("oss") {
                        EtaPreferenceGroup {
                            EtaArrowPreference("录音存储", summary = config.oss.bucket.ifBlank { "配置自有 OSS" },
                                onClick = { store.navigate(SpeechSettingsPane.OSS) })
                        }
                    }
                    item("test") { SpeechRecognitionTest(store) }
                }
                SpeechSettingsPane.SYNTHESIS -> {
                    item("provider") {
                        EtaPreferenceGroupTitle("播报服务")
                        EtaPreferenceGroup {
                            EtaDropdownPreference(title = "服务", items = TtsProvider.entries.map { DropdownItem(it.label()) },
                                selectedIndex = config.tts.ordinal, onSelectedIndexChange = { store.edit(config.copy(tts = TtsProvider.entries[it])) })
                        }
                    }
                    if (config.tts != TtsProvider.NONE) {
                        item("credentials") { SpeechConnectionFields(store, synthesis = true) }
                        item("voice") { SpeechVoiceFields(store) }
                        item("preview") {
                            val playback by store.playback.state.collectAsState()
                            Row(Modifier.padding(16.dp)) {
                                TextButton(text = if (playback.messageId != null) "停止试听" else "试听音色", onClick = {
                                    if (playback.messageId != null) store.playback.stop()
                                    else store.playback.speak("preview", "你好，我是 Eta。有什么可以帮你？", store.settings, store.credentials)
                                })
                            }
                            SpeechNote("试听使用当前填写的配置，不会自动保存。")
                        }
                    }
                }
                SpeechSettingsPane.OSS -> {
                    item("description") { SpeechNote("录音以私有对象上传，转写结束后删除。请为此目录授予上传、读取和删除权限，并配置一天过期的生命周期规则作为异常清理保障。") }
                    item("region") { SpeechField("地域", config.oss.region) { store.edit(config.copy(oss = config.oss.copy(region = it.trim()))) } }
                    item("endpoint") { SpeechField("Endpoint", config.oss.endpoint) { store.edit(config.copy(oss = config.oss.copy(endpoint = it.trim()))) } }
                    item("bucket") { SpeechField("Bucket", config.oss.bucket) { store.edit(config.copy(oss = config.oss.copy(bucket = it.trim()))) } }
                    item("prefix") { SpeechField("目录前缀", config.oss.prefix) { store.edit(config.copy(oss = config.oss.copy(prefix = it.trim()))) } }
                    item("keyId") { SpeechField("AccessKey ID", store.credentials.ossAccessKeyId, secret = true) { store.credential(SpeechCredentialField.OSS_KEY_ID, it.trim()) } }
                    item("keySecret") { SpeechField("AccessKey Secret", store.credentials.ossAccessKeySecret, secret = true) { store.credential(SpeechCredentialField.OSS_KEY_SECRET, it.trim()) } }
                }
            }
            store.message?.let { message -> item("feedback") { SpeechNote(message) } }
        }
    }
}

@Composable
private fun SpeechRecognitionTest(store: SpeechSettingsStore) {
    val state by store.recognition.state.collectAsState()
    val request = rememberSpeechPermission { store.recognition.start(store.settings, store.credentials) }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        TextButton(text = if (state.active) "完成录音" else "测试识别", onClick = {
            if (state.active) store.recognition.finish() else request()
        }, modifier = Modifier.padding(horizontal = 16.dp))
        SpeechInputFeedback(store.recognition)
        SpeechNote("测试会调用所选服务，可能产生费用；使用当前填写的配置，不会自动保存。")
    }
}

@Composable
internal fun SpeechNote(text: String) {
    Text(text, modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
        style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}
