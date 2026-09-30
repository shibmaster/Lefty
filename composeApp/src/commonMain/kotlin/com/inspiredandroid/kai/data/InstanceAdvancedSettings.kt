package com.inspiredandroid.kai.data

import kotlinx.serialization.Serializable

/**
 * User-tunable request behavior for one configured service instance. Every field is nullable and
 * `null` always means "use the built-in default", so an instance without stored settings behaves
 * exactly as before these settings existed.
 */
@Serializable
data class InstanceAdvancedSettings(
    // Timeouts (seconds)
    val requestTimeoutSec: Int? = null,
    val connectTimeoutSec: Int? = null,
    val socketTimeoutSec: Int? = null,
    // Retries & fallback
    val maxRetries: Int? = null,
    val retryDelaySec: Int? = null,
    val failoverOnTimeout: Boolean? = null,
    val useAsFallback: Boolean? = null,
    // Context & compaction
    val contextWindowTokens: Int? = null,
    val charsPerToken: Int? = null,
    val compactionEnabled: Boolean? = null,
    val compactionThresholdPct: Int? = null,
    val compactionKeepRecent: Int? = null,
    // Sampling
    val temperature: Double? = null,
    val topP: Double? = null,
    val topK: Int? = null,
    val minP: Double? = null,
    val maxTokens: Int? = null,
    val repeatPenalty: Double? = null,
    val presencePenalty: Double? = null,
    val frequencyPenalty: Double? = null,
    val seed: Long? = null,
    val stop: List<String>? = null,
    // Capabilities
    val supportsAudio: Boolean? = null,
    // Voice: a speech-to-text model on this endpoint (OpenAI `/audio/transcriptions`), e.g. Whisper
    // or Qwen3-ASR. Recordings are transcribed with it and sent to the chat model as text.
    val speechToTextModel: String? = null,
    val speechLanguage: String? = null,
) {
    val sttModel: String? get() = speechToTextModel?.trim()?.takeIf { it.isNotEmpty() }

    val isEmpty: Boolean get() = this == InstanceAdvancedSettings()

    val effectiveMaxRetries: Int get() = (maxRetries ?: DEFAULT_MAX_RETRIES).coerceIn(0, 10)
    val effectiveFailoverOnTimeout: Boolean get() = failoverOnTimeout ?: false
    val effectiveUseAsFallback: Boolean get() = useAsFallback ?: true
    val effectiveCharsPerToken: Int get() = (charsPerToken ?: DEFAULT_CHARS_PER_TOKEN).coerceIn(1, 16)
    val effectiveCompactionEnabled: Boolean get() = compactionEnabled ?: true
    val effectiveCompactionThreshold: Double get() = (compactionThresholdPct ?: DEFAULT_COMPACTION_THRESHOLD_PCT).coerceIn(10, 100) / 100.0
    val effectiveCompactionKeepRecent: Int get() = (compactionKeepRecent ?: DEFAULT_COMPACTION_KEEP_RECENT).coerceIn(1, 50)

    /** Delay before retry number [attempt] (0-based): fixed when configured, otherwise linear 1s, 2s, ... */
    fun retryDelayMs(attempt: Int): Long = ((retryDelaySec ?: (attempt + 1)).coerceAtLeast(0)) * 1000L

    val hasSampling: Boolean
        get() = temperature != null || topP != null || topK != null || minP != null || maxTokens != null ||
            repeatPenalty != null || presencePenalty != null || frequencyPenalty != null || seed != null ||
            !stop.isNullOrEmpty()

    companion object {
        const val DEFAULT_REQUEST_TIMEOUT_SEC = 180
        const val DEFAULT_SOCKET_TIMEOUT_SEC = 180
        const val DEFAULT_MAX_RETRIES = 2
        const val DEFAULT_CHARS_PER_TOKEN = 4
        const val DEFAULT_COMPACTION_THRESHOLD_PCT = 70
        const val DEFAULT_COMPACTION_KEEP_RECENT = 4
        const val DEFAULT_ANTHROPIC_MAX_TOKENS = 8192
    }
}
