package com.inspiredandroid.kai.audio

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import nl.marc_apps.tts.TextToSpeechInstance

/** Reads assistant text aloud. `say` suspends until speaking finished or [stop] was called. */
interface Speaker {
    suspend fun say(text: String)

    fun stop()
}

/**
 * Speaks with a remote text-to-speech model (OpenAI `/audio/speech`) when one is configured,
 * otherwise with the platform's system voice.
 *
 * Remote speech is split into sentence chunks and pipelined — the next chunk is synthesized while
 * the current one plays — so a long reply starts speaking after its first sentence. If the remote
 * endpoint fails, the rest of that message falls back to the system voice.
 */
class SwitchingSpeaker(
    private val system: TextToSpeechInstance?,
    private val remoteEnabled: () -> Boolean,
    private val synthesize: suspend (String) -> ByteArray,
    private val play: suspend (ByteArray) -> Unit,
    private val stopPlayback: () -> Unit,
    private val systemSay: suspend (String) -> Unit = { system?.say(it) },
) : Speaker {

    private var current: Job? = null

    override suspend fun say(text: String) {
        if (!remoteEnabled()) {
            systemSay(text)
            return
        }
        coroutineScope {
            val job = launch { speakRemote(text) }
            current = job
            job.join() // returns normally when stop() cancels the job
        }
    }

    private suspend fun speakRemote(text: String) = coroutineScope {
        val chunks = chunkForSpeech(text)
        if (chunks.isEmpty()) return@coroutineScope
        // Failures are captured in the Result: a throwing async child would cancel this whole scope
        // before the fallback below could run.
        fun synthesizeAsync(chunk: String): Deferred<Result<ByteArray>> = async { runCatching { synthesize(chunk) } }
        var next: Deferred<Result<ByteArray>>? = synthesizeAsync(chunks[0])
        for (i in chunks.indices) {
            val result = next!!.await()
            val audio = result.getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                // Remote voice unavailable: finish this message with the system voice.
                systemSay(chunks.drop(i).joinToString(" "))
                return@coroutineScope
            }
            next = if (i + 1 < chunks.size) synthesizeAsync(chunks[i + 1]) else null
            play(audio)
        }
    }

    override fun stop() {
        current?.cancel()
        current = null
        stopPlayback()
        system?.stop()
    }
}

/**
 * Splits text into speakable chunks at sentence boundaries: the first chunk short so speech starts
 * quickly, later ones up to [maxChars]. A sentence longer than the limit is split at a word boundary.
 */
fun chunkForSpeech(text: String, firstMaxChars: Int = 160, maxChars: Int = 360): List<String> {
    val sentences = text
        .split(Regex("(?<=[.!?…:;])\\s+|\\n+"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    val chunks = mutableListOf<String>()
    val current = StringBuilder()
    fun limit() = if (chunks.isEmpty()) firstMaxChars else maxChars
    fun flush() {
        if (current.isNotEmpty()) chunks += current.toString()
        current.clear()
    }
    for (sentence in sentences) {
        var rest = sentence
        while (rest.length > limit()) {
            flush()
            val cut = rest.lastIndexOf(' ', limit()).takeIf { it > 0 } ?: limit()
            chunks += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (current.isNotEmpty() && current.length + 1 + rest.length > limit()) flush()
        if (rest.isNotEmpty()) {
            if (current.isNotEmpty()) current.append(' ')
            current.append(rest)
        }
    }
    flush()
    return chunks
}
