package com.inspiredandroid.kai.audio

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koin.java.KoinJavaComponent.inject
import kotlin.concurrent.thread
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

    override suspend fun transcribe(file: PlatformFile): SystemSttResult = withContext(Dispatchers.Main) {
        val pcm = runCatching { readWavPcm(file.readBytes()) }.getOrNull()
        if (pcm == null) {
            SystemSttResult.Failed(IllegalStateException("Not a readable WAV file"))
        } else {
            transcribePcm(pcm.first, pcm.second)
        }
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

    /**
     * Plays [pcm] back through the speaker while a live recognizer (mic source, no echo
     * cancellation) listens, so the system can transcribe a recorded clip.
     */
    private suspend fun transcribePcm(pcm: ByteArray, sampleRate: Int): SystemSttResult =
        withContext(Dispatchers.Main) {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            active = recognizer
            val result: SystemSttResult =
                try {
                    withTimeout(SystemStt.FILE_TRANSCRIBE_TIMEOUT_MS) {
                        suspendCancellableCoroutine { cont ->
                        var finished = false
                        fun finish(result: SystemSttResult) {
                            if (finished) return
                            finished = true
                            if (cont.isActive) cont.resume(result)
                        }
                        recognizer.setRecognitionListener(object : RecognitionListener {
                            override fun onReadyForSpeech(params: Bundle?) {
                                // Mic is ready: play the clip back so the recognizer hears it.
                                playPcm(pcm, sampleRate)
                            }
                            override fun onBeginningOfSpeech() = Unit
                            override fun onRmsChanged(rmsdB: Float) = Unit
                            override fun onBufferReceived(buffer: ByteArray?) = Unit
                            override fun onEndOfSpeech() = Unit
                            override fun onError(error: Int) { finish(SystemSttResult.NoSpeech) }
                            override fun onResults(results: Bundle?) {
                                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                                finish(if (text.isBlank()) SystemSttResult.NoSpeech else SystemSttResult.Success(text))
                            }
                            override fun onPartialResults(partialResults: Bundle?) = Unit
                            override fun onEvent(eventType: Int, params: Bundle?) = Unit
                        })
                        cont.invokeOnCancellation { recognizer.stopListening() }
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                            // Plain mic (no echo cancellation) so the played-back clip is heard.
                            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, MediaRecorder.AudioSource.MIC)
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
                    recognizer.destroy()
                }
            result
        }

    private fun playPcm(pcm: ByteArray, sampleRate: Int) {
        thread {
            var track: AudioTrack? = null
            try {
                track = AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    pcm.size,
                    AudioTrack.MODE_STREAM,
                )
                track.play()
                var offset = 0
                while (offset < pcm.size) {
                    offset += track.write(pcm, offset, pcm.size - offset)
                }
                track.stop()
            } catch (_: Exception) {
                // Best-effort playback; a failure just yields no transcription.
            } finally {
                track?.release()
            }
        }
    }

    /** Extracts the PCM payload and sample rate from a 16-bit mono WAV. */
    private fun readWavPcm(bytes: ByteArray): Pair<ByteArray, Int>? {
        if (bytes.size < 44) return null
        if (String(bytes, 0, 4) != "RIFF" || String(bytes, 8, 4) != "WAVE") return null
        val sampleRate = bytes.readIntLe(24)
        var i = 12
        while (i + 8 <= bytes.size) {
            val id = String(bytes, i, 4)
            val size = bytes.readIntLe(i + 4)
            if (id == "data") {
                val start = i + 8
                return bytes.copyOfRange(start, (start + size).coerceAtMost(bytes.size)) to sampleRate
            }
            i += 8 + size
        }
        return null
    }

    private fun ByteArray.readIntLe(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or
            ((this[offset + 3].toInt() and 0xFF) shl 24)
}
