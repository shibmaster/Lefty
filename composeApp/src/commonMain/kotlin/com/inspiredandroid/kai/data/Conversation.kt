package com.inspiredandroid.kai.data

import androidx.compose.runtime.Immutable
import com.inspiredandroid.kai.TerminalLine
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * A single file attachment on a chat message. Used both in-memory on `History` and
 * persisted on `Conversation.Message`. Binary content is base64-encoded.
 */
@Immutable
@Serializable
data class Attachment(
    val data: String,
    val mimeType: String,
    val fileName: String? = null,
)

@Serializable
data class Conversation(
    val id: String,
    val messages: List<Message>,
    val createdAt: Long,
    val updatedAt: Long,
    val title: String = "",
    val type: String = TYPE_CHAT,
    val shellTranscript: List<TerminalLine> = emptyList(),
) {
    companion object {
        const val TYPE_CHAT = "chat"
        const val TYPE_HEARTBEAT = "heartbeat"
        const val TYPE_INTERACTIVE = "interactive"
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    data class Message(
        val id: String,
        val role: String,
        val content: String,
        val attachments: List<Attachment> = emptyList(),
        val uiSubmission: UiSubmission? = null,
        val isThinking: Boolean = false,
        // Most messages have no reasoning trace; skip the null to keep the persisted blob lean.
        @EncodeDefault(EncodeDefault.Mode.NEVER)
        val reasoningContent: String? = null,
        // Legacy single-file fields — retained for reading old persisted conversations.
        // New code writes only `attachments`; these remain null on newly saved messages.
        val mimeType: String? = null,
        val data: String? = null,
        val fileName: String? = null,
    )
}

/**
 * Snapshot of a kai-ui form the user submitted. Attached to the resulting User message so
 * the bubble renders as a frozen form (with the values the user picked) instead of the
 * cryptic "Responded with: ..." text. `sourceContent` holds the assistant message body that
 * originated the form — it's re-parsed at render time.
 */
@Immutable
@Serializable
data class UiSubmission(
    val sourceContent: String,
    val values: Map<String, String> = emptyMap(),
    val pressedEvent: String? = null,
)

@Serializable
data class ConversationsData(
    val version: Int = 2,
    val conversations: List<Conversation>,
)

private const val MAX_CONVERSATION_TITLE_LENGTH = 100

/** Automatic title: the first user message, cut to ~50 characters at a word boundary. */
internal fun deriveConversationTitle(firstUserMessage: String?): String {
    if (firstUserMessage == null) return ""
    return if (firstUserMessage.length <= 50) {
        firstUserMessage
    } else {
        val truncated = firstUserMessage.take(50)
        val lastSpace = truncated.lastIndexOf(' ')
        if (lastSpace > 20) truncated.substring(0, lastSpace) + "..." else truncated + "..."
    }
}

/** Title for a rename: trimmed and capped; blank goes back to the automatic title. */
internal fun renamedConversationTitle(input: String, conversation: Conversation): String = input.trim().take(MAX_CONVERSATION_TITLE_LENGTH).ifEmpty {
    deriveConversationTitle(conversation.messages.firstOrNull { it.role == "user" }?.content)
}
