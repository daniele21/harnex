package io.github.daniele21.localllm.externalembedded

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        assertEquals(VoicePhase.READY, state.settleIfUnfinished(completed = false, modelReady = true))
        assertFalse(state.completeTranscription())
    }

    @Test
    fun aborted_model_download_does_not_become_ready() {
        val state = VoiceInputState()
        assertTrue(state.beginPreparation())
        assertTrue(state.cancel())
        assertFalse(state.completePreparation())
        assertEquals(VoicePhase.NEEDS_MODEL, state.settleIfUnfinished(completed = false, modelReady = false))
    }

    @Test
    fun completed_transcription_cleanup_cannot_reset_the_next_recording() {
        val state = VoiceInputState()
        assertTrue(state.modelAlreadyReady())
        assertTrue(state.beginRecording())
        assertTrue(state.finishRecording())
        assertTrue(state.completeTranscription())
        assertTrue(state.beginRecording())
        // The previous worker can still enter finally after publishing READY.
        assertNull(state.settleIfUnfinished(completed = true, modelReady = true))
        assertEquals(VoicePhase.RECORDING, state.current())
    }

    @Test
    fun completed_preparation_cleanup_cannot_reset_a_new_recording() {
        val state = VoiceInputState()
        assertTrue(state.beginPreparation())
        assertTrue(state.completePreparation())
        assertTrue(state.beginRecording())
        assertNull(state.settleIfUnfinished(completed = true, modelReady = false))
        assertEquals(VoicePhase.RECORDING, state.current())
    }

    @Test
    fun recorder_setup_failure_recovers_from_recording_phase() {
        val state = VoiceInputState()
        assertTrue(state.modelAlreadyReady())
        assertTrue(state.beginRecording())
        assertEquals(VoicePhase.READY, state.settleIfUnfinished(completed = false, modelReady = true))
        assertTrue(state.beginRecording())
    }

    @Test
    fun stopping_during_recording_disallows_old_completion() {
        val state = VoiceInputState()
        assertTrue(state.modelAlreadyReady())
        assertTrue(state.beginRecording())
        assertTrue(state.cancel())
        assertFalse(state.finishRecording())
        assertFalse(state.completeTranscription())
        assertEquals(VoicePhase.READY, state.settleIfUnfinished(completed = false, modelReady = true))
    }
}
