package com.inspiredandroid.kai.audio

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
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

    override suspend fun transcribe(file: PlatformFile): SystemSttResult = withContext(Dispatchers.Main) {
        val pcm = runCatching { file.readBytes() }.getOrNull()?.let { readWavPcm(it) }
        if (pcm == null) {
            SystemSttResult.Failed(IllegalStateException("Not a readable WAV file"))
        } else {
            transcribePcm(pcm.first, pcm.second)
        }
    }

    /**
     * Plays [pcm] back through the speaker while a live recognizer (mic source, no echo
     * cancellation) listens, so the system can transcribe a recorded clip. The speaker is the
     * preferred output so the mic hears it even with the screen off.
     */
    private suspend fun transcribePcm(pcm: ByteArray, sampleRate: Int): SystemSttResult =
        withContext(Dispatchers.Main) {
            cancelRequested = false
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            active = recognizer
            val result: SystemSttResult =
                try {
                    withTimeout(SystemStt.FILE_TRANSCRIBE_TIMEOUT_MS) {
                        suspendCancellableCoroutine { cont ->
                            var finished = false
                            fun finish(res: SystemSttResult) {
                                if (finished) return
                                finished = true
                                _level.value = 0f
                                if (cont.isActive) cont.resume(res)
                            }
                            recognizer.setRecognitionListener(object : RecognitionListener {
                                override fun onReadyForSpeech(params: Bundle?) {
                                    // Mic is ready: play the clip back so the recognizer hears it.
                                    playPcm(pcm, sampleRate) {
                                        // Give the recognizer a moment to process the tail,
                                        // then finalize so we don't wait out the full timeout.
                                        thread {
                                            runCatching { Thread.sleep(800) }
                                            runCatching { recognizer.stopListening() }
                                        }
                                    }
                                }

                                override fun onBeginningOfSpeech() = Unit
                                override fun onRmsChanged(rmsdB: Float) = Unit
                                override fun onBufferReceived(buffer: ByteArray?) = Unit
                                override fun onEndOfSpeech() = Unit
                                override fun onError(error: Int) {
                                    if (cancelRequested) finish(SystemSttResult.Cancelled) else finish(SystemSttResult.NoSpeech)
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

    private fun playPcm(pcm: ByteArray, sampleRate: Int, onDone: () -> Unit) {
        thread {
            var track: AudioTrack? = null
            try {
                val builder = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build(),
                    )
                    .setBufferSizeInBytes(pcm.size)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                track = builder.build()
                // Route to the speaker so the mic hears it (screen-off talk mode).
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val speaker = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    if (speaker != null) runCatching { track.setPreferredDevice(speaker) }
                }
                track.play()
                var offset = 0
                while (offset < pcm.size) {
                    offset += track.write(pcm, offset, pcm.size - offset)
                }
                track.stop()
            } catch (_: Exception) {
                // Best-effort playback; a failure just yields no transcription.
            } finally {
                runCatching { track?.release() }
                onDone()
            }
        }
    }

    /** Returns (pcm, sampleRate) from a 16-bit mono/stereo WAV, or null if unparseable. */
    private fun readWavPcm(bytes: ByteArray): Pair<ByteArray, Int>? {
        if (bytes.size < 44 || bytes[0].toInt() != 'W'.code || bytes[1].toInt() != 'A'.code) return null
        var offset = 12
        var sampleRate = 0
        var channels = 0
        var bitsPerSample = 0
        var dataOffset = -1
        var dataLength = 0
        while (offset + 8 <= bytes.size) {
            val id = bytes.sliceArray(offset until offset + 4)
            val size = intLe(bytes, offset + 4)
            when (String(id, Charsets.US_ASCII)) {
                "fmt " -> {
                    // fmt chunk layout (relative to chunk start): +8 format, +10 channels,
                    // +12 sampleRate, +16 byteRate, +20 blockAlign, +22 bitsPerSample.
                    sampleRate = intLe(bytes, offset + 12)
                    channels = shortLe(bytes, offset + 10).toInt()
                    bitsPerSample = shortLe(bytes, offset + 22).toInt()
                }

                "data" -> {
                    dataOffset = offset + 8
                    dataLength = minOf(size, bytes.size - dataOffset)
                }
            }
            offset += 8 + size
            if (offset % 2 == 1) offset++ // chunks are word-aligned
        }
        if (dataOffset < 0 || sampleRate == 0 || bitsPerSample != 16) return null
        val src = bytes.sliceArray(dataOffset until dataOffset + dataLength)
        return if (channels == 1) {
            Pair(src, sampleRate)
        } else {
            // Down-mix to mono so the recognizer hears a clean signal.
            val mono = ByteArray(src.size / (channels * 2) * 2)
            var o = 0
            for (i in src.indices step (channels * 2)) {
                var sum = 0
                for (c in 0 until channels) sum += shortLe(bytes, dataOffset + i + c * 2).toInt()
                val v = sum / channels
                mono[o++] = (v and 0xFF).toByte()
                mono[o++] = ((v shr 8) and 0xFF).toByte()
            }
            Pair(mono, sampleRate)
        }
    }

    private fun intLe(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun shortLe(b: ByteArray, off: Int): Short =
        ((b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)).toShort()
}
