package io.github.daniele21.localllm.externalembedded

import io.github.daniele21.localllm.contracts.ConversationRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EphemeralChatHistoryTest {
    @Test
    fun sends_only_current_prompt_when_no_turn_has_completed() {
        val chat = EphemeralChatHistory()
        assertEquals(listOf(ConversationRole.USER), chat.messagesFor("Who am I?").map { it.role })
        assertEquals(0, chat.completedTurns())
    }

    @Test
    fun sends_explicit_user_assistant_history_with_next_prompt() {
        val chat = EphemeralChatHistory()
        chat.complete("My name is Mario", "Hello Mario")
        val snapshot = chat.messagesFor("What's my name?")
        assertEquals(
            listOf(ConversationRole.USER, ConversationRole.ASSISTANT, ConversationRole.USER),
            snapshot.map { it.role },
        )
        assertEquals(3, snapshot.size)
    }

    @Test
    fun retains_only_bounded_complete_turns_and_can_reset_without_persistence() {
        val chat = EphemeralChatHistory(maxTurns = 2)
        chat.complete("first", "a")
        chat.complete("second", "b")
        chat.complete("third", "c")
        assertEquals(5, chat.messagesFor("next").size)
        assertEquals(2, chat.completedTurns())
        chat.reset()
        assertEquals(1, chat.messagesFor("next").size)
    }

    @Test
    fun drops_oldest_complete_turns_to_fit_context() {
        val chat = EphemeralChatHistory(maxTurns = 5, maxContextChars = 21)
        chat.complete("older", "answer1")
        chat.complete("newer", "answer2")
        val snapshot = chat.messagesFor("question")
        assertEquals(3, snapshot.size)
        assertEquals("newer", snapshot[0].content)
    }

    @Test
    fun rejects_oversized_input_before_dispatch() {
        val chat = EphemeralChatHistory()
        assertThrows(IllegalArgumentException::class.java) {
            chat.messagesFor("a".repeat(EphemeralChatHistory.MAX_USER_CHARS + 1))
        }
    }
}
