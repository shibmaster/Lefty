package com.inspiredandroid.kai.data

import com.inspiredandroid.kai.network.OpenAICompatibleConnectionException
import com.inspiredandroid.kai.network.OpenAICompatibleGenericException
import com.inspiredandroid.kai.network.OpenAICompatibleInvalidApiKeyException
import com.inspiredandroid.kai.network.dtos.openaicompatible.OpenAICompatibleChatRequestDto
import com.inspiredandroid.kai.network.llamaCppRootUrl
import com.inspiredandroid.kai.network.parseLlamaCppProps
import com.inspiredandroid.kai.network.toGeminiGenerationConfig
import com.inspiredandroid.kai.network.withSampling
import com.inspiredandroid.kai.ui.chat.History
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstanceAdvancedSettingsTest {

    // region storage

    @Test
    fun `default settings are empty and not stored`() {
        val appSettings = AppSettings(MapSettings())
        appSettings.setInstanceAdvancedSettings("compat", InstanceAdvancedSettings())
        assertTrue(appSettings.getInstanceAdvancedSettings("compat").isEmpty)
        assertFalse(appSettings.settings.hasKey("instance_compat_advanced"))
    }

    @Test
    fun `advanced settings round-trip and are cleared with the instance`() {
        val appSettings = AppSettings(MapSettings())
        val advanced = InstanceAdvancedSettings(requestTimeoutSec = 600, temperature = 0.3, topK = 20, stop = listOf("</s>"), supportsAudio = true)
        appSettings.setInstanceAdvancedSettings("compat", advanced)
        assertEquals(advanced, appSettings.getInstanceAdvancedSettings("compat"))

        appSettings.removeInstanceSettings("compat")
        assertTrue(appSettings.getInstanceAdvancedSettings("compat").isEmpty)
    }

    @Test
    fun `corrupt stored json falls back to defaults`() {
        val appSettings = AppSettings(MapSettings())
        appSettings.settings.putString("instance_compat_advanced", "{not json")
        assertTrue(appSettings.getInstanceAdvancedSettings("compat").isEmpty)
    }

    @Test
    fun `export and import carry advanced settings`() {
        val source = AppSettings(MapSettings())
        source.setConfiguredServiceInstances(listOf(ServiceInstance("compat", Service.OpenAICompatible.id)))
        val advanced = InstanceAdvancedSettings(contextWindowTokens = 32768, maxRetries = 0, useAsFallback = false)
        source.setInstanceAdvancedSettings("compat", advanced)
        val exported = source.exportToJson(toolIds = emptyList())

        val instanceJson = (exported["instance_settings"] as kotlinx.serialization.json.JsonArray)[0].jsonObject
        assertEquals("32768", instanceJson["advanced"]!!.jsonObject["contextWindowTokens"]!!.jsonPrimitive.content)

        val target = AppSettings(MapSettings())
        target.importFromJson(exported, toolIds = emptyList())
        assertEquals(advanced, target.getInstanceAdvancedSettings("compat"))
    }

    // endregion

    // region effective values

    @Test
    fun `effective values default to previous hardcoded behavior`() {
        val d = InstanceAdvancedSettings()
        assertEquals(2, d.effectiveMaxRetries)
        assertEquals(4, d.effectiveCharsPerToken)
        assertEquals(0.7, d.effectiveCompactionThreshold)
        assertEquals(4, d.effectiveCompactionKeepRecent)
        assertTrue(d.effectiveCompactionEnabled)
        assertTrue(d.effectiveUseAsFallback)
        assertFalse(d.effectiveFailoverOnTimeout)
        assertEquals(1000, d.retryDelayMs(0))
        assertEquals(2000, d.retryDelayMs(1))
        assertEquals(5000, InstanceAdvancedSettings(retryDelaySec = 5).retryDelayMs(1))
    }

    // endregion

    // region sampling on the wire

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    private fun encode(dto: OpenAICompatibleChatRequestDto): JsonObject = json.parseToJsonElement(json.encodeToString(OpenAICompatibleChatRequestDto.serializer(), dto)).jsonObject

    @Test
    fun `unset sampling is omitted from the openai body`() {
        val body = encode(OpenAICompatibleChatRequestDto(messages = emptyList(), model = "m").withSampling(InstanceAdvancedSettings(), includeLlamaCppExtras = true))
        listOf("temperature", "top_p", "max_tokens", "top_k", "min_p", "repeat_penalty", "seed", "stop").forEach {
            assertFalse(body.containsKey(it), "$it should be omitted")
        }
    }

    @Test
    fun `llama cpp extras are only sent when enabled`() {
        val advanced = InstanceAdvancedSettings(temperature = 0.3, topK = 20, minP = 0.05, repeatPenalty = 1.1, maxTokens = 512)
        val llama = encode(OpenAICompatibleChatRequestDto(messages = emptyList()).withSampling(advanced, includeLlamaCppExtras = true))
        assertEquals(JsonPrimitive(0.3), llama["temperature"])
        assertEquals(JsonPrimitive(20), llama["top_k"])
        assertEquals(JsonPrimitive(0.05), llama["min_p"])
        assertEquals(JsonPrimitive(1.1), llama["repeat_penalty"])
        assertEquals(JsonPrimitive(512), llama["max_tokens"])

        val strict = encode(OpenAICompatibleChatRequestDto(messages = emptyList()).withSampling(advanced, includeLlamaCppExtras = false))
        assertEquals(JsonPrimitive(0.3), strict["temperature"])
        assertFalse(strict.containsKey("top_k"))
        assertFalse(strict.containsKey("min_p"))
        assertFalse(strict.containsKey("repeat_penalty"))
    }

    @Test
    fun `gemini generation config only exists when sampling is set`() {
        assertNull(InstanceAdvancedSettings(requestTimeoutSec = 10).toGeminiGenerationConfig())
        val config = InstanceAdvancedSettings(temperature = 0.5, maxTokens = 100).toGeminiGenerationConfig()!!
        assertEquals(0.5, config.temperature)
        assertEquals(100, config.maxOutputTokens)
    }

    // endregion

    // region llama.cpp props

    @Test
    fun `llama cpp root strips v1`() {
        assertEquals("http://host:8080", llamaCppRootUrl("http://host:8080/v1/"))
        assertEquals("http://host:8080", llamaCppRootUrl("http://host:8080"))
    }

    @Test
    fun `parses llama cpp props`() {
        val props = parseLlamaCppProps("""{"default_generation_settings":{"n_ctx":32768},"modalities":{"vision":false,"audio":true}}""")!!
        assertEquals(32768, props.contextTokens)
        assertEquals(true, props.supportsAudio)
        assertEquals(false, props.supportsVision)
        assertNull(parseLlamaCppProps("""{"object":"list"}"""))
        assertNull(parseLlamaCppProps("<html>"))
    }

    // endregion

    // region retry policy

    @Test
    fun `retries up to the configured count with configured delay`() = runTest {
        var calls = 0
        val sleeps = mutableListOf<Long>()
        assertFailsWith<OpenAICompatibleGenericException> {
            retryWithPolicy(InstanceAdvancedSettings(maxRetries = 3, retryDelaySec = 2), sleep = { sleeps += it }) {
                calls++
                throw OpenAICompatibleGenericException("boom")
            }
        }
        assertEquals(4, calls)
        assertEquals(listOf(2000L, 2000L, 2000L), sleeps)
    }

    @Test
    fun `zero retries fails on first error`() = runTest {
        var calls = 0
        assertFailsWith<OpenAICompatibleGenericException> {
            retryWithPolicy(InstanceAdvancedSettings(maxRetries = 0), sleep = {}) {
                calls++
                throw OpenAICompatibleGenericException("boom")
            }
        }
        assertEquals(1, calls)
    }

    @Test
    fun `invalid key is never retried`() = runTest {
        var calls = 0
        assertFailsWith<OpenAICompatibleInvalidApiKeyException> {
            retryWithPolicy(InstanceAdvancedSettings(), sleep = {}) {
                calls++
                throw OpenAICompatibleInvalidApiKeyException()
            }
        }
        assertEquals(1, calls)
    }

    @Test
    fun `failover on timeout skips retries for connection errors only`() = runTest {
        var calls = 0
        val policy = InstanceAdvancedSettings(failoverOnTimeout = true)
        assertFailsWith<OpenAICompatibleConnectionException> {
            retryWithPolicy(policy, sleep = {}) {
                calls++
                throw OpenAICompatibleConnectionException()
            }
        }
        assertEquals(1, calls)

        calls = 0
        val result = retryWithPolicy(policy, sleep = {}) {
            calls++
            if (calls < 2) throw OpenAICompatibleGenericException("transient") else "ok"
        }
        assertEquals("ok", result)
        assertEquals(2, calls)
    }

    // endregion

    // region context budget

    private fun user(text: String) = History(role = History.Role.USER, content = text)
    private fun assistant(text: String) = History(role = History.Role.ASSISTANT, content = text)

    @Test
    fun `compaction is skipped below the threshold`() {
        val history = listOf(user("a".repeat(10)), assistant("b".repeat(10)))
        assertNull(compactionCutoffIndex(history, systemPromptChars = 0, maxChars = 1000, threshold = 0.7, keepRecent = 1))
    }

    @Test
    fun `compaction keeps the configured number of recent exchanges`() {
        val history = (1..6).flatMap { listOf(user("u$it".padEnd(100, '.')), assistant("a$it".padEnd(100, '.'))) }
        // 1200 chars total, budget 1000 at 50% -> compaction needed; keep 2 user turns
        val cutoff = compactionCutoffIndex(history, systemPromptChars = 0, maxChars = 1000, threshold = 0.5, keepRecent = 2)
        assertEquals(8, cutoff)
        assertTrue(history[cutoff!!].content.startsWith("u5"))
    }

    @Test
    fun `compaction needs more user turns than kept`() {
        val history = listOf(user("x".repeat(500)), assistant("y".repeat(500)))
        assertNull(compactionCutoffIndex(history, systemPromptChars = 0, maxChars = 100, threshold = 0.5, keepRecent = 4))
    }

    @Test
    fun `history trimming drops oldest and reserves the system prompt`() {
        val history = listOf(user("1".repeat(40)), assistant("2".repeat(40)), user("3".repeat(40)))
        val kept = trimHistoryToBudget(history, systemPromptChars = 20, maxChars = 100)
        assertEquals(listOf("3".repeat(40), "2".repeat(40)).reversed(), kept.map { it.content })
    }

    @Test
    fun `message trimming keeps system and tool groups intact`() {
        fun msg(role: String, text: String, toolCalls: Boolean = false) = OpenAICompatibleChatRequestDto.Message(
            role = role,
            content = JsonPrimitive(text),
            tool_calls = if (toolCalls) listOf(OpenAICompatibleChatRequestDto.ToolCall("1", function = OpenAICompatibleChatRequestDto.FunctionCall("f", "{}"))) else null,
            tool_call_id = if (role == "tool") "1" else null,
        )
        val messages = listOf(
            msg("system", "s".repeat(10)),
            msg("user", "old".padEnd(200, '.')),
            msg("assistant", "call", toolCalls = true),
            msg("tool", "r".repeat(50)),
            msg("user", "new".padEnd(30, '.')),
        )
        val trimmed = trimMessagesToBudget(messages, maxChars = 150)
        assertEquals(listOf("system", "assistant", "tool", "user"), trimmed.map { it.role })
    }

    // endregion
}
