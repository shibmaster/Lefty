package com.inspiredandroid.kai.data

import com.inspiredandroid.kai.network.dtos.openaicompatible.OpenAICompatibleChatRequestDto
import com.inspiredandroid.kai.ui.chat.History
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Pure context-budget helpers: estimating message size, trimming to a character budget and
// deciding where compaction cuts the history. Budgets are in characters; callers convert from
// tokens with the instance's chars-per-token ratio.

/** Fixed cost charged for a non-text content part (image, audio, file), whose base64 is not text context. */
internal const val NON_TEXT_PART_CHARS = 100

internal fun estimateMessageChars(msg: OpenAICompatibleChatRequestDto.Message): Int {
    val contentChars = when (val content = msg.content) {
        is JsonArray -> {
            // Multimodal messages: only count text parts, not base64 image/audio data
            content.sumOf { element ->
                val obj = element as? JsonObject
                val type = (obj?.get("type") as? JsonPrimitive)?.content
                if (type == "text") {
                    (obj["text"] as? JsonPrimitive)?.content?.length ?: 0
                } else {
                    NON_TEXT_PART_CHARS
                }
            }
        }

        is JsonPrimitive -> content.content.length

        else -> content?.toString()?.length ?: 0
    }
    return contentChars + msg.role.length
}

/**
 * Trims messages to fit within [maxChars] by dropping the oldest messages, keeping the leading
 * system messages. Each assistant tool-call turn stays grouped with the tool responses that follow
 * it: strict OpenAI-compatible providers reject an assistant `tool_calls` message that isn't
 * followed by its tool responses, and a `tool` message without a preceding `tool_calls`.
 */
internal fun trimMessagesToBudget(
    messages: List<OpenAICompatibleChatRequestDto.Message>,
    maxChars: Int,
): List<OpenAICompatibleChatRequestDto.Message> {
    val totalChars = messages.sumOf { estimateMessageChars(it) }
    if (totalChars <= maxChars) return messages

    val systemMessages = messages.takeWhile { it.role == "system" }
    val nonSystemMessages = messages.drop(systemMessages.size)
    val availableChars = maxChars - systemMessages.sumOf { estimateMessageChars(it) }

    val groups = mutableListOf<List<OpenAICompatibleChatRequestDto.Message>>()
    var index = 0
    while (index < nonSystemMessages.size) {
        val msg = nonSystemMessages[index]
        if (msg.role == "assistant" && !msg.tool_calls.isNullOrEmpty()) {
            var end = index + 1
            while (end < nonSystemMessages.size && nonSystemMessages[end].role == "tool") {
                end++
            }
            groups.add(nonSystemMessages.subList(index, end).toList())
            index = end
        } else {
            groups.add(listOf(msg))
            index++
        }
    }

    // Keep whole groups from the end until we exceed the budget.
    val kept = mutableListOf<OpenAICompatibleChatRequestDto.Message>()
    var usedChars = 0
    for (group in groups.asReversed()) {
        val groupChars = group.sumOf { estimateMessageChars(it) }
        if (usedChars + groupChars > availableChars) break
        kept.addAll(0, group)
        usedChars += groupChars
    }

    return systemMessages + kept
}

/**
 * Trims History entries to fit within [maxChars] by dropping the oldest, for APIs where the system
 * prompt is sent separately ([systemPromptChars] is reserved from the budget).
 */
internal fun trimHistoryToBudget(
    history: List<History>,
    systemPromptChars: Int,
    maxChars: Int,
): List<History> {
    val totalChars = history.sumOf { it.content.length } + systemPromptChars
    if (totalChars <= maxChars) return history

    val availableChars = maxChars - systemPromptChars
    val kept = mutableListOf<History>()
    var usedChars = 0
    for (msg in history.asReversed()) {
        val msgChars = msg.content.length
        if (usedChars + msgChars > availableChars) break
        kept.add(0, msg)
        usedChars += msgChars
    }
    return kept
}

/**
 * Where to split [history] for compaction, or null when no compaction is needed: the history
 * (plus system prompt) must exceed [threshold] of [maxChars], and there must be more than
 * [keepRecent] user turns so the most recent ones can be kept verbatim. The returned index is the
 * first message kept; everything before it gets summarized.
 */
internal fun compactionCutoffIndex(
    history: List<History>,
    systemPromptChars: Int,
    maxChars: Int,
    threshold: Double,
    keepRecent: Int,
): Int? {
    val totalChars = history.sumOf { it.content.length } + systemPromptChars
    if (totalChars <= (maxChars * threshold).toInt()) return null
    val userIndices = history.mapIndexedNotNull { index, h -> if (h.role == History.Role.USER) index else null }
    if (keepRecent < 1 || userIndices.size <= keepRecent) return null
    return userIndices[userIndices.size - keepRecent].takeIf { it > 0 }
}
