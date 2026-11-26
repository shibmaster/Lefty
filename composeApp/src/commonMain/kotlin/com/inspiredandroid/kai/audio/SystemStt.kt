package com.inspiredandroid.kai.audio

import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.flow.StateFlow

/**
 * Result of one system speech-recognition session.
 */
sealed interface SystemSttResult {
    /** The utterance the system recognized. */
    data class Success(val text: String) : SystemSttResult

    /** The system heard nothing. */
    data object NoSpeech : SystemSttResult

    data object Cancelled : SystemSttResult

    data class Failed(val error: Throwable?) : SystemSttResult
}

/**
 * The platform's built-in speech-to-text (on Android, the system voice-typing service, e.g.
 * Google's — mostly on-device, no server needed). It drives the microphone itself: one
 * [transcribe] call is one utterance, ended by the system's own speech detection.
 */
interface SystemStt {
    fun isSupported(): Boolean

    /** Normalized input level (0..1) of the running session, for UI feedback. */
    val level: StateFlow<Float>

    /**
     * Listens for one utterance and suspends until the system ends it ([SystemSttResult.Success]
     * or [SystemSttResult.NoSpeech]), [cancel], or [maxDurationMs].
     */
    suspend fun transcribe(maxDurationMs: Long = DEFAULT_MAX_DURATION_MS): SystemSttResult

    /**
     * Transcribes a recorded audio file. The live recognizer only hears the microphone, so the
     * file is played back through the speaker while a mic-source recognizer (no echo cancellation)
     * listens. Used after the app's own VAD capture so the take is ended by the configurable
     * silence pause — not by the system's short built-in one.
     */
    suspend fun transcribe(file: PlatformFile): SystemSttResult

    /** Ends the running session and discards it. */
    fun cancel()

    companion object {
        /** The system recognizer caps utterances well below the recorder's two minutes. */
        const val DEFAULT_MAX_DURATION_MS = 60_000L

        /** A talk-mode take is a sentence or two; cap the play-back transcription well under a minute. */
        const val FILE_TRANSCRIBE_TIMEOUT_MS = 30_000L
    }
}

expect fun createSystemStt(): SystemStt

/** Recognizer for platforms without a built-in speech service. */
internal class UnsupportedSystemStt : SystemStt {
    override fun isSupported(): Boolean = false
    override val level: StateFlow<Float> = kotlinx.coroutines.flow.MutableStateFlow(0f)
    override suspend fun transcribe(maxDurationMs: Long): SystemSttResult =
        SystemSttResult.Failed(UnsupportedOperationException("System speech recognition is not supported on this platform"))
    override suspend fun transcribe(file: PlatformFile): SystemSttResult =
        SystemSttResult.Failed(UnsupportedOperationException("System speech recognition is not supported on this platform"))
    override fun cancel() = Unit
}
