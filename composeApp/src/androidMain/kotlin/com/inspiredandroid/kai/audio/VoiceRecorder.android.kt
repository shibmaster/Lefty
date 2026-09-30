package com.inspiredandroid.kai.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.koin.java.KoinJavaComponent.inject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.io.encoding.Base64
import kotlin.math.sqrt

actual fun createVoiceRecorder(): VoiceRecorder = AndroidVoiceRecorder()

actual fun createAudioPlayer(): AudioPlayer = AndroidAudioPlayer()

private const val SAMPLE_RATE = VOICE_SAMPLE_RATE
private const val CHUNK_MS = 50
private const val MAX_KEPT_RECORDINGS = 20

/** RMS (16-bit scale) below which a chunk counts as silence, before noise-floor adaptation. */
private const val MIN_SPEECH_RMS = 600.0

/** Chunks used to estimate the background noise floor at the start of a take. */
private const val NOISE_FLOOR_CHUNKS = 6

/**
 * Records 16 kHz mono PCM with [AudioRecord] and writes it out as WAV — the format llama.cpp's
 * `input_audio` accepts directly. Silence detection is an RMS energy gate relative to the noise
 * floor measured over the first few hundred milliseconds.
 */
private class AndroidVoiceRecorder : VoiceRecorder {
    private val context: Context by inject(Context::class.java)

    @Volatile private var stopRequested = false

    @Volatile private var cancelRequested = false

    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level

    override fun isSupported(): Boolean = true

    override fun stop() {
        stopRequested = true
    }

    override fun cancel() {
        cancelRequested = true
    }

    @SuppressLint("MissingPermission") // Callers request RECORD_AUDIO first; a SecurityException is still handled.
    override suspend fun record(vad: VadConfig?, maxDurationMs: Long): RecordingResult = withContext(Dispatchers.IO) {
        stopRequested = false
        cancelRequested = false

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return@withContext RecordingResult.Failed(IllegalStateException("Audio input unavailable"))
        val chunkSamples = SAMPLE_RATE * CHUNK_MS / 1000
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, chunkSamples * 2 * 4),
            )
        } catch (e: SecurityException) {
            return@withContext RecordingResult.Failed(e)
        } catch (e: IllegalArgumentException) {
            return@withContext RecordingResult.Failed(e)
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return@withContext RecordingResult.Failed(IllegalStateException("AudioRecord failed to initialize"))
        }

        val pcm = ByteArrayOutputStream()
        val buffer = ShortArray(chunkSamples)
        var elapsedMs = 0L
        var speechStarted = false
        var silenceMs = 0L
        var noiseFloorSum = 0.0
        var noiseFloorCount = 0
        var threshold = MIN_SPEECH_RMS
        var noSpeech = false

        try {
            recorder.startRecording()
            while (isActive) {
                if (cancelRequested) return@withContext RecordingResult.Cancelled
                if (stopRequested) break
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                for (i in 0 until read) {
                    val sample = buffer[i].toInt()
                    pcm.write(sample and 0xFF)
                    pcm.write((sample shr 8) and 0xFF)
                }
                val chunkMs = read * 1000L / SAMPLE_RATE
                elapsedMs += chunkMs

                var sumSquares = 0.0
                for (i in 0 until read) {
                    val v = buffer[i].toDouble()
                    sumSquares += v * v
                }
                val rms = sqrt(sumSquares / read)
                _level.value = (rms / 8000.0).toFloat().coerceIn(0f, 1f)

                if (vad != null) {
                    if (noiseFloorCount < NOISE_FLOOR_CHUNKS) {
                        noiseFloorSum += rms
                        noiseFloorCount++
                        if (noiseFloorCount == NOISE_FLOOR_CHUNKS) {
                            threshold = maxOf(MIN_SPEECH_RMS, (noiseFloorSum / noiseFloorCount) * 2.5)
                        }
                    } else if (rms > threshold) {
                        speechStarted = true
                        silenceMs = 0
                    } else if (speechStarted) {
                        silenceMs += chunkMs
                    }
                    if (!speechStarted && elapsedMs >= vad.noSpeechTimeoutMs) {
                        noSpeech = true
                        break
                    }
                    if (speechStarted && silenceMs >= vad.silenceMs) break
                }
                if (elapsedMs >= maxDurationMs) break
            }
            ensureActive()
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
            _level.value = 0f
        }

        if (noSpeech) return@withContext RecordingResult.NoSpeech
        if (pcm.size() == 0) return@withContext RecordingResult.Cancelled
        val file = writeWav(pcm.toByteArray())
        RecordingResult.Recorded(PlatformFile(file), elapsedMs)
    }

    private fun writeWav(pcm: ByteArray): File {
        val dir = File(context.cacheDir, "voice").apply { mkdirs() }
        // Keep the cache bounded: voice files are only needed until they've been read into the message.
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_KEPT_RECORDINGS)?.forEach { it.delete() }
        val file = File(dir, "voice-${System.currentTimeMillis()}.wav")
        FileOutputStream(file).use { out ->
            out.write(wavHeader(pcm.size))
            out.write(pcm)
        }
        return file
    }
}

/** Plays base64 audio attachments through [MediaPlayer] from a temp file. */
private class AndroidAudioPlayer : AudioPlayer {
    private val context: Context by inject(Context::class.java)
    private var player: MediaPlayer? = null
    private val _playingKey = MutableStateFlow<String?>(null)
    override val playingKey: StateFlow<String?> = _playingKey

    override fun play(key: String, base64Data: String, mimeType: String) {
        stop()
        try {
            val ext = when {
                mimeType.contains("wav") -> "wav"
                mimeType.contains("mpeg") || mimeType.contains("mp3") -> "mp3"
                mimeType.contains("ogg") -> "ogg"
                mimeType.contains("flac") -> "flac"
                else -> "m4a"
            }
            val file = File(context.cacheDir, "playback.$ext")
            file.writeBytes(Base64.decode(base64Data))
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { stop() }
                setOnErrorListener { _, _, _ ->
                    stop()
                    true
                }
                prepare()
                start()
            }
            _playingKey.value = key
        } catch (_: Exception) {
            stop()
        }
    }

    override suspend fun playAndAwait(bytes: ByteArray, mimeType: String) {
        stop()
        val file = File.createTempFile("speech-", ".${extensionFor(mimeType)}", context.cacheDir)
        try {
            file.writeBytes(bytes)
            suspendCancellableCoroutine { cont ->
                val mp = MediaPlayer()
                player = mp
                fun finish() {
                    if (player === mp) player = null
                    runCatching { mp.release() }
                    if (cont.isActive) cont.resume(Unit)
                }
                try {
                    mp.setDataSource(file.absolutePath)
                    mp.setOnCompletionListener { finish() }
                    mp.setOnErrorListener { _, _, _ ->
                        finish()
                        true
                    }
                    mp.prepare()
                    mp.start()
                } catch (_: Exception) {
                    finish()
                }
                cont.invokeOnCancellation {
                    runCatching { mp.stop() }
                    if (player === mp) player = null
                    runCatching { mp.release() }
                }
            }
        } finally {
            file.delete()
        }
    }

    override fun stop() {
        player?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        player = null
        _playingKey.value = null
    }
}

private fun extensionFor(mimeType: String): String = when {
    mimeType.contains("wav") -> "wav"
    mimeType.contains("mpeg") || mimeType.contains("mp3") -> "mp3"
    mimeType.contains("ogg") -> "ogg"
    mimeType.contains("flac") -> "flac"
    else -> "m4a"
}
