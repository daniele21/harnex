package io.github.daniele21.localllm.runtime

import io.github.daniele21.localllm.contracts.OutputConstraint
import io.github.daniele21.localllm.contracts.StopReason
import io.github.daniele21.localllm.llamacpp.ContextCreationResult
import io.github.daniele21.localllm.llamacpp.EvaluationBatchCancelResult
import io.github.daniele21.localllm.llamacpp.EvaluationContextCreationResult
import io.github.daniele21.localllm.llamacpp.GenerationNativeOperationResult
import io.github.daniele21.localllm.llamacpp.LlamaCppBridge
import io.github.daniele21.localllm.llamacpp.LlamaCppEvaluationBatchBridge
import io.github.daniele21.localllm.llamacpp.LlamaCppGenerationBridge
import io.github.daniele21.localllm.llamacpp.LlamaCppPromptPlanningBridge
import io.github.daniele21.localllm.llamacpp.LlamaCppStreamingBridge
import io.github.daniele21.localllm.llamacpp.LoadedNativeContext
import io.github.daniele21.localllm.llamacpp.LoadedNativeEvaluationContext
import io.github.daniele21.localllm.llamacpp.LoadedNativeModel
import io.github.daniele21.localllm.llamacpp.ModelLoadResult
import io.github.daniele21.localllm.llamacpp.NativeBackendDevice
import io.github.daniele21.localllm.llamacpp.NativeEvaluationBatchCase
import io.github.daniele21.localllm.llamacpp.NativeEvaluationBatchCaseStatus
import io.github.daniele21.localllm.llamacpp.NativeEvaluationBatchResult
import io.github.daniele21.localllm.llamacpp.NativeGenerationConfig
import io.github.daniele21.localllm.llamacpp.NativeModelCapabilitiesResult
import io.github.daniele21.localllm.llamacpp.NativeOperationResult
import io.github.daniele21.localllm.llamacpp.NativePromptPlanningResult
import io.github.daniele21.localllm.llamacpp.NativeStreamingListener
import io.github.daniele21.localllm.llamacpp.NativeStreamingResult
import io.github.daniele21.localllm.llamacpp.RuntimeInitializationResult
import io.github.daniele21.localllm.llamacpp.StreamingCancelResult
import io.github.daniele21.localllm.llamacpp.materializedLoadMode
import io.github.daniele21.localllm.models.GgufModelProfile
import java.io.File
import java.security.MessageDigest

