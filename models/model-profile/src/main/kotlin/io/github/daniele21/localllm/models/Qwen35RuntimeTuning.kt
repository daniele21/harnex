package io.github.daniele21.localllm.models

enum class Qwen35RuntimeEvidenceStatus {
    CANDIDATE,
    MEASURED,
}

data class Qwen35RuntimeTuningProfile(
    val id: String,
    val version: Int,
    val tier: Qwen35ModelTier,
    val approvedContextTiers: List<Int>,
    val defaultContextTokens: Int,
    val batchSize: Int,
    val microBatchSize: Int,
    val maxCpuThreads: Int,
    val maxBatchThreads: Int,
    val useMmap: Boolean,
    val useMlock: Boolean,
    val flashAttention: Boolean,
    val evidenceStatus: Qwen35RuntimeEvidenceStatus,
) {
    init {
        require(id.isNotBlank()) { "Runtime tuning profile ID must not be blank" }
        require(version > 0) { "Runtime tuning profile version must be positive" }
        require(approvedContextTiers.isNotEmpty()) { "Qwen3.5 context tiers must not be empty" }
        require(defaultContextTokens in approvedContextTiers) { "Default context must be an approved tier" }
        require(batchSize > 0 && microBatchSize > 0 && microBatchSize <= batchSize) {
            "Invalid batch configuration"
        }
        require(maxCpuThreads > 0 && maxBatchThreads > 0) { "Thread caps must be positive" }
    }

    fun resolve(availableProcessors: Int): Qwen35ResolvedRuntimeTuning {
        val processors = availableProcessors.coerceAtLeast(1)
        return Qwen35ResolvedRuntimeTuning(
            profileId = id,
            profileVersion = version,
            tier = tier,
            contextTokens = defaultContextTokens,
            batchSize = batchSize,
            microBatchSize = microBatchSize,
            cpuThreads = processors.coerceAtMost(maxCpuThreads),
            batchThreads = processors.coerceAtMost(maxBatchThreads),
            useMmap = useMmap,
            useMlock = useMlock,
            flashAttention = flashAttention,
            evidenceStatus = evidenceStatus,
        )
    }

    fun runtimeCapabilities(): RuntimeCapabilityProfile = RuntimeCapabilityProfile(
        requiredBackendId = "llama.cpp",
        requiredBackendRevision = Qwen35RuntimeTuningProfiles.LLAMA_CPP_REVISION,
        approvedContextTiers = approvedContextTiers,
        contextSafetyReserveTokens = 256,
        supportsStatelessContextReuse = false,
        supportsPrefixSnapshot = false,
        supportsSessionRestore = false,
        supportsPrefixReuse = false,
    )
}

data class Qwen35ResolvedRuntimeTuning(
    val profileId: String,
    val profileVersion: Int,
    val tier: Qwen35ModelTier,
    val contextTokens: Int,
    val batchSize: Int,
    val microBatchSize: Int,
    val cpuThreads: Int,
    val batchThreads: Int,
    val useMmap: Boolean,
    val useMlock: Boolean,
    val flashAttention: Boolean,
    val evidenceStatus: Qwen35RuntimeEvidenceStatus,
)

data class Qwen35TuningCandidate(
    val tier: Qwen35ModelTier,
    val contextTokens: Int,
    val cpuThreads: Int,
    val batchThreads: Int,
    val batchSize: Int,
    val microBatchSize: Int,
) {
    val stableId: String
        get() = listOf(
            tier.name.lowercase(),
            "ctx$contextTokens",
            "t$cpuThreads",
            "bt$batchThreads",
            "b$batchSize",
            "ub$microBatchSize",
        ).joinToString("-")
}

object Qwen35RuntimeTuningProfiles {
    const val VERSION = 1
    const val LLAMA_CPP_REVISION = "c1d0e7a004015f23bc0233470b747b596f29b264"
    val APPROVED_CONTEXT_TIERS: List<Int> = listOf(1_024, 2_048, 4_096, 8_192)

    fun candidateForTier(tier: Qwen35ModelTier): Qwen35RuntimeTuningProfile = Qwen35RuntimeTuningProfile(
        id = when (tier) {
            Qwen35ModelTier.B0_8 -> "qwen35-08b-android-candidate"
            Qwen35ModelTier.B2 -> "qwen35-2b-android-candidate"
            Qwen35ModelTier.B4 -> "qwen35-4b-android-candidate"
        },
        version = VERSION,
        tier = tier,
        approvedContextTiers = APPROVED_CONTEXT_TIERS,
        defaultContextTokens = 2_048,
        batchSize = 128,
        microBatchSize = 64,
        maxCpuThreads = 4,
        maxBatchThreads = 4,
        useMmap = true,
        useMlock = false,
        flashAttention = false,
        evidenceStatus = Qwen35RuntimeEvidenceStatus.CANDIDATE,
    )

    fun tuningMatrixForTier(tier: Qwen35ModelTier): List<Qwen35TuningCandidate> = APPROVED_CONTEXT_TIERS.flatMap { context ->
        listOf(2, 4).flatMap { threads ->
            listOf(64 to 32, 128 to 64).map { (batch, microBatch) ->
                Qwen35TuningCandidate(
                    tier = tier,
                    contextTokens = context,
                    cpuThreads = threads,
                    batchThreads = threads,
                    batchSize = batch,
                    microBatchSize = microBatch,
                )
            }
        }
    }
}
