package com.inspiredandroid.kai.data

import com.inspiredandroid.kai.TerminalLine
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversationStorageTest {

    private fun conversation(id: String, vararg messages: String) = Conversation(
        id = id,
        messages = messages.mapIndexed { index, content ->
            Conversation.Message(id = "$id-msg$index", role = if (index % 2 == 0) "user" else "assistant", content = content)
        },
        createdAt = 1000L,
        updatedAt = 2000L,
        title = "Title $id",
    )

    private fun createStorage(settings: MapSettings = MapSettings()): ConversationStorage {
        val appSettings = AppSettings(settings)
        return ConversationStorage(appSettings, SettingsConversationPersistence(appSettings))
    }

    @Test
    fun `saved conversations survive a reload through a fresh storage`() {
        val settings = MapSettings()
        val storage = createStorage(settings)
        storage.saveConversation(conversation("c1", "Hello", "Hi!"))
        storage.saveConversation(conversation("c2", "Ping"))

        val reloaded = createStorage(settings)
        reloaded.loadConversations()

        val conversations = reloaded.conversations.value
        assertEquals(listOf("c1", "c2"), conversations.map { it.id })
        assertEquals(listOf("Hello", "Hi!"), conversations[0].messages.map { it.content })
        assertEquals("Title c2", conversations[1].title)
    }

    @Test
    fun `saving an existing conversation replaces it in place`() {
        val storage = createStorage()
        storage.saveConversation(conversation("c1", "Hello"))
        storage.saveConversation(conversation("c2", "Other"))
        storage.saveConversation(conversation("c1", "Hello", "Hi!", "More"))

        val conversations = storage.conversations.value
        assertEquals(listOf("c1", "c2"), conversations.map { it.id })
        assertEquals(3, conversations[0].messages.size)
    }

    @Test
    fun `chat-layer save without transcript preserves the stored transcript`() {
        val storage = createStorage()
        val withTranscript = conversation("c1", "Hello").copy(
            shellTranscript = listOf(TerminalLine.Command("ls"), TerminalLine.Output("file.txt")),
        )
        storage.saveConversation(withTranscript)
        storage.saveConversation(conversation("c1", "Hello", "Hi!"))

        val saved = storage.conversations.value.single()
        assertEquals(2, saved.shellTranscript.size)
        assertEquals(2, saved.messages.size)
    }

    @Test
    fun `rename changes only the title and survives a reload`() {
        val settings = MapSettings()
        val storage = createStorage(settings)
        storage.saveConversation(conversation("c1", "Hello", "Hi!"))
        storage.saveConversation(conversation("c2", "Other"))

        storage.renameConversation("c1", "Greetings")
        storage.renameConversation("missing", "Nothing")

        val reloaded = createStorage(settings)
        reloaded.loadConversations()
        val renamed = reloaded.conversations.value
        assertEquals(listOf("c1", "c2"), renamed.map { it.id })
        assertEquals("Greetings", renamed[0].title)
        assertEquals(2000L, renamed[0].updatedAt)
        assertEquals(listOf("Hello", "Hi!"), renamed[0].messages.map { it.content })
        assertEquals("Title c2", renamed[1].title)
    }

    @Test
    fun `renamed title is trimmed, capped, and blank restores the automatic one`() {
        val chat = conversation("c1", "What is the capital of France and why is it Paris of all places?", "Paris.")
        assertEquals("Trip", renamedConversationTitle("  Trip \n", chat))
        assertEquals(100, renamedConversationTitle("x".repeat(300), chat).length)
        assertEquals("What is the capital of France and why is it Paris...", renamedConversationTitle("   ", chat))
        assertEquals("", renamedConversationTitle("", conversation("c2")))
    }

    @Test
    fun `deleteConversation removes it from flow and persistence`() {
        val settings = MapSettings()
        val storage = createStorage(settings)
        storage.saveConversation(conversation("c1", "Hello"))
        storage.saveConversation(conversation("c2", "Other"))
        storage.deleteConversation("c1")

        assertEquals(listOf("c2"), storage.conversations.value.map { it.id })

        val reloaded = createStorage(settings)
        reloaded.loadConversations()
        assertEquals(listOf("c2"), reloaded.conversations.value.map { it.id })
    }

    @Test
    fun `updateShellTranscript trims older lines beyond the char budget`() {
        val settings = MapSettings()
        val storage = createStorage(settings)
        storage.saveConversation(conversation("c1", "Hello"))

        val longLine = "x".repeat(6_000)
        storage.updateShellTranscript(
            "c1",
            listOf(
                TerminalLine.Output(longLine),
                TerminalLine.Output(longLine),
                TerminalLine.Output("tail"),
            ),
        )

        val transcript = storage.conversations.value.single().shellTranscript
        assertEquals(2, transcript.size)
        assertEquals(longLine, transcript[0].text)
        assertEquals("tail", transcript[1].text)

        val reloaded = createStorage(settings)
        reloaded.loadConversations()
        assertEquals(2, reloaded.conversations.value.single().shellTranscript.size)
    }

    @Test
    fun `updateShellTranscript is a no-op for unsaved conversations`() {
        val storage = createStorage()
        storage.updateShellTranscript("missing", listOf(TerminalLine.Output("hello")))
        assertTrue(storage.conversations.value.isEmpty())
    }

    @Test
    fun `single oversized line keeps its tail`() {
        val storage = createStorage()
        storage.saveConversation(conversation("c1", "Hello"))

        storage.updateShellTranscript("c1", listOf(TerminalLine.Output("a".repeat(9_000) + "b".repeat(6_000))))

        val transcript = storage.conversations.value.single().shellTranscript
        assertEquals(1, transcript.size)
        assertEquals(10_000, transcript.single().text.length)
        assertTrue(transcript.single().text.endsWith("b"))
    }
}
