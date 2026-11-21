package io.github.daniele21.localllm.externalembedded

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ChatSessionStateTest {
    private fun prepared(): ChatSessionState = ChatSessionState().apply {
        assertTrue(beginPreparation())
        assertTrue(completePreparation())
    }

    @Test
    fun stop_before_completion_discards_output_and_allows_another_turn() {
        val state = prepared()
        val history = EphemeralChatHistory()
        assertTrue(state.beginGeneration())
        assertTrue(state.requestStop())
        assertFalse(state.acceptCompletion())
        if (state.acceptCompletion()) history.complete("my name is Mario", "Mario")
        assertEquals(ChatPhase.READY, state.settleGeneration())
        assertEquals(1, history.messagesFor("What is my name?").size)
        assertTrue(state.beginGeneration())
    }

    @Test
    fun completion_before_stop_can_commit_one_completed_turn() {
        val state = prepared()
        val history = EphemeralChatHistory()
        assertTrue(state.beginGeneration())
        assertTrue(state.acceptCompletion())
        assertFalse(state.requestStop())
        history.complete("my name is Mario", "Hello Mario")
        assertEquals(ChatPhase.READY, state.settleGeneration())
        assertEquals(3, history.messagesFor("What is my name?").size)
    }

    @Test
    fun stop_during_preparation_never_marks_model_ready() {
        val state = ChatSessionState()
        assertTrue(state.beginPreparation())
        assertTrue(state.requestStop())
        assertFalse(state.completePreparation())
        state.requirePreparation()
        assertEquals(ChatPhase.NEEDS_MODEL, state.current())
    }

    @Test
    fun timeout_requires_preparation_and_rejects_stale_completion() {
        val state = prepared()
        assertTrue(state.beginGeneration())
        state.requirePreparation()
        assertFalse(state.acceptCompletion())
        assertEquals(ChatPhase.NEEDS_MODEL, state.settleGeneration())
        assertFalse(state.beginGeneration())
    }

    @Test
    fun simultaneous_stop_and_completion_have_exactly_one_winner() {
        repeat(50) {
            val state = prepared()
            assertTrue(state.beginGeneration())
            val start = CountDownLatch(1)
            val done = CountDownLatch(2)
            val stopped = AtomicBoolean(false)
            val completed = AtomicBoolean(false)
            val stopThread = Thread {
                start.await()
                stopped.set(state.requestStop())
                done.countDown()
            }
            val completeThread = Thread {
                start.await()
                completed.set(state.acceptCompletion())
                done.countDown()
            }
            stopThread.start()
            completeThread.start()
            start.countDown()
            assertTrue("Racing threads did not finish", done.await(5, TimeUnit.SECONDS))
            stopThread.join()
            completeThread.join()
            assertTrue("Stop and completion must be mutually exclusive", stopped.get() != completed.get())
            assertEquals(ChatPhase.READY, state.settleGeneration())
        }
    }
}
