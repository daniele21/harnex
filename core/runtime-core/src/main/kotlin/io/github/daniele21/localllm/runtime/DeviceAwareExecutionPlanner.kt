package io.github.daniele21.localllm.runtime

import io.github.daniele21.localllm.models.GgufModelProfile

enum class RuntimeThermalState {
    NOMINAL,
    ELEVATED,
    SEVERE,
}

data class DeviceCapabilities(
    val logicalCpuCount: Int,
    val supportsMmap: Boolean = true,
    val supportsMlock: Boolean = false,
    val supportsFlashAttention: Boolean = false,
    val maximumGpuLayers: Int = 0,
) {
    init {
        require(logicalCpuCount > 0) { "Logical CPU count must be positive" }
        require(maximumGpuLayers >= 0) { "Maximum GPU layers must not be negative" }
    }
}

data class RuntimeResourceState(
    val memoryPressure: RuntimeMemoryPressure? = null,
    val thermalState: RuntimeThermalState = RuntimeThermalState.NOMINAL,
)

data class ExecutionWorkload(val requestedContextTokens: Int, val interactive: Boolean = true) {
    init {
        require(requestedContextTokens > 0) { "Requested context tokens must be positive" }
    }
}

data class BackendExecutionPlan(
    val policyVersion: Int,
    val modelProfileId: String,
    val contextTokens: Int,
    val batchSize: Int,
    val microBatchSize: Int,
    val cpuThreads: Int,
    val batchThreads: Int,
    val gpuLayers: Int,
    val useMmap: Boolean,
    val useMlock: Boolean,
    val flashAttention: Boolean,
)

/**
 * Pure, deterministic execution policy. It never enables a backend feature that is absent from
 * both the model profile and explicit device capabilities. Resource pressure can only downshift
 * an already-declared profile; measured auto-tuning remains a representative-device concern.
 */
class DeviceAwareExecutionPlanner(private val policyVersion: Int = CURRENT_POLICY_VERSION) {
    init {
        require(policyVersion > 0) { "Execution policy version must be positive" }
    }

    fun plan(
        device: DeviceCapabilities,
        model: GgufModelProfile,
        resources: RuntimeResourceState,
        workload: ExecutionWorkload,
    ): BackendExecutionPlan {
        val constrainedContext = workload.requestedContextTokens.coerceAtMost(model.contextSize)
        val contextTokens = selectContextTokens(model, constrainedContext, resources)
        val pressureLevel = pressureLevel(resources)
        val cpuCeiling = minOf(model.cpuThreads, device.logicalCpuCount)
        val batchThreadCeiling = minOf(model.batchThreads, device.logicalCpuCount)
        return BackendExecutionPlan(
            policyVersion = policyVersion,
            modelProfileId = model.id,
            contextTokens = contextTokens,
            batchSize = downshift(model.batchSize, pressureLevel),
            microBatchSize = downshift(model.microBatchSize, pressureLevel).coerceAtMost(downshift(model.batchSize, pressureLevel)),
            cpuThreads = downshift(cpuCeiling, pressureLevel),
            batchThreads = downshift(batchThreadCeiling, pressureLevel),
            gpuLayers = minOf(model.gpuLayers, device.maximumGpuLayers),
            useMmap = model.useMmap && device.supportsMmap,
            useMlock = model.useMlock && device.supportsMlock,
            flashAttention = model.flashAttention && device.supportsFlashAttention,
        )
    }

    private fun selectContextTokens(model: GgufModelProfile, requested: Int, resources: RuntimeResourceState): Int {
        val approved = model.runtimeCapabilities.approvedContextTiers
            .filter { it <= requested && it <= model.contextSize }
            .ifEmpty { listOf(requested) }
        val sorted = approved.sorted()
        return when (pressureLevel(resources)) {
            0 -> sorted.last()
            1 -> sorted.getOrElse((sorted.lastIndex - 1).coerceAtLeast(0)) { sorted.first() }
            else -> sorted.first()
        }
    }

    private fun pressureLevel(resources: RuntimeResourceState): Int = when {
        resources.memoryPressure == RuntimeMemoryPressure.LOW_MEMORY || resources.thermalState == RuntimeThermalState.SEVERE -> 2

        resources.memoryPressure == RuntimeMemoryPressure.BACKGROUND ||
            resources.memoryPressure == RuntimeMemoryPressure.UI_HIDDEN ||
            resources.thermalState == RuntimeThermalState.ELEVATED -> 1

        else -> 0
    }

    private fun downshift(value: Int, pressureLevel: Int): Int {
        require(value > 0) { "Execution profile values must be positive" }
        return when (pressureLevel) {
            0 -> value
            1 -> (value / 2).coerceAtLeast(1)
            else -> (value / 4).coerceAtLeast(1)
        }
    }

    private companion object {
        const val CURRENT_POLICY_VERSION = 1
    }
}
