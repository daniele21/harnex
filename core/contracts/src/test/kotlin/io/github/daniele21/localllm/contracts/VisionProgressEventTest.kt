package io.github.daniele21.localllm.contracts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VisionProgressEventTest {
    @Test
    fun eventCarriesNativeStageAndTimeOnly() {
        val id = RequestId("vision-1")
        val event = GenerationEvent.VisionProgress(
            requestId = id,
            stage = VisionStage.PREFILL_STARTED,
            elapsedMs = 1_150L,
        )
        assertEquals(id, event.requestId)
        assertEquals(VisionStage.PREFILL_STARTED, event.stage)
        assertEquals(1_150L, event.elapsedMs)
    }

    @Test
    fun negativeTimeIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            GenerationEvent.VisionProgress(
                requestId = RequestId("vision-2"),
                stage = VisionStage.FIRST_TOKEN,
                elapsedMs = -1L,
            )
        }
    }
}
