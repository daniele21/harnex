package io.github.daniele21.localllm.runtime

import io.github.daniele21.localllm.contracts.ModelDigest
import io.github.daniele21.localllm.models.ArtifactSource
import io.github.daniele21.localllm.models.GgufArtifact
import io.github.daniele21.localllm.models.GgufModelProfile
import io.github.daniele21.localllm.models.RuntimeCapabilityProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAwareExecutionPlannerTest {
    private val planner = DeviceAwareExecutionPlanner()

    @Test
    fun `nominal plan preserves supported profile settings and caps cpu to device`() {
        val plan = planner.plan(
            device = DeviceCapabilities(
                logicalCpuCount = 6,
                supportsMmap = true,
                supportsMlock = true,
                supportsFlashAttention = true,
                maximumGpuLayers = 8,
            ),
            model = profile(),
            resources = RuntimeResourceState(),
            workload = ExecutionWorkload(requestedContextTokens = 4_096),
        )

        assertEquals(1, plan.policyVersion)
        assertEquals("planner-profile", plan.modelProfileId)
        assertEquals(4_096, plan.contextTokens)
        assertEquals(512, plan.batchSize)
        assertEquals(256, plan.microBatchSize)
        assertEquals(6, plan.cpuThreads)
        assertEquals(4, plan.batchThreads)
        assertEquals(8, plan.gpuLayers)
        assertTrue(plan.useMmap)
        assertTrue(plan.useMlock)
        assertTrue(plan.flashAttention)
    }

    @Test
    fun `planner fails closed for unsupported acceleration capabilities`() {
        val plan = planner.plan(
            device = DeviceCapabilities(logicalCpuCount = 8),
            model = profile(),
            resources = RuntimeResourceState(),
            workload = ExecutionWorkload(requestedContextTokens = 4_096),
        )

        assertEquals(0, plan.gpuLayers)
        assertTrue(plan.useMmap)
        assertFalse(plan.useMlock)
        assertFalse(plan.flashAttention)
    }

    @Test
    fun `elevated pressure deterministically downshifts context batch and threads`() {
        val plan = planner.plan(
            device = DeviceCapabilities(logicalCpuCount = 8),
            model = profile(),
            resources = RuntimeResourceState(thermalState = RuntimeThermalState.ELEVATED),
            workload = ExecutionWorkload(requestedContextTokens = 4_096),
        )

        assertEquals(2_048, plan.contextTokens)
        assertEquals(256, plan.batchSize)
        assertEquals(128, plan.microBatchSize)
        assertEquals(4, plan.cpuThreads)
        assertEquals(2, plan.batchThreads)
    }

    @Test
    fun `severe pressure selects lowest approved context tier and quarter profile resources`() {
        val plan = planner.plan(
            device = DeviceCapabilities(logicalCpuCount = 16),
            model = profile(),
            resources = RuntimeResourceState(memoryPressure = RuntimeMemoryPressure.LOW_MEMORY),
            workload = ExecutionWorkload(requestedContextTokens = 8_192),
        )

        assertEquals(1_024, plan.contextTokens)
        assertEquals(128, plan.batchSize)
        assertEquals(64, plan.microBatchSize)
        assertEquals(2, plan.cpuThreads)
        assertEquals(1, plan.batchThreads)
    }

    @Test
    fun `same inputs always yield the same versioned plan`() {
        val device = DeviceCapabilities(logicalCpuCount = 6, supportsMmap = true)
        val model = profile()
        val resources = RuntimeResourceState(memoryPressure = RuntimeMemoryPressure.BACKGROUND)
        val workload = ExecutionWorkload(requestedContextTokens = 3_000)

        assertEquals(
            planner.plan(device, model, resources, workload),
            planner.plan(device, model, resources, workload),
        )
    }

    private fun profile(): GgufModelProfile = GgufModelProfile(
        id = "planner-profile",
        artifact = GgufArtifact(
            digest = ModelDigest("a".repeat(64)),
            fileName = "planner.gguf",
            sizeBytes = 4,
            architecture = "qwen35",
            quantization = "Q4_K_M",
            source = ArtifactSource.Imported("planner"),
        ),
        contextSize = 8_192,
        batchSize = 512,
        microBatchSize = 256,
        cpuThreads = 8,
        batchThreads = 4,
        gpuLayers = 12,
        useMmap = true,
        useMlock = true,
        flashAttention = true,
        runtimeCapabilities = RuntimeCapabilityProfile(approvedContextTiers = listOf(1_024, 2_048, 4_096, 8_192)),
    )
}
