package io.github.daniele21.localllm.runtime

import io.github.daniele21.localllm.contracts.EffectiveGenerationMetadata
import io.github.daniele21.localllm.contracts.GenerationMetrics
import io.github.daniele21.localllm.contracts.GenerationRequest
import io.github.daniele21.localllm.contracts.LocalLlmError
import io.github.daniele21.localllm.contracts.ModelDigest
import io.github.daniele21.localllm.contracts.ModelLoadKind
import io.github.daniele21.localllm.contracts.RequestId
import io.github.daniele21.localllm.observability.ExecutionPlacementStatus
import io.github.daniele21.localllm.observability.GenerationRunRecord
import io.github.daniele21.localllm.observability.LogLevel
import io.github.daniele21.localllm.observability.RunStatus
import io.github.daniele21.localllm.observability.StructuredLog
import io.github.daniele21.localllm.observability.TelemetryRepository
import java.util.concurrent.ConcurrentHashMap

fun interface EpochClock {
    fun nowEpochMs(): Long
}

@Suppress("TooManyFunctions")
internal class RuntimeTelemetry(
    private val repository: TelemetryRepository,
    private val clock: EpochClock,
    private val enforceMemoryAdmission: Boolean = true,
) {
    private val activeRuns = ConcurrentHashMap<RequestId, GenerationRunRecord>()

    fun queued(request: GenerationRequest, modelDigest: ModelDigest) {
        val run = GenerationRunRecord(
            requestId = request.requestId,
            applicationId = request.applicationId,
            useCaseId = request.useCaseId,
            modelDigest = modelDigest,
            startedAtEpochMs = clock.nowEpochMs(),
            completedAtEpochMs = null,
            status = RunStatus.QUEUED,
            queueMs = null,
            modelLoadMs = null,
            timeToFirstTokenMs = null,
            totalMs = null,
            inputTokens = null,
            outputTokens = null,
            decodeTokensPerSecond = null,
            errorCode = null,
            modelLoadKind = ModelLoadKind.UNKNOWN,
        )
        activeRuns[request.requestId] = run
        persist(run)
        log(
            level = LogLevel.INFO,
            event = "generation.queued",
            requestId = request.requestId,
            fields = mapOf(
                "applicationId" to request.applicationId.value,
                "useCaseId" to request.useCaseId.value,
                "modelDigest" to modelDigest.sha256,
            ),
        )
    }

    fun queuedPosition(requestId: RequestId, position: Int) {
        log(
            level = LogLevel.DEBUG,
            event = "generation.queue_position",
            requestId = requestId,
            fields = mapOf("position" to position.toString()),
        )
    }

    fun started(requestId: RequestId) {
        val current = activeRuns[requestId] ?: return
        val updated = current.copy(status = RunStatus.RUNNING)
        activeRuns[requestId] = updated
        persist(updated)
        log(
            level = LogLevel.INFO,
            event = "generation.started",
            requestId = requestId,
        )
    }

    fun prepared(
        requestId: RequestId,
        configuration: EffectiveGenerationMetadata,
        promptPlanningMs: Long,
        contextCreationMs: Long?,
        executionEvidence: BackendExecutionEvidence? = null,
    ) {
        val current = activeRuns[requestId] ?: return
        val updated = current.copy(
            presetId = configuration.preset?.id,
            presetVersion = configuration.preset?.version,
            temperature = configuration.temperature,
            topP = configuration.topP,
            topK = configuration.topK,
            minP = configuration.minP,
            presencePenalty = configuration.presencePenalty,
            thinkingMode = configuration.thinkingMode,
            repeatPenalty = configuration.repeatPenalty,
            repeatLastN = configuration.repeatLastN,
            seedPolicy = configuration.requestedSeedPolicy,
            effectiveSeed = configuration.effectiveSeed,
            maxOutputTokens = configuration.maxOutputTokens,
            contextSize = configuration.contextSize,
            promptTokenCount = configuration.promptTokenCount,
            chatTemplateId = configuration.chatTemplateId,
            chatTemplateSource = configuration.chatTemplateSource,
            systemPromptVersion = configuration.systemPromptVersion,
            backendId = executionEvidence?.backendId,
            backendRevision = executionEvidence?.backendRevision,
            backendExecutionFingerprint = executionEvidence?.materialFingerprint,
            effectivePlacement = executionEvidence?.effectivePlacement?.let { status ->
                ExecutionPlacementStatus.valueOf(status.name)
            },
            promptPlanningMs = promptPlanningMs,
            contextCreationMs = contextCreationMs,
        )
        activeRuns[requestId] = updated
        persist(updated)
        log(
            level = LogLevel.INFO,
            event = "generation.prepared",
            requestId = requestId,
            fields = buildMap {
                put("contextSize", configuration.contextSize.toString())
                put("promptTokenCount", configuration.promptTokenCount.toString())
                put("chatTemplateId", configuration.chatTemplateId)
                put("chatTemplateSource", configuration.chatTemplateSource.name)
                put("thinkingMode", configuration.thinkingMode.name)
                put("minP", configuration.minP.toString())
                put("presencePenalty", configuration.presencePenalty.toString())
                put("repeatPenalty", configuration.repeatPenalty.toString())
                put("repeatLastN", configuration.repeatLastN.toString())
                configuration.preset?.let {
                    put("presetId", it.id.value)
                    put("presetVersion", it.version.toString())
                }
                executionEvidence?.let {
                    put("backendId", it.backendId)
                    it.backendRevision?.let { revision -> put("backendRevision", revision) }
                    put("backendExecutionFingerprint", it.materialFingerprint)
                    put("effectivePlacement", it.effectivePlacement.name)
                }
            },
        )
    }

    fun memoryAdmission(
        resource: MemoryAdmissionResource,
        outcome: MemoryAdmissionOutcome,
        estimate: MemoryCostEstimate? = null,
        decisionReason: String? = null,
        admissionReason: MemoryAdmissionRejectReason? = null,
        requestedContextTokens: Int? = null,
        effectiveContextTokens: Int? = null,
    ) {
        log(
            level = if (enforceMemoryAdmission && outcome == MemoryAdmissionOutcome.REJECT) LogLevel.WARN else LogLevel.INFO,
            event = if (enforceMemoryAdmission) "memory.admission" else "memory.admission.shadow",
            requestId = null,
            fields = buildMap {
                put("resource", resource.name)
                put("outcome", outcome.name)
                if (!enforceMemoryAdmission) put("enforced", "false")
                decisionReason?.let { put("decisionReason", it) }
                admissionReason?.let { put("admissionReason", it.name) }
                estimate?.let {
                    put("profileId", it.profileId)
                    put("source", it.source.name)
                    put("residentBytes", it.residentBytes.toString())
                    put("peakIncrementalBytes", it.peakIncrementalBytes.toString())
                }
                requestedContextTokens?.let { put("requestedContextTokens", it.toString()) }
                effectiveContextTokens?.let { put("effectiveContextTokens", it.toString()) }
            },
        )
    }

    fun completed(requestId: RequestId, metrics: GenerationMetrics) {
        val current = activeRuns.remove(requestId) ?: return
        val updated = current.copy(
            completedAtEpochMs = clock.nowEpochMs(),
            status = RunStatus.COMPLETED,
            queueMs = metrics.queueMs,
            modelLoadMs = metrics.modelLoadMs,
            timeToFirstTokenMs = metrics.timeToFirstTokenMs,
            totalMs = metrics.totalMs,
            inputTokens = metrics.inputTokens,
            outputTokens = metrics.outputTokens,
            decodeTokensPerSecond = metrics.decodeTokensPerSecond,
            prefillMs = metrics.prefillMs,
            decodeMs = metrics.decodeMs,
            modelLoadKind = metrics.modelLoadKind,
            stopReason = metrics.stopReason,
            promptPlanningMs = metrics.promptPlanningMs ?: current.promptPlanningMs,
            contextCreationMs = metrics.contextCreationMs ?: current.contextCreationMs,
        )
        persist(updated)
        log(
            level = LogLevel.INFO,
            event = "generation.completed",
            requestId = requestId,
            fields = metricsFields(metrics),
        )
    }

    fun failed(requestId: RequestId, error: LocalLlmError) {
        val current = activeRuns.remove(requestId)
        if (current == null) {
            rejected(requestId, error)
            return
        }
        val status = if (error.code == "CANCELLED") RunStatus.CANCELLED else RunStatus.FAILED
        val updated = current.copy(
            completedAtEpochMs = clock.nowEpochMs(),
            status = status,
            errorCode = error.code,
        )
        persist(updated)
        log(
            level = if (status == RunStatus.CANCELLED) LogLevel.INFO else LogLevel.ERROR,
            event = if (status == RunStatus.CANCELLED) "generation.cancelled" else "generation.failed",
            requestId = requestId,
            fields = mapOf("errorCode" to error.code),
        )
    }

    fun rejected(requestId: RequestId, error: LocalLlmError) {
        log(
            level = if (error.code == "CANCELLED") LogLevel.INFO else LogLevel.WARN,
            event = "generation.rejected",
            requestId = requestId,
            fields = mapOf("errorCode" to error.code),
        )
    }

    private fun persist(run: GenerationRunRecord) {
        safely { repository.recordRun(run) }
    }

    private fun log(level: LogLevel, event: String, requestId: RequestId?, fields: Map<String, String> = emptyMap()) {
        safely {
            repository.appendLog(
                StructuredLog(
                    timestampEpochMs = clock.nowEpochMs(),
                    level = level,
                    component = "runtime",
                    event = event,
                    requestId = requestId,
                    fields = fields,
                ),
            )
        }
    }

    private fun metricsFields(metrics: GenerationMetrics): Map<String, String> = buildMap {
        put("queueMs", metrics.queueMs.toString())
        put("totalMs", metrics.totalMs.toString())
        put("modelLoadKind", metrics.modelLoadKind.name)
        put("stopReason", metrics.stopReason.name)
        metrics.modelLoadMs?.let { put("modelLoadMs", it.toString()) }
        metrics.timeToFirstTokenMs?.let { put("timeToFirstTokenMs", it.toString()) }
        metrics.prefillMs?.let { put("prefillMs", it.toString()) }
        metrics.decodeMs?.let { put("decodeMs", it.toString()) }
        metrics.inputTokens?.let { put("inputTokens", it.toString()) }
        metrics.outputTokens?.let { put("outputTokens", it.toString()) }
        metrics.decodeTokensPerSecond?.let { put("decodeTokensPerSecond", it.toString()) }
    }

    private inline fun safely(operation: () -> Unit) {
        runCatching(operation)
    }
}
