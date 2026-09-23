package io.github.mangi.eta.agent.voice

import android.app.Application
import android.os.Bundle
import android.os.Looper
import android.speech.SpeechRecognizer
import java.time.Duration
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EtaSpeechInputTest {
    private val results = mutableListOf<String>()
    private val errors = mutableListOf<Int>()
    private val partials = mutableListOf<String>()
    private lateinit var input: EtaSpeechInput

    @Before fun setup() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        input = EtaSpeechInput(RuntimeEnvironment.getApplication(), {}, {}, {}, partials::add, results::add, errors::add)
    }

    private fun start(): ShadowSpeechRecognizer {
        input.start()
        shadowOf(Looper.getMainLooper()).idle()
        return shadowOf(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
    }

    private fun text(value: String) = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(value))
    }

    @Test fun finalResultIsDeliveredOnceAndReleasesRecognizer() {
        val recognizer = start()
        recognizer.triggerOnPartialResults(text("草稿"))
        assertEquals(listOf("草稿"), partials)
        recognizer.triggerOnResults(text(" 最终问题 "))
        recognizer.triggerOnResults(text("重复结果"))
        assertEquals(listOf("最终问题"), results)
        assertTrue(recognizer.isDestroyed)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(31))
        assertTrue(errors.isEmpty())
    }

    @Test fun errorReturnsToOwnerAndReleasesRecognizer() {
        val recognizer = start()
        recognizer.triggerOnError(SpeechRecognizer.ERROR_NETWORK)
        assertEquals(listOf(SpeechRecognizer.ERROR_NETWORK), errors)
        assertTrue(recognizer.isDestroyed)
    }

    @Test fun canceledCallbacksCannotSubmitOrReplaceANewSession() {
        val old = start()
        input.cancel()
        val current = start()
        old.triggerOnResults(text("过期结果"))
        old.triggerOnPartialResults(text("过期草稿"))
        old.triggerOnError(SpeechRecognizer.ERROR_CLIENT)
        assertTrue(results.isEmpty())
        assertTrue(partials.isEmpty())
        assertTrue(errors.isEmpty())
        current.triggerOnResults(text("本次结果"))
        assertEquals(listOf("本次结果"), results)
    }

    @Test fun emptyResultsDoNotSubmit() {
        val recognizer = start()
        recognizer.triggerOnResults(Bundle())
        assertTrue(results.isEmpty())
        assertEquals(listOf(SpeechRecognizer.ERROR_NO_MATCH), errors)
    }

    @Test fun stalledServiceTimesOutAndRejectsLateResults() {
        val recognizer = start()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
        assertEquals(listOf(SpeechRecognizer.ERROR_SPEECH_TIMEOUT), errors)
        recognizer.triggerOnResults(text("迟到结果"))
        assertTrue(results.isEmpty())
        assertTrue(recognizer.isDestroyed)
    }
}
