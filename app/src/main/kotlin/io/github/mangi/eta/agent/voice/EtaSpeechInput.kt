package io.github.mangi.eta.agent.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import io.github.mangi.eta.core.AndroidAgentLogger

/** 单次识别由浮窗持有；关闭、切换输入方式和重新唤醒都会使旧回调失效。 */
internal class EtaSpeechInput(
    private val context: Context,
    private val onListening: () -> Unit,
    private val onRecognizing: () -> Unit,
    private val onLevel: (Float) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onResult: (String) -> Unit,
    private val onError: (Int) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var generation = 0
    private var timeout: Runnable? = null

    fun start() {
        cancel()
        val session = generation
        try {
            val delegate = SystemSpeechRecognizer.create(context)
            if (delegate == null) {
                onError(SpeechRecognizer.ERROR_CLIENT)
                return
            }
            recognizer = delegate
            delegate.setRecognitionListener(object : RecognitionListener {
                private fun current() = generation == session && recognizer === delegate
                override fun onReadyForSpeech(params: Bundle?) {
                    if (current()) onListening()
                }
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) {
                    if (current()) onLevel((rmsdB / 10f).coerceIn(0f, 1f))
                }
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() {
                    if (current()) onRecognizing()
                }
                override fun onError(error: Int) {
                    if (!current()) return
                    cancel()
                    this@EtaSpeechInput.onError(error)
                }
                override fun onResults(results: Bundle?) {
                    if (!current()) return
                    val text = results.text()
                    cancel()
                    if (text.isBlank()) this@EtaSpeechInput.onError(SpeechRecognizer.ERROR_NO_MATCH) else onResult(text)
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    if (current()) partialResults.text().takeIf(String::isNotBlank)?.let(onPartial)
                }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            timeout = Runnable {
                if (generation == session) {
                    cancel()
                    onError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                }
            }.also { handler.postDelayed(it, 30_000) }
            delegate.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1),
            )
        } catch (error: RuntimeException) {
            cancel()
            AndroidAgentLogger.warn("Eta speech start failed: type=${error.javaClass.simpleName}")
            onError(if (error is SecurityException) SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS else SpeechRecognizer.ERROR_CLIENT)
        }
    }

    fun finish() {
        try {
            recognizer?.stopListening()
            onRecognizing()
        } catch (_: RuntimeException) {
            cancel()
            onError(SpeechRecognizer.ERROR_CLIENT)
        }
    }

    fun cancel() {
        generation++
        timeout?.let(handler::removeCallbacks)
        timeout = null
        val previous = recognizer
        recognizer = null
        try {
            previous?.destroy()
        } catch (error: RuntimeException) {
            AndroidAgentLogger.warn("Eta speech release failed: type=${error.javaClass.simpleName}")
        }
    }

    private fun Bundle?.text(): String =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
}
