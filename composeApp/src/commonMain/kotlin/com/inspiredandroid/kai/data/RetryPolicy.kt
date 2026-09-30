package com.inspiredandroid.kai.data

import com.inspiredandroid.kai.network.AnthropicInsufficientCreditsException
import com.inspiredandroid.kai.network.AnthropicInvalidApiKeyException
import com.inspiredandroid.kai.network.AudioInputNotSupportedException
import com.inspiredandroid.kai.network.GeminiInvalidApiKeyException
import com.inspiredandroid.kai.network.OpenAICompatibleConnectionException
import com.inspiredandroid.kai.network.OpenAICompatibleInvalidApiKeyException
import com.inspiredandroid.kai.network.OpenAICompatibleModelNotFoundException
import com.inspiredandroid.kai.network.OpenAICompatibleQuotaExhaustedException
import com.inspiredandroid.kai.network.OpenAICompatibleTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Failures that will not change on a retry: exhausted credit, bad keys, unknown models. */
internal fun isNonRetryableException(e: Exception): Boolean = e is AnthropicInsufficientCreditsException ||
    e is OpenAICompatibleQuotaExhaustedException ||
    e is OpenAICompatibleInvalidApiKeyException ||
    e is GeminiInvalidApiKeyException ||
    e is AnthropicInvalidApiKeyException ||
    e is OpenAICompatibleModelNotFoundException ||
    e is AudioInputNotSupportedException

/** Timeouts and unreachable servers — the failures "fail over on timeout" skips retries for. */
internal fun isTimeoutOrConnectionException(e: Throwable): Boolean {
    var current: Throwable? = e
    var depth = 0
    while (current != null && depth < 8) {
        if (current is OpenAICompatibleTimeoutException || current is OpenAICompatibleConnectionException) return true
        if (current::class.simpleName?.contains("Timeout", ignoreCase = true) == true) return true
        current = current.cause
        depth++
    }
    return false
}

/**
 * Runs [block], retrying per the instance's [policy]: up to `maxRetries` extra attempts with the
 * configured delay. Non-retryable failures, and timeouts when `failoverOnTimeout` is set, are
 * rethrown immediately so the fallback chain can move on.
 */
internal suspend fun <T> retryWithPolicy(
    policy: InstanceAdvancedSettings,
    sleep: suspend (Long) -> Unit = { delay(it) },
    block: suspend () -> T,
): T {
    val maxRetries = policy.effectiveMaxRetries
    var lastException: Exception? = null
    for (attempt in 0..maxRetries) {
        try {
            return block()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (isNonRetryableException(e)) throw e
            if (policy.effectiveFailoverOnTimeout && isTimeoutOrConnectionException(e)) throw e
            lastException = e
            if (attempt < maxRetries) sleep(policy.retryDelayMs(attempt))
        }
    }
    throw lastException!!
}