@Suppress("TooManyFunctions")
class LlamaCppInferenceBackend(
    private val nativeLibraryDir: File,
    private val lifecycleBridge: LlamaCppBridge = LlamaCppBridge(),
    private val generationBridge: LlamaCppGenerationBridge = LlamaCppGenerationBridge(),
    private val evaluationBatchBridge: LlamaCppEvaluationBatchBridge = LlamaCppEvaluationBatchBridge(),
    private val promptPlanningBridge: LlamaCppPromptPlanningBridge = LlamaCppPromptPlanningBridge(),
    private val streamingBridge: LlamaCppStreamingBridge = LlamaCppStreamingBridge(),
) : InferenceBackend,
    EvaluationBatchInferenceBackend {
    override val id: String = "llama.cpp"
    override val revision: String = "c1d0e7a004015f23bc0233470b747b596f29b264"

    @Volatile
    private var deviceInventoryFingerprint: String? = null

    @Volatile
    private var deviceInventoryAvailable: Boolean = false

    override fun initialize() {
        when (val result = lifecycleBridge.initializeRuntime(nativeLibraryDir)) {
            is RuntimeInitializationResult.Success -> {
                deviceInventoryAvailable = result.devices != null
                deviceInventoryFingerprint = result.devices?.let(::fingerprintDevices)
            }

            is RuntimeInitializationResult.Failure -> throw result.error.asBackendException()
        }
    }

    override fun shutdown() {
        when (val result = lifecycleBridge.shutdownRuntime()) {
            NativeOperationResult.Success -> {
                deviceInventoryFingerprint = null
                deviceInventoryAvailable = false
            }

            is NativeOperationResult.Failure -> throw result.error.asBackendException()
        }
    }

    override fun loadModel(source: BackendModelSource, profile: GgufModelProfile): BackendModelHandle {
        require(source.digest == profile.artifact.digest) {
            "Backend model source digest does not match profile ${profile.id}"
        }
        require(source.sizeBytes == profile.artifact.sizeBytes) {
            "Backend model source size does not match profile ${profile.id}"
        }
        return when (val result = lifecycleBridge.loadModel(source.file, profile)) {
            is ModelLoadResult.Success -> LlamaBackendModel(result.model)
            is ModelLoadResult.Failure -> throw result.error.asBackendException()
        }
    }

    override fun unloadModel(model: BackendModelHandle) {
        val nativeModel = model.requireLlamaModel()
        when (val result = lifecycleBridge.unloadModel(nativeModel.delegate)) {
            NativeOperationResult.Success -> Unit
            is NativeOperationResult.Failure -> throw result.error.asBackendException()
        }
    }

    override fun modelCapabilities(model: BackendModelHandle): BackendModelCapabilities {
        val nativeModel = model.requireLlamaModel()
        return when (val result = promptPlanningBridge.capabilities(nativeModel.delegate)) {
            is NativeModelCapabilitiesResult.Success -> BackendModelCapabilities(
                maximumContextTokens = result.capabilities.maximumContextTokens,
                supportsGrammar = result.capabilities.supportsGrammar,
                supportsReasoningTransition = true,
            )

            is NativeModelCapabilitiesResult.Failure -> throw result.error.asBackendException()
        }
    }

    override fun planPrompt(model: BackendModelHandle, request: BackendPromptPlanningRequest): BackendPromptPlan {
        val nativeModel = model.requireLlamaModel()
        return when (
            val result = promptPlanningBridge.plan(
                model = nativeModel.delegate,
                input = request.input,
                systemPrompt = request.systemPrompt,
                policy = request.chatTemplatePolicy,
                thinkingMode = request.thinkingMode,
            )
        ) {
            is NativePromptPlanningResult.Success -> BackendPromptPlan(
                prompt = result.plan.prompt,
                tokenCount = result.plan.tokenCount,
                chatTemplateId = result.plan.chatTemplateId,
                chatTemplateSource = result.plan.chatTemplateSource,
                stopTokenIds = result.plan.stopTokenIds,
                stopSequences = request.chatTemplatePolicy.stopSequences,
            )

            is NativePromptPlanningResult.Failure -> throw result.error.asBackendException()
        }
    }

    override fun createContext(
        model: BackendModelHandle,
        profile: GgufModelProfile,
        configuration: BackendContextConfiguration,
    ): BackendContextHandle {
        val nativeModel = model.requireLlamaModel()
        return when (val result = generationBridge.createContext(nativeModel.delegate, profile, configuration.contextSize)) {
            is ContextCreationResult.Success -> LlamaBackendContext(
                model = nativeModel,
                delegate = result.context,
                contextSize = configuration.contextSize,
                profile = profile,
            )

            is ContextCreationResult.Failure -> throw result.error.asBackendException()
        }
    }

    override fun createEvaluationBatchContext(
        model: BackendModelHandle,
        profile: GgufModelProfile,
        configuration: BackendEvaluationBatchContextConfiguration,
    ): BackendEvaluationBatchContextHandle {
        val nativeModel = model.requireLlamaModel()
        val aggregateContextSize = try {
            Math.multiplyExact(configuration.perSequenceContextSize, configuration.maxSequences)
        } catch (error: ArithmeticException) {
            throw BackendException("CONTEXT_OVERFLOW", "Evaluation aggregate context size overflow", error)
        }
        return when (
            val result = evaluationBatchBridge.createContext(
                model = nativeModel.delegate,
                profile = profile,
                perSequenceContextSize = configuration.perSequenceContextSize,
                maxSequences = configuration.maxSequences,
            )
        ) {
            is EvaluationContextCreationResult.Success -> LlamaBackendEvaluationContext(
                model = nativeModel,
                delegate = result.context,
                contextSize = aggregateContextSize,
                perSequenceContextSize = configuration.perSequenceContextSize,
                maxSequences = configuration.maxSequences,
                profile = profile,
            )

            is EvaluationContextCreationResult.Failure -> throw result.error.asBackendException()
        }
    }

    override fun executionEvidence(context: BackendContextHandle): BackendExecutionEvidence {
        val executionContext = when (context) {
            is LlamaBackendContext -> ExecutionContextIdentity(
                model = context.model,
                profile = context.profile,
                aggregateContextSize = context.contextSize,
                perSequenceContextSize = context.contextSize,
                sequenceWidth = 1,
                executionMode = EXECUTION_MODE_PRODUCTION,
            )

            is LlamaBackendEvaluationContext -> ExecutionContextIdentity(
                model = context.model,
                profile = context.profile,
                aggregateContextSize = context.contextSize,
                perSequenceContextSize = context.perSequenceContextSize,
                sequenceWidth = context.maxSequences,
                executionMode = EXECUTION_MODE_EVALUATION_MULTI_SEQUENCE,
            )

            else -> throw BackendException("BACKEND_MISMATCH", "Context handle was not created by the llama.cpp backend")
        }
        val requested = executionContext.model.delegate.requestedExecution
        val profile = executionContext.profile
        val canonical = listOf(
            "backend=$id",
            "revision=$revision",
            "profile=${profile.id}",
            "executionMode=${executionContext.executionMode}",
            "contextSize=${executionContext.aggregateContextSize}",
            "perSequenceContextSize=${executionContext.perSequenceContextSize}",
            "sequenceWidth=${executionContext.sequenceWidth}",
            "batchSize=${profile.batchSize}",
            "microBatchSize=${profile.microBatchSize}",
            "cpuThreads=${profile.cpuThreads}",
            "batchThreads=${profile.batchThreads}",
            "requestedGpuLayers=${requested.gpuLayers}",
            "requestedUseMmap=${requested.useMmap}",
            "requestedUseMlock=${requested.useMlock}",
            "materializedLoadMode=${requested.materializedLoadMode.name}",
            "flashAttention=${profile.flashAttention}",
            "kvCacheTypeK=${profile.kvCacheTypeK ?: UNAVAILABLE_VALUE}",
            "kvCacheTypeV=${profile.kvCacheTypeV ?: UNAVAILABLE_VALUE}",
            "deviceInventory=${if (deviceInventoryAvailable) deviceInventoryFingerprint else UNAVAILABLE_VALUE}",
            "effectivePlacement=${BackendExecutionFactAvailability.UNAVAILABLE.name}",
            "preparedPromptTokenReuse=EXACT_ONE_SHOT",
            "recurrentStateReuse=DISABLED",
        ).joinToString("\n")
        return BackendExecutionEvidence(
            backendId = id,
            backendRevision = revision,
            materialFingerprint = sha256(canonical),
            effectivePlacement = BackendExecutionFactAvailability.UNAVAILABLE,
        )
    }

    override fun releaseContext(context: BackendContextHandle) {
        val nativeContext = context.requireLlamaContext()
        when (val result = generationBridge.releaseContext(nativeContext.delegate)) {
            GenerationNativeOperationResult.Success -> Unit
            is GenerationNativeOperationResult.Failure -> throw result.error.asBackendException()
        }
    }

    override fun releaseEvaluationBatchContext(context: BackendEvaluationBatchContextHandle) {
        val nativeContext = context.requireLlamaEvaluationContext()
        when (val result = evaluationBatchBridge.releaseContext(nativeContext.delegate)) {
            GenerationNativeOperationResult.Success -> Unit
            is GenerationNativeOperationResult.Failure -> throw result.error.asBackendException()
        }
    }

    override fun generate(
        context: BackendContextHandle,
        request: BackendGenerationRequest,
        onChunk: (text: String, generatedTokens: Int) -> Boolean,
    ): BackendGenerationOutcome {
        val nativeContext = context.requireLlamaContext()
        val config = request.toNativeGenerationConfig()
        return when (
            val result = streamingBridge.generate(
                context = nativeContext.delegate,
                requestId = request.requestId,
                prompt = request.prompt,
                config = config,
                listener = NativeStreamingListener { chunk ->
                    onChunk(chunk.text, chunk.generatedTokens)
                },
            )
        ) {
            is NativeStreamingResult.Completed -> BackendGenerationOutcome.Completed(result.metrics.toBackendMetrics())
            is NativeStreamingResult.Cancelled -> BackendGenerationOutcome.Cancelled(result.metrics.toBackendMetrics())
            is NativeStreamingResult.Failure -> throw result.error.asBackendException()
        }
    }

    override fun generateEvaluationBatch(
        context: BackendEvaluationBatchContextHandle,
        requests: List<BackendGenerationRequest>,
    ): BackendEvaluationBatchResult {
        val nativeContext = context.requireLlamaEvaluationContext()
        val cases = requests.map { request ->
            NativeEvaluationBatchCase(
                requestId = request.requestId,
                prompt = request.prompt,
                config = request.toNativeGenerationConfig(),
            )
        }
        return when (val result = evaluationBatchBridge.generate(nativeContext.delegate, cases)) {
            is NativeEvaluationBatchResult.Completed -> BackendEvaluationBatchResult(
                result.cases.map { nativeCase ->
                    val metrics = BackendGenerationMetrics(
                        inputTokens = nativeCase.metrics.inputTokens,
                        outputTokens = nativeCase.metrics.outputTokens,
                        promptDurationMs = nativeCase.metrics.promptDurationMs,
                        generationDurationMs = nativeCase.metrics.generationDurationMs,
                        stopReason = StopReason.entries.firstOrNull { it.name == nativeCase.metrics.stopReason } ?: StopReason.UNKNOWN,
                    )
                    BackendEvaluationBatchCaseResult(
                        requestId = nativeCase.requestId,
                        output = nativeCase.output,
                        outcome = when (nativeCase.status) {
                            NativeEvaluationBatchCaseStatus.COMPLETED -> BackendGenerationOutcome.Completed(metrics)
                            NativeEvaluationBatchCaseStatus.CANCELLED -> BackendGenerationOutcome.Cancelled(metrics)
                        },
                    )
                },
            )

            is NativeEvaluationBatchResult.Failure -> throw result.error.asBackendException()
        }
    }

    override fun cancel(requestId: String): Boolean = when (val result = streamingBridge.cancel(requestId)) {
        is StreamingCancelResult.Accepted -> result.wasRunning
        is StreamingCancelResult.Failure -> throw result.error.asBackendException()
    }

    override fun cancelEvaluationCase(requestId: String): Boolean = when (val result = evaluationBatchBridge.cancel(requestId)) {
        is EvaluationBatchCancelResult.Accepted -> result.wasRunning
        is EvaluationBatchCancelResult.Failure -> throw result.error.asBackendException()
    }

    private fun BackendGenerationRequest.toNativeGenerationConfig(): NativeGenerationConfig = NativeGenerationConfig(
        maxOutputTokens = maxOutputTokens,
        temperature = temperature,
        topP = topP,
        topK = topK,
        minP = minP,
        presencePenalty = presencePenalty,
        repeatPenalty = repeatPenalty,
        repeatLastN = repeatLastN,
        seed = seed,
        outputConstraintType = outputConstraint.nativeType,
        outputSchema = (outputConstraint as? OutputConstraint.JsonSchema)?.schema,
        stopTokenIds = stopTokenIds.toIntArray(),
        stopSequences = stopSequences,
        reasoningMaxTokens = reasoningControl?.maxReasoningTokens,
        reasoningCloseMarker = reasoningControl?.closeMarker,
        reasoningForcedCloseText = reasoningControl?.forcedCloseText,
    )

    private fun fingerprintDevices(devices: List<NativeBackendDevice>): String = sha256(
        devices.sortedBy(NativeBackendDevice::index).joinToString("\n") { device ->
            listOf(
                device.index,
                device.type.name,
                device.name,
                device.description,
                device.memoryTotalBytes,
                device.capabilities.asynchronous,
                device.capabilities.hostBuffer,
                device.capabilities.bufferFromHostPointer,
                device.capabilities.events,
            ).joinToString("|")
        },
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

    private data class LlamaBackendModel(val delegate: LoadedNativeModel) : BackendModelHandle {
        override val digest = delegate.digest
        override val profileId = delegate.profileId
        override val loadDurationMs = delegate.loadDurationMs
    }

    private data class LlamaBackendContext(
        override val model: LlamaBackendModel,
        val delegate: LoadedNativeContext,
        override val contextSize: Int,
        val profile: GgufModelProfile,
    ) : BackendContextHandle

    private data class LlamaBackendEvaluationContext(
        override val model: LlamaBackendModel,
        val delegate: LoadedNativeEvaluationContext,
        override val contextSize: Int,
        override val perSequenceContextSize: Int,
        override val maxSequences: Int,
        val profile: GgufModelProfile,
    ) : BackendEvaluationBatchContextHandle

    private data class ExecutionContextIdentity(
        val model: LlamaBackendModel,
        val profile: GgufModelProfile,
        val aggregateContextSize: Int,
        val perSequenceContextSize: Int,
        val sequenceWidth: Int,
        val executionMode: String,
    )

    private fun BackendModelHandle.requireLlamaModel(): LlamaBackendModel = this as? LlamaBackendModel
        ?: throw BackendException("BACKEND_MISMATCH", "Model handle was not created by the llama.cpp backend")

    private fun BackendContextHandle.requireLlamaContext(): LlamaBackendContext = this as? LlamaBackendContext
        ?: throw BackendException("BACKEND_MISMATCH", "Context handle was not created by the llama.cpp production path")

    private fun BackendEvaluationBatchContextHandle.requireLlamaEvaluationContext(): LlamaBackendEvaluationContext =
        this as? LlamaBackendEvaluationContext
            ?: throw BackendException("BACKEND_MISMATCH", "Evaluation context handle was not created by the llama.cpp backend")

    private companion object {
        const val UNAVAILABLE_VALUE = "~"
        const val EXECUTION_MODE_PRODUCTION = "PRODUCTION_SINGLE_SEQUENCE"
        const val EXECUTION_MODE_EVALUATION_MULTI_SEQUENCE = "EVALUATION_MULTI_SEQUENCE"
    }
}

private fun io.github.daniele21.localllm.llamacpp.NativeStreamingMetrics.toBackendMetrics(): BackendGenerationMetrics =
    BackendGenerationMetrics(
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        promptDurationMs = promptDurationMs,
        generationDurationMs = generationDurationMs,
        stopReason = StopReason.entries.firstOrNull { it.name == stopReason } ?: StopReason.UNKNOWN,
        reasoningTokens = reasoningTokens,
        answerTokens = answerTokens,
    )

private val OutputConstraint.nativeType: String
    get() = when (this) {
        OutputConstraint.Text -> "TEXT"
        OutputConstraint.Json -> "JSON"
        is OutputConstraint.JsonSchema -> "JSON_SCHEMA"
    }

private fun io.github.daniele21.localllm.llamacpp.NativeRuntimeError.asBackendException(): BackendException =
    BackendException(code.name, message)

private fun io.github.daniele21.localllm.llamacpp.GenerationNativeError.asBackendException(): BackendException =
    BackendException(code.name, message)

private fun io.github.daniele21.localllm.llamacpp.StreamingNativeError.asBackendException(): BackendException =
    BackendException(code.name, message)

private fun io.github.daniele21.localllm.llamacpp.PromptPlanningNativeError.asBackendException(): BackendException =
    BackendException(code.name, message)
