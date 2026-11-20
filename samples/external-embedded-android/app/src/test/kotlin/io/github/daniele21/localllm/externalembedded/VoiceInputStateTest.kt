package io.github.daniele21.localllm.externalembedded

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceInputStateTest {
    @Test
    fun preparation_and_recording_have_one_owner() {
        val state = VoiceInputState()
        assertTrue(state.beginPreparation())
        assertFalse(state.beginPreparation())
        assertTrue(state.completePreparation())
        assertTrue(state.beginRecording())
        assertFalse(state.beginRecording())
        assertTrue(state.finishRecording())
        assertTrue(state.completeTranscription())
        assertEquals(VoicePhase.READY, state.current())
    }

    @Test
    fun cancellation_never_publishes_stale_transcription() {
        val state = VoiceInputState()
        assertTrue(state.modelAlreadyReady())
        assertTrue(state.beginRecording())
        assertTrue(state.finishRecording())
        assertTrue(state.cancel())
        assertFalse(state.completeTranscription())
        assertEquals(VoicePhase.READY, state.settle(true))
        assertFalse(state.completeTranscription())
    }

    @Test
    fun aborted_model_download_does_not_become_ready() {
        val state = VoiceInputState()
        assertTrue(state.beginPreparation())
        assertTrue(state.cancel())
        assertFalse(state.completePreparation())
        assertEquals(VoicePhase.NEEDS_MODEL, state.settle(false))
    }

    @Test
    fun stopping_during_recording_disallows_old_completion() {
        val state = VoiceInputState()
        assertTrue(state.modelAlreadyReady())
        assertTrue(state.beginRecording())
        assertTrue(state.cancel())
        assertFalse(state.finishRecording())
        assertFalse(state.completeTranscription())
        assertEquals(VoicePhase.READY, state.settle(true))
    }
}
