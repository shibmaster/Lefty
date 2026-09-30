package com.inspiredandroid.kai.audio

import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.flow.StateFlow

/**
 * Silence detection for hands-free recording. A take starts once speech is heard and ends after
 * [silenceMs] of quiet; if nobody speaks within [noSpeechTimeoutMs] the take ends as
 * [RecordingResult.NoSpeech].
 */
data class VadConfig(
    val silenceMs: Long = DEFAULT_SILENCE_MS,
    val noSpeechTimeoutMs: Long = DEFAULT_NO_SPEECH_TIMEOUT_MS,
) {
    companion object {
        const val DEFAULT_SILENCE_MS = 1200L
        const val DEFAULT_NO_SPEECH_TIMEOUT_MS = 15_000L
    }
}

sealed interface RecordingResult {
    /** A finished take, saved as a 16 kHz mono WAV file. */
    data class Recorded(val file: PlatformFile, val durationMs: Long) : RecordingResult

    /** Silence detection saw no speech before its timeout. */
    data object NoSpeech : RecordingResult

    data object Cancelled : RecordingResult

    data class Failed(val error: Throwable?) : RecordingResult
}

/** Microphone capture for voice messages. Only Android records; other platforms report unsupported. */
interface VoiceRecorder {
    fun isSupported(): Boolean

    /** Normalized input level (0..1) of the running take, for UI feedback. */
    val level: StateFlow<Float>

    /**
     * Records until [stop] (result: [RecordingResult.Recorded]), [cancel], silence detection
     * ([vad] non-null), or [maxDurationMs]. Suspends for the whole take.
     */
    suspend fun record(vad: VadConfig?, maxDurationMs: Long = DEFAULT_MAX_DURATION_MS): RecordingResult

    /** Ends the running take and keeps what was recorded. */
    fun stop()

    /** Ends the running take and discards it. */
    fun cancel()

    companion object {
        const val DEFAULT_MAX_DURATION_MS = 120_000L
    }
}

/** Plays back audio attachments (voice messages). */
interface AudioPlayer {
    /** Key of the clip currently playing, or null. */
    val playingKey: StateFlow<String?>

    fun play(key: String, base64Data: String, mimeType: String)

    fun stop()
}

expect fun createVoiceRecorder(): VoiceRecorder

expect fun createAudioPlayer(): AudioPlayer

/** Recorder for platforms without microphone support. */
internal class UnsupportedVoiceRecorder : VoiceRecorder {
    override fun isSupported(): Boolean = false
    override val level: StateFlow<Float> = kotlinx.coroutines.flow.MutableStateFlow(0f)
    override suspend fun record(vad: VadConfig?, maxDurationMs: Long): RecordingResult = RecordingResult.Failed(UnsupportedOperationException("Recording is not supported on this platform"))
    override fun stop() = Unit
    override fun cancel() = Unit
}

/** Player for platforms without audio playback support. */
internal class UnsupportedAudioPlayer : AudioPlayer {
    override val playingKey: StateFlow<String?> = kotlinx.coroutines.flow.MutableStateFlow(null)
    override fun play(key: String, base64Data: String, mimeType: String) = Unit
    override fun stop() = Unit
}
