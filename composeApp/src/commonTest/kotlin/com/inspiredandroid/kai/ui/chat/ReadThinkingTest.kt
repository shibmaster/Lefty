package com.inspiredandroid.kai.ui.chat

import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals

class ReadThinkingTest {

    private fun user(t: String) = History(role = History.Role.USER, content = t)
    private fun thinking(t: String) = History(role = History.Role.ASSISTANT, content = t, isThinking = true)

    @Test
    fun `thinking of the current turn only, in order, then the answer's own trace`() {
        val oldAnswer = History(role = History.Role.ASSISTANT, content = "old", reasoningContent = "old trace")
        val toolTurn = History(
            role = History.Role.ASSISTANT,
            content = "",
            toolCalls = persistentListOf(ToolCallInfo(id = "1", name = "search", arguments = "{}")),
            reasoningContent = "need to search",
        )
        val answer = History(role = History.Role.ASSISTANT, content = "It's 5 pm.", reasoningContent = "found it")
        val history = listOf(user("hi"), oldAnswer, user("time?"), thinking("let me think"), toolTurn, answer)

        assertEquals(listOf("let me think", "need to search", "found it"), history.thinkingFor(answer))
    }

    @Test
    fun `speech text only includes thinking when enabled`() {
        assertEquals("answer", speechTextFor("answer", listOf("t1"), includeThinking = false))
        assertEquals("t1\n\nt2\n\nanswer", speechTextFor("answer", listOf("t1", "t2"), includeThinking = true))
        assertEquals("answer", speechTextFor("answer", emptyList(), includeThinking = true))
    }
}
