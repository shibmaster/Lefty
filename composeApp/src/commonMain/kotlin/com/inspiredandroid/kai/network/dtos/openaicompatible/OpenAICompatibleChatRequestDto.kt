package com.inspiredandroid.kai.network.dtos.openaicompatible

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class OpenAICompatibleChatRequestDto(
    val messages: List<Message>,
    val model: String? = null,
    val tools: List<Tool>? = null,
    // Sampling — null fields are omitted from the body (explicitNulls = false).
    val temperature: Double? = null,
    val top_p: Double? = null,
    val max_tokens: Int? = null,
    val presence_penalty: Double? = null,
    val frequency_penalty: Double? = null,
    val seed: Long? = null,
    val stop: List<String>? = null,
    // llama.cpp server extensions; only sent to the generic OpenAI-compatible service.
    val top_k: Int? = null,
    val min_p: Double? = null,
    val repeat_penalty: Double? = null,
) {
    @Serializable
    data class Message(
        val role: String, // "system", "user", "assistant", "tool"
        val content: JsonElement? = null, // String or array of content parts (for vision)
        val tool_calls: List<ToolCall>? = null,
        val tool_call_id: String? = null, // Required for "tool" role messages
        // Echoed back on assistant turns that produced tool_calls for providers
        // that require it (DeepSeek thinking, Fireworks, LongCat, MiniMax,
        // Moonshot/Kimi thinking, Venice, Z.AI/GLM thinking, OpenCode Zen,
        // OpenRouter). Groq/Cerebras strict-reject it, so emission is gated by
        // Service.reasoningRequestMode.
        @SerialName("reasoning_content")
        val reasoningContent: String? = null,
    )

    @Serializable
    data class Tool(
        val type: String = "function", // Currently only "function" is widely supported
        val function: Function,
    )

    @Serializable
    data class Function(
        val name: String,
        val description: String? = null,
        val parameters: Parameters? = null,
        val strict: Boolean? = null, // Optional (for Structured Outputs / strict mode)
    )

    @Serializable
    data class Parameters(
        val type: String = "object",
        val properties: Map<String, PropertySchema>,
        val required: List<String>? = null,
        val additionalProperties: Boolean? = null,
    )

    @Serializable
    data class PropertySchema(
        val type: String, // "string", "number", "boolean", "integer", "array", "object"
        val description: String? = null,
        val enum: List<String>? = null, // Optional enum values
        val items: PropertySchema? = null, // For type: "array"
        val properties: Map<String, PropertySchema>? = null, // For type: "object"
        val required: List<String>? = null,
        val additionalProperties: Boolean? = null,
    )

    @Serializable
    data class ToolCall(
        val id: String,
        val type: String = "function",
        val function: FunctionCall,
    )

    @Serializable
    data class FunctionCall(
        val name: String,
        val arguments: String, // JSON string of the args → parse it in your code
    )
}
