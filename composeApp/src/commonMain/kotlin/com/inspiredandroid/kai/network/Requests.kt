@file:OptIn(ExperimentalUuidApi::class)

package com.inspiredandroid.kai.network

import com.inspiredandroid.kai.Version
import com.inspiredandroid.kai.currentPlatform
import com.inspiredandroid.kai.data.InstanceAdvancedSettings
import com.inspiredandroid.kai.data.Service
import com.inspiredandroid.kai.data.TtsVoice
import com.inspiredandroid.kai.httpClient
import com.inspiredandroid.kai.isDebugBuild
import com.inspiredandroid.kai.network.dtos.anthropic.AnthropicChatRequestDto
import com.inspiredandroid.kai.network.dtos.anthropic.AnthropicChatResponseDto
import com.inspiredandroid.kai.network.dtos.anthropic.AnthropicModelsResponseDto
import com.inspiredandroid.kai.network.dtos.gemini.FunctionDeclaration
import com.inspiredandroid.kai.network.dtos.gemini.FunctionParameters
import com.inspiredandroid.kai.network.dtos.gemini.GeminiChatRequestDto
import com.inspiredandroid.kai.network.dtos.gemini.GeminiChatResponseDto
import com.inspiredandroid.kai.network.dtos.gemini.GeminiModelsResponseDto
import com.inspiredandroid.kai.network.dtos.gemini.GeminiTool
import com.inspiredandroid.kai.network.dtos.gemini.PropertySchema
import com.inspiredandroid.kai.network.dtos.openaicompatible.OpenAICompatibleChatRequestDto
import com.inspiredandroid.kai.network.dtos.openaicompatible.OpenAICompatibleChatResponseDto
import com.inspiredandroid.kai.network.dtos.openaicompatible.OpenAICompatibleModelResponseDto
import com.inspiredandroid.kai.network.dtos.openairesponses.OpenAIResponsesRequestDto
import com.inspiredandroid.kai.network.dtos.openairesponses.OpenAIResponsesResponseDto
import com.inspiredandroid.kai.network.tools.Tool
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.EMPTY
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class ServiceCredentials(
    val apiKey: String = "",
    val modelId: String = "",
    val baseUrl: String = "",
    val advanced: InstanceAdvancedSettings = InstanceAdvancedSettings(),
)

/**
 * Applies per-request timeouts. An explicit [requestTimeoutMs] from the caller wins for the
 * request/socket timeout; otherwise the instance's advanced settings are used. Anything left unset
 * keeps the client-wide default.
 */
private fun HttpRequestBuilder.applyTimeout(requestTimeoutMs: Long?, advanced: InstanceAdvancedSettings = InstanceAdvancedSettings()) {
    val requestMs = requestTimeoutMs ?: advanced.requestTimeoutSec?.takeIf { it > 0 }?.let { it * 1000L }
    val socketMs = requestTimeoutMs ?: advanced.socketTimeoutSec?.takeIf { it > 0 }?.let { it * 1000L }
    val connectMs = advanced.connectTimeoutSec?.takeIf { it > 0 }?.let { it * 1000L }
    if (requestMs == null && socketMs == null && connectMs == null) return
    timeout {
        requestMs?.let { requestTimeoutMillis = it }
        socketMs?.let { socketTimeoutMillis = it }
        connectMs?.let { connectTimeoutMillis = it }
    }
}

/**
 * Session id for provider requests that belong to no conversation — the model list, and the
 * silent asks that run before a chat exists. Stable for the lifetime of the process.
 */
private val processSessionId: String by lazy { Uuid.random().toString() }

/**
 * OpenCode Zen identifies the calling client by an `x-opencode-session` header and rejects
 * requests that omit it. One id per conversation, so a whole chat reads as a single session
 * upstream; [sessionId] is the conversation id, or null for requests outside any conversation.
 * No other provider is sent a session header.
 */
internal fun sessionHeadersFor(service: Service, sessionId: String?): Map<String, String> = if (service == Service.OpenCode) {
    mapOf("x-opencode-session" to (sessionId?.takeIf { it.isNotBlank() } ?: processSessionId))
} else {
    emptyMap()
}

private fun HttpRequestBuilder.applySessionHeader(service: Service, sessionId: String?) {
    sessionHeadersFor(service, sessionId).forEach { (k, v) -> header(k, v) }
}

/**
 * Converts declared tools into a provider's request shape, dropping any whose schema can't be
 * represented rather than failing the whole request, and returning null for an empty list so the
 * field is omitted from the payload entirely.
 */
private fun <T> List<Tool>.toRequestTools(convert: (Tool) -> T): List<T>? = mapNotNull { runCatching { convert(it) }.getOrNull() }.ifEmpty { null }

/**
 * Shared failure mapping for the OpenAI-compatible endpoints that have no bespoke error handling:
 * API exceptions propagate as-is, anything else (I/O, serialization) becomes a connection error.
 * Kept separate from [Requests.openAICompatibleChat], which additionally distinguishes timeouts.
 */
private inline fun <T> openAICompatibleResult(block: () -> Result<T>): Result<T> = try {
    block()
} catch (e: OpenAICompatibleApiException) {
    Result.failure(e)
} catch (e: Exception) {
    Result.failure(OpenAICompatibleConnectionException())
}

