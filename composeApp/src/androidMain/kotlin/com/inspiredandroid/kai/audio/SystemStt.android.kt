package com.inspiredandroid.kai.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koin.java.KoinJavaComponent.inject
import kotlin.coroutines.resume

actual fun createSystemStt(): SystemStt = AndroidSystemStt()

/**
 * One utterance at a time through the system speech-recognition service (Google's voice typing
 * on most devices, mostly on-device). The service owns the microphone and detects the end of
 * speech itself, so there is no VAD here.
 */
private class AndroidSystemStt : SystemStt {
    private val context: Context by inject(Context::class.java)

    @Volatile private var active: SpeechRecognizer? = null

    @Volatile private var cancelRequested = false

    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level

    override fun isSupported(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    override fun cancel() {
        cancelRequested = true
        active?.stopListening()
    }

    override suspend fun transcribe(maxDurationMs: Long): SystemSttResult = withContext(Dispatchers.Main) {
        cancelRequested = false
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        active = recognizer
        val result: SystemSttResult =
            try {
                withTimeout(maxDurationMs) {
                    suspendCancellableCoroutine { cont ->
                    var finished = false
                    fun finish(result: SystemSttResult) {
                        if (finished) return
                        finished = true
                        _level.value = 0f
                        if (cont.isActive) cont.resume(result)
                    }
                    recognizer.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) = Unit
                        override fun onBeginningOfSpeech() = Unit
                        override fun onRmsChanged(rmsdB: Float) {
                            // rms is a dB-ish value around -160..0; map it to 0..1 for the UI.
                            _level.value = ((rmsdB + 160f) / 160f).coerceIn(0f, 1f)
                        }
                        override fun onBufferReceived(buffer: ByteArray?) = Unit
                        override fun onEndOfSpeech() = Unit
                        override fun onError(error: Int) {
                            if (cancelRequested) {
                                finish(SystemSttResult.Cancelled)
                                return
                            }
                            when (error) {
                                SpeechRecognizer.ERROR_NO_MATCH,
                                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> finish(SystemSttResult.NoSpeech)
                                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> finish(SystemSttResult.Failed(SecurityException("Microphone permission missing")))
                                else -> finish(SystemSttResult.Failed(IllegalStateException("System speech recognition failed (code $error)")))
                            }
                        }
                        override fun onResults(results: Bundle?) {
                            if (cancelRequested) {
                                finish(SystemSttResult.Cancelled)
                                return
                            }
                            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                            finish(if (text.isBlank()) SystemSttResult.NoSpeech else SystemSttResult.Success(text))
                        }
                        override fun onPartialResults(partialResults: Bundle?) = Unit
                        override fun onEvent(eventType: Int, params: Bundle?) = Unit
                    })
                    cont.invokeOnCancellation {
                        // Coroutine cancelled (timeout, leaving talk mode, VM cleared): stop
                        // without a result; finish() is a no-op once the coroutine is gone.
                        recognizer.stopListening()
                    }
                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                    }
                    try {
                        recognizer.startListening(intent)
                    } catch (e: Exception) {
                        finish(SystemSttResult.Failed(e))
                    }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                SystemSttResult.Failed(e)
            } finally {
                active = null
                _level.value = 0f
                recognizer.destroy()
            }
        result
    }
}
