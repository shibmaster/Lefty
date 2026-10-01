package com.inspiredandroid.kai.audio

import com.inspiredandroid.kai.network.speechInput
import com.inspiredandroid.kai.network.speechRequestJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SpeakerTest {

    @Test
    fun `chunks follow sentences with a short first chunk`() {
        val text = List(12) { "This is sentence number $it of the reply." }.joinToString(" ")
        val chunks = chunkForSpeech(text, firstMaxChars = 80, maxChars = 200)
        assertTrue(chunks.first().length <= 80)
        assertTrue(chunks.all { it.length <= 200 })
        assertTrue(chunks.all { it.endsWith(".") }, "chunks end at sentence boundaries: $chunks")
        assertEquals(text, chunks.joinToString(" "))
    }

    @Test
    fun `overlong sentence is cut at a word boundary`() {
        val text = List(60) { "word$it" }.joinToString(" ")
        val chunks = chunkForSpeech(text, firstMaxChars = 50, maxChars = 100)
        assertTrue(chunks.all { it.length <= 100 })
        assertEquals(text.split(" "), chunks.flatMap { it.split(" ") })
    }

    @Test
    fun `blank text has no chunks`() {
        assertTrue(chunkForSpeech("  \n ").isEmpty())
    }

    private class Recorder {
        val synthesized = mutableListOf<String>()
        val played = mutableListOf<String>()
        val systemSaid = mutableListOf<String>()
    }

    private fun speaker(rec: Recorder, remote: Boolean, failOn: String? = null, playGate: CompletableDeferred<Unit>? = null) = SwitchingSpeaker(
        system = null,
        remoteEnabled = { remote },
        synthesize = { text ->
            rec.synthesized += text
            if (failOn != null && text.contains(failOn)) error("server down")
            text.encodeToByteArray()
        },
        play = { bytes ->
            playGate?.await()
            rec.played += bytes.decodeToString()
        },
        stopPlayback = {},
        systemSay = { rec.systemSaid += it },
    )

    @Test
    fun `system voice is used without a remote model`() = runTest {
        val rec = Recorder()
        speaker(rec, remote = false).say("Hello there.")
        assertEquals(listOf("Hello there."), rec.systemSaid)
        assertTrue(rec.synthesized.isEmpty())
    }

    @Test
    fun `remote voice plays every chunk in order`() = runTest {
        val rec = Recorder()
        val text = List(10) { "Sentence $it is here." }.joinToString(" ")
        speaker(rec, remote = true).say(text)
        assertEquals(chunkForSpeech(text), rec.played)
        assertTrue(rec.systemSaid.isEmpty())
    }

    @Test
    fun `remote failure falls back to the system voice for the rest`() = runTest {
        val rec = Recorder()
        val text = "First part. " + List(20) { "Filler sentence $it." }.joinToString(" ") + " BROKEN tail."
        speaker(rec, remote = true, failOn = "BROKEN").say(text)
        assertFalse(rec.played.any { it.contains("BROKEN") })
        assertTrue(rec.systemSaid.single().contains("BROKEN"))
    }

    @Test
    fun `stop ends a pending say`() = runTest {
        val rec = Recorder()
        val gate = CompletableDeferred<Unit>() // playback never finishes on its own
        val speaker = speaker(rec, remote = true, playGate = gate)
        var finished = false
        val job = launch {
            speaker.say("One. Two. Three.")
            finished = true
        }
        advanceUntilIdle()
        assertFalse(finished)
        speaker.stop()
        advanceUntilIdle()
        assertTrue(finished, "say returns normally after stop()")
        assertTrue(job.isCompleted)
    }

    @Test
    fun `speech request body`() {
        val body = Json.parseToJsonElement(speechRequestJson("voice-tts", "Hi", "alloy", null, "mp3")).jsonObject
        assertEquals("voice-tts", body["model"]!!.jsonPrimitive.content)
        assertEquals("Hi", body["input"]!!.jsonPrimitive.content)
        assertEquals("alloy", body["voice"]!!.jsonPrimitive.content)
        assertEquals("mp3", body["response_format"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("speed"))
    }

    @Test
    fun `voice description goes in front of the text`() {
        assertEquals("Hi.", speechInput("Hi.", null))
        assertEquals("Hi.", speechInput("Hi.", "  "))
        assertEquals("[deep male voice] Hi.", speechInput("Hi.", " deep male voice "))
        // A "]" inside the description would end the instruction early.
        assertEquals("[calm (whispering) tone] Hi.", speechInput("Hi.", "calm [whispering] tone"))
    }
}