/** Text of an `/audio/transcriptions` JSON response (`{"text": ...}`), or the raw body for text formats. */
internal fun parseTranscriptionText(body: String): String = try {
    Json.parseToJsonElement(body).jsonObject["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
} catch (_: Exception) {
    body.trim()
}

/**
 * Voices from a `GET /audio/voices` response. Servers disagree on the shape: `{"voices":[…]}`
 * (KoboldCpp, Kokoro-FastAPI), `{"data":[…]}` or a bare array, with plain names or objects carrying
 * `id` / `voice_id` / `name` and optionally a `description`.
 */
internal fun parseVoiceList(body: String): List<TtsVoice> {
    val root = try {
        Json.parseToJsonElement(body)
    } catch (_: Exception) {
        return emptyList()
    }
    val items = when (root) {
        is JsonArray -> root
        is JsonObject -> (root["voices"] ?: root["data"]) as? JsonArray ?: return emptyList()
        else -> return emptyList()
    }
    return items.mapNotNull { item ->
        when (item) {
            is JsonPrimitive -> item.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { TtsVoice(it) }

            is JsonObject -> listOf("id", "voice_id", "name")
                .firstNotNullOfOrNull { (item[it] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { id -> id.isNotEmpty() } }
                ?.let { id -> TtsVoice(id, (item["description"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }) }

            else -> null
        }
    }.distinctBy { it.id }
}

/**
 * Text for `/audio/speech` with an optional voice description in front, the "[description] text"
 * form KoboldCpp reads as a Qwen3-TTS VoiceDesign instruction. It travels inside `input`, so proxies
 * such as LiteLLM pass it through unchanged. Brackets in the description would end it early.
 */
internal fun speechInput(text: String, voiceDescription: String?): String {
    val description = voiceDescription?.replace('[', '(')?.replace(']', ')')?.trim()?.takeIf { it.isNotEmpty() }
    return if (description == null) text else "[$description] $text"
}

/** JSON body of an OpenAI `/audio/speech` request. */
internal fun speechRequestJson(model: String, input: String, voice: String, speed: Double?, format: String): String = JsonObject(
    buildMap {
        put("model", JsonPrimitive(model))
        put("input", JsonPrimitive(input))
        put("voice", JsonPrimitive(voice))
        put("response_format", JsonPrimitive(format))
        speed?.let { put("speed", JsonPrimitive(it)) }
    },
).toString()

data class LlamaCppProps(
    val contextTokens: Int?,
    val supportsAudio: Boolean?,
    val supportsVision: Boolean?,
)

/** Strips a trailing `/v1` (and slashes) from an OpenAI-compatible base URL to get the llama.cpp server root. */
internal fun llamaCppRootUrl(baseUrl: String): String = baseUrl.trim().trimEnd('/').removeSuffix("/v1").trimEnd('/')

/** Parses a llama.cpp `/props` body; null when it carries neither a context size nor modalities. */
internal fun parseLlamaCppProps(body: String): LlamaCppProps? = try {
    val root = Json.parseToJsonElement(body).jsonObject
    val nCtx = (root["default_generation_settings"] as? JsonObject)?.get("n_ctx")?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        ?: root["n_ctx"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
    val modalities = root["modalities"] as? JsonObject
    val audio = modalities?.get("audio")?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
    val vision = modalities?.get("vision")?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
    if (nCtx == null && audio == null && vision == null) null else LlamaCppProps(nCtx?.takeIf { it > 0 }, audio, vision)
} catch (_: Exception) {
    null
}

/** Copies the instance's sampling settings onto the request; llama.cpp-only fields are opt-in. */
internal fun OpenAICompatibleChatRequestDto.withSampling(
    advanced: InstanceAdvancedSettings,
    includeLlamaCppExtras: Boolean,
): OpenAICompatibleChatRequestDto = copy(
    temperature = advanced.temperature,
    top_p = advanced.topP,
    max_tokens = advanced.maxTokens,
    presence_penalty = advanced.presencePenalty,
    frequency_penalty = advanced.frequencyPenalty,
    seed = advanced.seed,
    stop = advanced.stop?.ifEmpty { null },
    top_k = if (includeLlamaCppExtras) advanced.topK else null,
    min_p = if (includeLlamaCppExtras) advanced.minP else null,
    repeat_penalty = if (includeLlamaCppExtras) advanced.repeatPenalty else null,
)

internal fun InstanceAdvancedSettings.toGeminiGenerationConfig(): GeminiChatRequestDto.GenerationConfig? {
    if (!hasSampling) return null
    return GeminiChatRequestDto.GenerationConfig(
        temperature = temperature,
        topP = topP,
        topK = topK,
        maxOutputTokens = maxTokens,
        stopSequences = stop?.ifEmpty { null },
        seed = seed,
        presencePenalty = presencePenalty,
        frequencyPenalty = frequencyPenalty,
    )
}

class Requests {

    private val defaultClient = httpClient {
        install(ContentNegotiation) {
            json(
                Json {
                    prettyPrint = true
                    isLenient = true
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                    explicitNulls = false
                },
            )
        }
        install(UserAgent) {
            agent = "Kai/${Version.appVersion} (${currentPlatform.displayName})"
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 180.seconds.inWholeMilliseconds
            socketTimeoutMillis = 180.seconds.inWholeMilliseconds
        }
        install(Logging) {
            if (isDebugBuild) {
                logger = DebugKtorLogger()
                level = LogLevel.BODY
            } else {
                logger = Logger.EMPTY
                level = LogLevel.NONE
            }
        }
    }

    class DebugKtorLogger : Logger {
        override fun log(message: String) {
            println("[KTOR] $message")
        }
    }

    // region Gemini

    suspend fun getGeminiModels(credentials: ServiceCredentials): Result<GeminiModelsResponseDto> = try {
        val apiKey = credentials.apiKey.ifEmpty { throw GeminiInvalidApiKeyException() }
        val response: HttpResponse =
            defaultClient.get("https://generativelanguage.googleapis.com/v1beta/models") {
                header("x-goog-api-key", apiKey)
            }
        if (response.status.isSuccess()) {
            Result.success(response.body())
        } else {
            when (response.status.value) {
                400, 403 -> throw GeminiInvalidApiKeyException()
                else -> throw GeminiGenericException("Failed to fetch models: ${response.status}")
            }
        }
    } catch (e: GeminiApiException) {
        Result.failure(e)
    } catch (e: Exception) {
        Result.failure(GeminiGenericException("Connection failed", e))
    }

    suspend fun geminiChat(
        credentials: ServiceCredentials,
        messages: List<GeminiChatRequestDto.Content>,
        tools: List<Tool> = emptyList(),
        systemInstruction: String? = null,
        requestTimeoutMs: Long? = null,
    ): Result<GeminiChatResponseDto> = try {
        val apiKey = credentials.apiKey.ifEmpty { throw GeminiInvalidApiKeyException() }
        val selectedModelId = credentials.modelId

        val systemContent = systemInstruction?.let {
            GeminiChatRequestDto.Content(
                parts = listOf(GeminiChatRequestDto.Part(text = it)),
            )
        }

        val response: HttpResponse =
            defaultClient.post("${Service.Gemini.chatUrl}$selectedModelId:generateContent") {
                header("x-goog-api-key", apiKey)
                applyTimeout(requestTimeoutMs, credentials.advanced)
                contentType(ContentType.Application.Json)
                setBody(
                    GeminiChatRequestDto(
                        contents = messages,
                        tools = tools.toRequestTools { it.toGeminiTool() },
                        systemInstruction = systemContent,
                        generationConfig = credentials.advanced.toGeminiGenerationConfig(),
                    ),
                )
            }
        if (response.status.isSuccess()) {
            Result.success(response.body())
        } else {
            when (response.status.value) {
                429 -> throw GeminiRateLimitExceededException()

                403 -> throw GeminiInvalidApiKeyException()

                else -> {
                    val responseBody = response.bodyAsText()
                    if (responseBody.contains("API_KEY_INVALID", ignoreCase = true)) {
                        throw GeminiInvalidApiKeyException()
                    } else {
                        throw GeminiGenericException("Chat request failed: ${response.status}")
                    }
                }
            }
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    // endregion

    // region OpenAI-compatible (unified)

    suspend fun openAICompatibleChat(
        service: Service,
        credentials: ServiceCredentials,
        messages: List<OpenAICompatibleChatRequestDto.Message>,
        tools: List<Tool> = emptyList(),
        customHeaders: Map<String, String> = emptyMap(),
        sessionId: String? = null,
        requestTimeoutMs: Long? = null,
    ): Result<OpenAICompatibleChatResponseDto> = try {
        val apiKey = getApiKeyOrThrow(service, credentials)
        val model = credentials.modelId.ifEmpty { null }
        val url = resolveUrl(service, credentials, service.chatUrl)
        val response: HttpResponse =
            defaultClient.post(url) {
                applyTimeout(requestTimeoutMs, credentials.advanced)
                contentType(ContentType.Application.Json)
                apiKey?.let { bearerAuth(it) }
                applySessionHeader(service, sessionId)
                customHeaders.forEach { (k, v) -> header(k, v) }
                setBody(
                    OpenAICompatibleChatRequestDto(
                        messages = messages,
                        model = model,
                        tools = tools.toRequestTools { it.toRequestTool() },
                    ).withSampling(credentials.advanced, includeLlamaCppExtras = service == Service.OpenAICompatible),
                )
            }
        if (response.status.isSuccess()) {
            Result.success(response.body())
        } else {
            handleOpenAICompatibleError(service, credentials, response)
        }
    } catch (e: OpenAICompatibleApiException) {
        Result.failure(e)
    } catch (e: io.ktor.client.plugins.HttpRequestTimeoutException) {
        Result.failure(OpenAICompatibleConnectionException())
    } catch (e: Exception) {
        Result.failure(mapOpenAICompatibleException(e))
    }

    /**
     * OpenAI Responses API (`POST /v1/responses`). Used for the model families whose function
     * calling chat completions rejects — see `requiresResponsesApi`. Auth, URL resolution and
     * error mapping are shared with [openAICompatibleChat]; only the body and result shape differ.
     */
    suspend fun openAIResponses(
        service: Service,
        credentials: ServiceCredentials,
        input: List<JsonObject>,
        tools: List<Tool> = emptyList(),
        requestTimeoutMs: Long? = null,
    ): Result<OpenAIResponsesResponseDto> = try {
        val apiKey = getApiKeyOrThrow(service, credentials)
        val responsesUrl = service.responsesUrl
            ?: throw OpenAICompatibleGenericException("Responses URL not configured for ${service.displayName}")
        val url = resolveUrl(service, credentials, responsesUrl)
        val response: HttpResponse =
            defaultClient.post(url) {
                applyTimeout(requestTimeoutMs, credentials.advanced)
                contentType(ContentType.Application.Json)
                apiKey?.let { bearerAuth(it) }
                setBody(
                    OpenAIResponsesRequestDto(
                        input = input,
                        model = credentials.modelId.ifEmpty { null },
                        tools = tools.toRequestTools { it.toResponsesTool() },
                        temperature = credentials.advanced.temperature,
                        top_p = credentials.advanced.topP,
                        max_output_tokens = credentials.advanced.maxTokens,
                    ),
                )
            }
        if (response.status.isSuccess()) {
            Result.success(response.body())
        } else {
            handleOpenAICompatibleError(service, credentials, response)
        }
    } catch (e: OpenAICompatibleApiException) {
        Result.failure(e)
    } catch (e: io.ktor.client.plugins.HttpRequestTimeoutException) {
        Result.failure(OpenAICompatibleConnectionException())
    } catch (e: Exception) {
        Result.failure(mapOpenAICompatibleException(e))
    }

    suspend fun getOpenAICompatibleModels(
        service: Service,
        credentials: ServiceCredentials,
    ): Result<OpenAICompatibleModelResponseDto> = openAICompatibleResult {
        val modelsUrl = service.modelsUrl
            ?: return Result.failure(OpenAICompatibleGenericException("Models URL not configured for ${service.displayName}"))
        val url = resolveUrl(service, credentials, modelsUrl)
        val apiKey = getOptionalApiKey(service, credentials)
        val response: HttpResponse = defaultClient.get(url) {
            applyTimeout(null, credentials.advanced)
            apiKey?.let { bearerAuth(it) }
            applySessionHeader(service, sessionId = null)
        }
        if (response.status.isSuccess()) {
            if (service.modelsResponseIsArray) {
                val models: List<OpenAICompatibleModelResponseDto.Model> = response.body()
                Result.success(OpenAICompatibleModelResponseDto(data = models))
            } else {
                Result.success(response.body())
            }
        } else {
            handleOpenAICompatibleError(service, credentials, response)
        }
    }

    /**
     * Best-effort read of a llama.cpp server's `/props` (context size and input modalities) for the
     * generic OpenAI-compatible service. Tries the server root first, then llama-swap's
     * `/upstream/<model>/props` passthrough. Returns null when neither answers with usable data.
     */
    suspend fun getLlamaCppProps(credentials: ServiceCredentials): LlamaCppProps? {
        val root = llamaCppRootUrl(credentials.baseUrl.ifEmpty { Service.DEFAULT_OPENAI_COMPATIBLE_BASE_URL })
        val model = credentials.modelId.trim()
        val candidates = buildList {
            if (model.isNotEmpty()) add("$root/upstream/${model.encodeURLPathPart()}/props")
            add(if (model.isNotEmpty()) "$root/props?model=${model.encodeURLParameter()}" else "$root/props")
        }
        for (url in candidates) {
            val props = try {
                val response = defaultClient.get(url) {
                    timeout { requestTimeoutMillis = 15_000 }
                    credentials.apiKey.ifEmpty { null }?.let { bearerAuth(it) }
                }
                if (response.status.isSuccess()) parseLlamaCppProps(response.bodyAsText()) else null
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                null
            }
            if (props != null) return props
        }
        return null
    }

    /**
     * OpenAI-style speech-to-text (`POST <base>/audio/transcriptions`, multipart) against the
     * instance's endpoint, e.g. a Whisper / Qwen3-ASR model behind llama-swap or LiteLLM.
     */
    suspend fun transcribeAudio(
        service: Service,
        credentials: ServiceCredentials,
        model: String,
        audio: ByteArray,
        fileName: String,
        mimeType: String,
        language: String? = null,
    ): Result<String> = openAICompatibleResult {
        val url = audioUrl(service, credentials, "/audio/transcriptions")
        val apiKey = credentials.apiKey.ifEmpty { null }
        val response: HttpResponse = defaultClient.submitFormWithBinaryData(
            url = url,
            formData = formData {
                append("model", model)
                append("response_format", "json")
                language?.trim()?.takeIf { it.isNotEmpty() }?.let { append("language", it) }
                append(
                    "file",
                    audio,
                    Headers.build {
                        append(HttpHeaders.ContentType, mimeType)
                        append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                    },
                )
            },
        ) {
            applyTimeout(null, credentials.advanced)
            apiKey?.let { bearerAuth(it) }
        }
        if (response.status.isSuccess()) {
            Result.success(parseTranscriptionText(response.bodyAsText()))
        } else {
            handleOpenAICompatibleError(service, credentials, response)
        }
    }

    /** OpenAI-style text-to-speech (`POST <base>/audio/speech`); returns the encoded audio (mp3 by default). */
    suspend fun synthesizeSpeech(
        service: Service,
        credentials: ServiceCredentials,
        model: String,
        input: String,
        voice: String,
        speed: Double? = null,
        format: String = "mp3",
    ): Result<ByteArray> = openAICompatibleResult {
        val url = audioUrl(service, credentials, "/audio/speech")
        val response: HttpResponse = defaultClient.post(url) {
            applyTimeout(null, credentials.advanced)
            credentials.apiKey.ifEmpty { null }?.let { bearerAuth(it) }
            // TextContent avoids ContentNegotiation re-encoding the prepared JSON.
            setBody(TextContent(speechRequestJson(model, input, voice, speed, format), ContentType.Application.Json))
        }
        if (response.status.isSuccess()) {
            Result.success(response.readRawBytes())
        } else {
            handleOpenAICompatibleError(service, credentials, response)
        }
    }

    /**
     * Voices the text-to-speech server offers (`GET /audio/voices`, not part of the OpenAI API but
     * common on self-hosted servers). [model] is sent as a query parameter so llama-swap can route it.
     */
    suspend fun listSpeechVoices(service: Service, credentials: ServiceCredentials, model: String): Result<List<TtsVoice>> = openAICompatibleResult {
        val url = audioUrl(service, credentials, "/audio/voices") + "?model=" + model.encodeURLParameter()
        val response: HttpResponse = defaultClient.get(url) {
            applyTimeout(null, credentials.advanced)
            credentials.apiKey.ifEmpty { null }?.let { bearerAuth(it) }
        }
        if (response.status.isSuccess()) {
            Result.success(parseVoiceList(response.bodyAsText()))
        } else {
            handleOpenAICompatibleError(service, credentials, response)
        }
    }

    /** Audio endpoints live next to chat completions; hosted providers use their fixed base. */
    private fun audioUrl(service: Service, credentials: ServiceCredentials, path: String): String = if (service == Service.OpenAICompatible) {
        resolveUrl(service, credentials, path)
    } else {
        service.chatUrl.substringBeforeLast("/chat/completions") + path
    }

    suspend fun validateOpenRouterApiKey(credentials: ServiceCredentials): Result<Unit> = openAICompatibleResult {
        val apiKey = credentials.apiKey.ifEmpty { throw OpenAICompatibleInvalidApiKeyException() }
        val response: HttpResponse = defaultClient.get("https://openrouter.ai/api/v1/auth/key") {
            bearerAuth(apiKey)
        }
        if (response.status.isSuccess()) {
            Result.success(Unit)
        } else {
            when (response.status.value) {
                401, 403 -> throw OpenAICompatibleInvalidApiKeyException()
                else -> throw OpenAICompatibleGenericException("Failed to validate OpenRouter API key: ${response.status}")
            }
        }
    }

    /**
     * Perplexity Sonar has no authenticated models endpoint, so key checks go through the
     * chat completions URL with an intentionally incomplete body. Auth is evaluated first:
     * invalid keys return 401/403; a valid key typically yields 400/422 on the empty messages
     * array — which we treat as connected without spending tokens on a real completion.
     */
    suspend fun validatePerplexityApiKey(credentials: ServiceCredentials): Result<Unit> = openAICompatibleResult {
        val apiKey = credentials.apiKey.ifEmpty { throw OpenAICompatibleInvalidApiKeyException() }
        val response: HttpResponse = defaultClient.post(Service.Perplexity.chatUrl) {
            bearerAuth(apiKey)
            // TextContent avoids ContentNegotiation re-encoding a raw String as a JSON string.
            setBody(TextContent("""{"model":"sonar","messages":[]}""", ContentType.Application.Json))
        }
        when (response.status.value) {
            401, 403 -> throw OpenAICompatibleInvalidApiKeyException()
            in 200..499 -> Result.success(Unit)
            else -> throw OpenAICompatibleGenericException("Failed to validate Perplexity API key: ${response.status}")
        }
    }

    // endregion

    private val anthropicJson = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    // region Anthropic

    suspend fun getAnthropicModels(credentials: ServiceCredentials): Result<AnthropicModelsResponseDto> = try {
        val apiKey = credentials.apiKey.ifEmpty { throw AnthropicInvalidApiKeyException() }
        val response: HttpResponse =
            defaultClient.get("https://api.anthropic.com/v1/models") {
                header("x-api-key", apiKey)
                header("anthropic-version", "2023-06-01")
            }
        val responseBody = response.bodyAsText()
        if (response.status.isSuccess()) {
            val dto = anthropicJson.decodeFromString(AnthropicModelsResponseDto.serializer(), responseBody)
            Result.success(dto)
        } else {
            throwAnthropicError(response.status.value, responseBody)
        }
    } catch (e: AnthropicApiException) {
        Result.failure(e)
    } catch (e: Exception) {
        Result.failure(AnthropicGenericException("Anthropic: ${e.message}", e))
    }

    suspend fun anthropicChat(
        credentials: ServiceCredentials,
        messages: List<AnthropicChatRequestDto.Message>,
        tools: List<Tool> = emptyList(),
        systemInstruction: String? = null,
        requestTimeoutMs: Long? = null,
    ): Result<AnthropicChatResponseDto> = try {
        val apiKey = credentials.apiKey.ifEmpty { throw AnthropicInvalidApiKeyException() }
        val response: HttpResponse =
            defaultClient.post(Service.Anthropic.chatUrl) {
                applyTimeout(requestTimeoutMs, credentials.advanced)
                contentType(ContentType.Application.Json)
                header("x-api-key", apiKey)
                header("anthropic-version", "2023-06-01")
                setBody(
                    AnthropicChatRequestDto(
                        model = credentials.modelId,
                        messages = messages,
                        max_tokens = credentials.advanced.maxTokens ?: InstanceAdvancedSettings.DEFAULT_ANTHROPIC_MAX_TOKENS,
                        system = systemInstruction,
                        tools = tools.toRequestTools { it.toAnthropicTool() },
                        temperature = credentials.advanced.temperature,
                        top_p = credentials.advanced.topP,
                        top_k = credentials.advanced.topK,
                        stop_sequences = credentials.advanced.stop?.ifEmpty { null },
                    ),
                )
            }
        val responseBody = response.bodyAsText()
        if (response.status.isSuccess()) {
            val dto = anthropicJson.decodeFromString(AnthropicChatResponseDto.serializer(), responseBody)
            Result.success(dto)
        } else {
            throwAnthropicError(response.status.value, responseBody)
        }
    } catch (e: AnthropicApiException) {
        Result.failure(e)
    } catch (e: Exception) {
        Result.failure(AnthropicGenericException("Anthropic: ${e.message}", e))
    }

    private fun throwAnthropicError(statusCode: Int, responseBody: String): Nothing {
        when (statusCode) {
            401, 403 -> throw AnthropicInvalidApiKeyException()
            429 -> throw AnthropicRateLimitExceededException()
            529 -> throw AnthropicOverloadedException()
        }
        val errorMessage = parseAnthropicErrorMessage(responseBody)
        if (errorMessage != null && errorMessage.contains("credit balance", ignoreCase = true)) {
            throw AnthropicInsufficientCreditsException()
        }
        throw AnthropicGenericException(errorMessage ?: "Anthropic: $statusCode $responseBody")
    }

    private fun parseAnthropicErrorMessage(responseBody: String): String? = try {
        val json = anthropicJson.parseToJsonElement(responseBody)
        val errorObj = json.jsonObject["error"]?.jsonObject
        errorObj?.get("message")?.jsonPrimitive?.content
    } catch (_: Exception) {
        null
    }

    // endregion

    // region Helpers

    private fun resolveUrl(service: Service, credentials: ServiceCredentials, path: String): String = if (service == Service.OpenAICompatible) {
        "${credentials.baseUrl.ifEmpty { Service.DEFAULT_OPENAI_COMPATIBLE_BASE_URL }.trimEnd('/')}$path"
    } else {
        path
    }

    private fun getApiKeyOrThrow(service: Service, credentials: ServiceCredentials): String? {
        if (!service.requiresApiKey && !service.supportsOptionalApiKey) return null
        val key = credentials.apiKey
        if (service.requiresApiKey && key.isEmpty()) throw OpenAICompatibleInvalidApiKeyException()
        return key.ifEmpty { null }
    }

    private fun getOptionalApiKey(service: Service, credentials: ServiceCredentials): String? {
        if (!service.requiresApiKey && !service.supportsOptionalApiKey) return null
        return credentials.apiKey.ifEmpty { null }
    }

    private suspend fun handleOpenAICompatibleError(
        service: Service,
        credentials: ServiceCredentials,
        response: HttpResponse,
    ): Nothing {
        val responseBody = response.bodyAsText()
        val parsed = parseOpenAICompatibleErrorDetail(responseBody)
        val moderationDetail = parsed.moderationDetail()
        when (response.status.value) {
            400 -> {
                if (parsed.looksLikeContentPolicyViolation()) {
                    throw OpenAICompatibleContentModerationException(moderationDetail)
                }
                throw OpenAICompatibleBadRequestException(parsed.message)
            }

            401 -> throw OpenAICompatibleInvalidApiKeyException()

            402 -> throw OpenAICompatibleQuotaExhaustedException()

            403 -> throw OpenAICompatibleContentModerationException(moderationDetail)

            404 -> throw OpenAICompatibleModelNotFoundException()

            408, 504 -> throw OpenAICompatibleTimeoutException()

            413 -> throw OpenAICompatibleRequestTooLargeException()

            429 -> throw OpenAICompatibleRateLimitExceededException()

            500, 502 -> {
                // llama.cpp answers input_audio for a model without an audio projector with a 500.
                if (parsed.message.orEmpty().contains("audio input is not supported", ignoreCase = true)) {
                    throw AudioInputNotSupportedException()
                }
                throw OpenAICompatibleProviderErrorException(parsed.message)
            }

            503 -> throw OpenAICompatibleServiceUnavailableException()

            else -> {
                val haystack = parsed.message ?: responseBody
                if (haystack.contains("credit", ignoreCase = true) ||
                    haystack.contains("exhausted", ignoreCase = true) ||
                    haystack.contains("spending limit", ignoreCase = true) ||
                    haystack.contains("quota", ignoreCase = true) ||
                    haystack.contains("subscription", ignoreCase = true) ||
                    haystack.contains("upgrade", ignoreCase = true)
                ) {
                    throw OpenAICompatibleQuotaExhaustedException()
                }
                val detail = parsed.message ?: "${response.status}"
                throw OpenAICompatibleGenericException("${service.displayName}: $detail")
            }
        }
    }

    // Distinguish genuine network/I/O failures (preserve the "Cannot connect to
    // server" UX and the settings-screen ErrorConnectionFailed status) from
    // client-side exceptions (schema/serialization bugs, etc.) which would
    // otherwise be silently misclassified as connection failures.
    private fun mapOpenAICompatibleException(e: Exception): OpenAICompatibleApiException {
        val name = e::class.simpleName.orEmpty()
        val looksLikeNetworkFailure = name.endsWith("IOException") ||
            name.contains("Timeout", ignoreCase = true) ||
            name.contains("ConnectException", ignoreCase = true) ||
            name.contains("UnknownHost", ignoreCase = true) ||
            name.contains("NoRoute", ignoreCase = true) ||
            name.contains("SocketException", ignoreCase = true) ||
            name.contains("Unresolved", ignoreCase = true)
        return if (looksLikeNetworkFailure) {
            OpenAICompatibleConnectionException()
        } else {
            OpenAICompatibleGenericException(
                e.message?.takeIf { it.isNotBlank() } ?: "Unexpected error: $name",
                e,
            )
        }
    }

    private data class OpenAICompatibleErrorDetail(
        val message: String?,
        val code: String?,
        val type: String?,
        val reasons: List<String>,
    ) {
        fun looksLikeContentPolicyViolation(): Boolean {
            if (code?.equals("content_policy_violation", ignoreCase = true) == true) return true
            if (type?.contains("content_filter", ignoreCase = true) == true) return true
            if (type?.contains("content_policy", ignoreCase = true) == true) return true
            val msg = message ?: return false
            return msg.contains("content policy", ignoreCase = true) ||
                msg.contains("moderation", ignoreCase = true) ||
                msg.contains("flagged", ignoreCase = true)
        }

        fun moderationDetail(): String? {
            val reasonText = reasons.takeIf { it.isNotEmpty() }
                ?.joinToString(", ")
                ?.let { "flagged for '$it'" }
            return reasonText ?: message
        }
    }

    private fun parseOpenAICompatibleErrorDetail(responseBody: String): OpenAICompatibleErrorDetail = try {
        val error = anthropicJson.parseToJsonElement(responseBody).jsonObject["error"]
        when {
            error == null -> OpenAICompatibleErrorDetail(null, null, null, emptyList())

            error is JsonPrimitive -> OpenAICompatibleErrorDetail(
                message = error.content.takeIf { it.isNotBlank() },
                code = null,
                type = null,
                reasons = emptyList(),
            )

            else -> {
                val obj = error.jsonObject
                val message = obj["message"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                val code = (obj["code"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                val type = (obj["type"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
                val reasons = (obj["metadata"] as? JsonObject)?.get("reasons")?.let { it as? JsonArray }
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
                    .orEmpty()
                OpenAICompatibleErrorDetail(message, code, type, reasons)
            }
        }
    } catch (_: Exception) {
        OpenAICompatibleErrorDetail(null, null, null, emptyList())
    }

    private fun Tool.toRequestTool(): OpenAICompatibleChatRequestDto.Tool = OpenAICompatibleChatRequestDto.Tool(
        function = OpenAICompatibleChatRequestDto.Function(
            name = schema.name,
            description = schema.description,
            parameters = OpenAICompatibleChatRequestDto.Parameters(
                properties = propertySchemas(OpenAISchemaDialect),
                required = requiredParameterNames(),
            ),
        ),
    )

    /** Same schema as [toRequestTool], flattened — the Responses API tags function tools inline. */
    private fun Tool.toResponsesTool(): OpenAIResponsesRequestDto.Tool = OpenAIResponsesRequestDto.Tool(
        name = schema.name,
        description = schema.description,
        parameters = OpenAICompatibleChatRequestDto.Parameters(
            properties = propertySchemas(OpenAISchemaDialect),
            required = requiredParameterNames(),
        ),
    )

    private fun Tool.toAnthropicTool(): AnthropicChatRequestDto.Tool = AnthropicChatRequestDto.Tool(
        name = schema.name,
        description = schema.description,
        input_schema = AnthropicChatRequestDto.InputSchema(
            properties = propertySchemas(AnthropicSchemaDialect),
            required = requiredParameterNames(),
        ),
    )

    private fun Tool.toGeminiTool(): GeminiTool = GeminiTool(
        functionDeclarations = listOf(
            FunctionDeclaration(
                name = schema.name,
                description = schema.description,
                parameters = FunctionParameters(
                    properties = propertySchemas(GeminiSchemaDialect),
                    required = requiredParameterNames(),
                ),
            ),
        ),
    )

    // endregion
}

/**
 * Builds one provider's property-schema node. The three chat APIs accept the same JSON Schema
 * shape under three unrelated DTOs, so the traversal in [toPropertySchema] is written once and
 * the dialect only decides which type each node becomes. Fields a provider doesn't model (e.g.
 * Anthropic and Gemini have no `additionalProperties`) are dropped by its implementation.
 */
private interface SchemaDialect<T> {
    /** Element type used when an `array` node declares no `items`. */
    val defaultStringItems: T

    fun property(
        type: String,
        description: String? = null,
        enum: List<String>? = null,
        items: T? = null,
        properties: Map<String, T>? = null,
        required: List<String>? = null,
        additionalProperties: Boolean? = null,
    ): T
}

private object OpenAISchemaDialect : SchemaDialect<OpenAICompatibleChatRequestDto.PropertySchema> {
    override val defaultStringItems = OpenAICompatibleChatRequestDto.PropertySchema(type = "string")

    override fun property(
        type: String,
        description: String?,
        enum: List<String>?,
        items: OpenAICompatibleChatRequestDto.PropertySchema?,
        properties: Map<String, OpenAICompatibleChatRequestDto.PropertySchema>?,
        required: List<String>?,
        additionalProperties: Boolean?,
    ) = OpenAICompatibleChatRequestDto.PropertySchema(
        type = type,
        description = description,
        enum = enum,
        items = items,
        properties = properties,
        required = required,
        additionalProperties = additionalProperties,
    )
}

private object AnthropicSchemaDialect : SchemaDialect<AnthropicChatRequestDto.PropertySchema> {
    override val defaultStringItems = AnthropicChatRequestDto.PropertySchema(type = "string")

    override fun property(
        type: String,
        description: String?,
        enum: List<String>?,
        items: AnthropicChatRequestDto.PropertySchema?,
        properties: Map<String, AnthropicChatRequestDto.PropertySchema>?,
        required: List<String>?,
        additionalProperties: Boolean?,
    ) = AnthropicChatRequestDto.PropertySchema(
        type = type,
        description = description,
        enum = enum,
        items = items,
        properties = properties,
        required = required,
    )
}

private object GeminiSchemaDialect : SchemaDialect<PropertySchema> {
    override val defaultStringItems = PropertySchema(type = "string")

    override fun property(
        type: String,
        description: String?,
        enum: List<String>?,
        items: PropertySchema?,
        properties: Map<String, PropertySchema>?,
        required: List<String>?,
        additionalProperties: Boolean?,
    ) = PropertySchema(
        type = type,
        description = description,
        enum = enum,
        items = items,
        properties = properties,
        required = required,
    )
}

/** Declared parameters of this tool, converted to [dialect]'s node type. */
private fun <T> Tool.propertySchemas(dialect: SchemaDialect<T>): Map<String, T> = schema.parameters.mapValues { (_, param) ->
    param.rawSchema?.toPropertySchema(dialect)
        ?: dialect.property(type = param.type, description = param.description)
}

private fun Tool.requiredParameterNames(): List<String> = schema.parameters.filter { it.value.required }.keys.toList()

// JSON Schema dialects vary: `type` may be a string or an array (e.g.
// ["string","null"]); enum/required entries may be non-string primitives;
// nested objects may be malformed. MCP tool servers (especially proxies like
// litellm /toolset/<category>/mcp) emit any of these shapes, so the converter
// falls back to safe defaults instead of throwing — a throw here would bubble
// to setBody() and surface as a misleading "Cannot connect to server" error.

private fun <T> JsonObject.toPropertySchema(dialect: SchemaDialect<T>): T {
    val type = schemaType()
    val items = (this["items"] as? JsonObject)?.toPropertySchema(dialect)
    val properties = schemaObjectMap("properties")?.mapValues { it.value.toPropertySchema(dialect) }
    val additionalProperties = (this["additionalProperties"] as? JsonPrimitive)
        ?.contentOrNull?.toBooleanStrictOrNull()
    return dialect.property(
        type = type,
        description = schemaString("description"),
        enum = schemaStringList("enum"),
        items = items ?: if (type == "array") dialect.defaultStringItems else null,
        properties = properties,
        required = schemaStringList("required"),
        additionalProperties = additionalProperties,
    )
}

private fun JsonObject.schemaType(): String = when (val t = this["type"]) {
    is JsonPrimitive -> t.contentOrNull ?: "string"

    is JsonArray -> t.asSequence()
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        .firstOrNull { it != "null" }
        ?: "string"

    else -> "string"
}

private fun JsonObject.schemaString(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.schemaStringList(key: String): List<String>? = (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    ?.takeIf { it.isNotEmpty() }

private fun JsonObject.schemaObjectMap(key: String): Map<String, JsonObject>? = (this[key] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonObject)?.let { k to it } }
    ?.toMap()
    ?.takeIf { it.isNotEmpty() }
