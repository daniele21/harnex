package io.github.daniele21.localllm.runtime

import io.github.daniele21.localllm.observability.LogLevel
import io.github.daniele21.localllm.observability.store.InMemoryTelemetryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryAdmissionTelemetryTest {
    @Test
    fun `shadow rejection is informational and is never reported as enforced admission`() {
        val repository = InMemoryTelemetryRepository()
        val telemetry = RuntimeTelemetry(repository, EpochClock { 1_000L }, enforceMemoryAdmission = false)

        telemetry.memoryAdmission(
            resource = MemoryAdmissionResource.MODEL,
            outcome = MemoryAdmissionOutcome.REJECT,
            decisionReason = MemoryAwareModelLoadRejectReason.MEMORY_COST_ESTIMATE_UNAVAILABLE.name,
        )

        val log = repository.recentLogs().single()
        assertEquals("memory.admission.shadow", log.event)
        assertEquals(LogLevel.INFO, log.level)
        assertNull(log.requestId)
        assertEquals("false", log.fields["enforced"])
        assertEquals("REJECT", log.fields["outcome"])
        assertEquals("MEMORY_COST_ESTIMATE_UNAVAILABLE", log.fields["decisionReason"])
        assertTrue(log.fields.keys.none { it.contains("prompt", ignoreCase = true) })
    }

    @Test
    fun `records downshift with only resource policy and cost fields`() {
        val repository = InMemoryTelemetryRepository()
        val telemetry = RuntimeTelemetry(repository, EpochClock { 1_000L })

        telemetry.memoryAdmission(
            resource = MemoryAdmissionResource.CONTEXT,
            outcome = MemoryAdmissionOutcome.DOWNSHIFT,
            estimate = MemoryCostEstimate(
                residentBytes = 512L,
                peakIncrementalBytes = 768L,
                source = MemoryCostSource.MEASURED,
                profileId = "context-measured-v1",
            ),
            requestedContextTokens = 4_096,
            effectiveContextTokens = 2_048,
        )

        val log = repository.recentLogs().single()
        assertEquals("memory.admission", log.event)
        assertEquals(LogLevel.INFO, log.level)
        assertNull(log.requestId)
        assertEquals("CONTEXT", log.fields["resource"])
        assertEquals("DOWNSHIFT", log.fields["outcome"])
        assertEquals("MEASURED", log.fields["source"])
        assertEquals("4096", log.fields["requestedContextTokens"])
        assertEquals("2048", log.fields["effectiveContextTokens"])
        assertTrue(log.fields.keys.none { it.contains("prompt", ignoreCase = true) || it.contains("output", ignoreCase = true) })
    }

    @Test
    fun `records typed model rejection reasons without request content`() {
        val repository = InMemoryTelemetryRepository()
        val telemetry = RuntimeTelemetry(repository, EpochClock { 2_000L })

        telemetry.memoryAdmission(
            resource = MemoryAdmissionResource.MODEL,
            outcome = MemoryAdmissionOutcome.REJECT,
            decisionReason = MemoryAwareModelLoadRejectReason.MEMORY_BUDGET_REJECTED.name,
            admissionReason = MemoryAdmissionRejectReason.AVAILABLE_MEMORY_FLOOR,
        )

        val log = repository.recentLogs().single()
        assertEquals(LogLevel.WARN, log.level)
        assertEquals("MODEL", log.fields["resource"])
        assertEquals("REJECT", log.fields["outcome"])
        assertEquals("MEMORY_BUDGET_REJECTED", log.fields["decisionReason"])
        assertEquals("AVAILABLE_MEMORY_FLOOR", log.fields["admissionReason"])
        assertNull(log.requestId)
    }
}
