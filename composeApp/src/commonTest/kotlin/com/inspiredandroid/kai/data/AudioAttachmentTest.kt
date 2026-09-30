package com.inspiredandroid.kai.data

import com.inspiredandroid.kai.ui.chat.History
import com.inspiredandroid.kai.ui.chat.toAnthropicContentBlocks
import com.inspiredandroid.kai.ui.chat.toGroqMessageDto
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioAttachmentTest {

    private val wav = Attachment(data = "UklGRg==", mimeType = "audio/wav", fileName = "voice-1.wav")

    private fun userWithAudio(text: String = "") = History(role = History.Role.USER, content = text, attachments = persistentListOf(wav))

    @Test
    fun `audio files are classified by mime type and extension`() {
        assertEquals(FileCategory.AUDIO, classifyFile("audio/wav", "a.wav"))
        assertEquals(FileCategory.AUDIO, classifyFile(null, "clip.mp3"))
        assertEquals(FileCategory.AUDIO, classifyFile("audio/ogg", null))
        assertEquals(FileCategory.UNSUPPORTED, classifyFile(null, "clip.xyz"))
    }

    @Test
    fun `openai audio format only covers wav and mp3`() {
        assertEquals("wav", openAIAudioFormat("audio/x-wav"))
        assertEquals("mp3", openAIAudioFormat("audio/mpeg"))
        assertEquals("wav", openAIAudioFormat("application/octet-stream", "rec.wav"))
        assertNull(openAIAudioFormat("audio/mp4", "rec.m4a"))
    }

    @Test
    fun `audio mime falls back to the extension`() {
        assertEquals("audio/wav", audioMimeType(null, "a.wav"))
        assertEquals("audio/mpeg", audioMimeType("application/octet-stream", "a.mp3"))
        assertEquals("audio/ogg", audioMimeType("audio/ogg", "a.ogg"))
    }

    @Test
    fun `audio becomes an input_audio part when supported`() {
        val content = userWithAudio("what did I say?").toGroqMessageDto(supportsAudio = true).content as JsonArray
        val text = content[0].jsonObject
        assertEquals("text", text["type"]!!.jsonPrimitive.content)
        val audio = content[1].jsonObject
        assertEquals("input_audio", audio["type"]!!.jsonPrimitive.content)
        val inner = audio["input_audio"] as JsonObject
        assertEquals("UklGRg==", inner["data"]!!.jsonPrimitive.content)
        assertEquals("wav", inner["format"]!!.jsonPrimitive.content)
    }

    @Test
    fun `audio is dropped for models without audio input`() {
        val content = userWithAudio("hi").toGroqMessageDto(supportsAudio = false).content
        assertEquals(JsonPrimitive("hi"), content)
    }

    @Test
    fun `anthropic never receives audio blocks`() {
        val content = userWithAudio("hi").toAnthropicContentBlocks()
        assertEquals(JsonPrimitive("hi"), content)
    }

    @Test
    fun `audio model heuristic`() {
        assertTrue(modelSupportsAudio(Service.OpenAICompatible, "Qwen2.5-Omni-7B"))
        assertTrue(modelSupportsAudio(Service.OpenAICompatible, "mistralai/Voxtral-Mini-3B"))
        assertTrue(modelSupportsAudio(Service.Gemini, "gemini-2.5-flash"))
        assertFalse(modelSupportsAudio(Service.OpenAICompatible, "llama-3.1-8b"))
        assertFalse(modelSupportsAudio(Service.Anthropic, "claude-omni"))
    }
}
