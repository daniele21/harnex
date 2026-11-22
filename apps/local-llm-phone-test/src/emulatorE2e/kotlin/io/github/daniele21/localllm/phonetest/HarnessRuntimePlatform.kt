package io.github.daniele21.localllm.phonetest

import android.content.Context
import io.github.daniele21.localllm.catalog.CuratedModelCatalog
import io.github.daniele21.localllm.contracts.ChatTemplateSource
import io.github.daniele21.localllm.contracts.GenerationInput
import io.github.daniele21.localllm.contracts.ModelDigest
import io.github.daniele21.localllm.models.GgufArtifact
import io.github.daniele21.localllm.models.GgufModelProfile
import io.github.daniele21.localllm.models.Qwen35RuntimeTuningProfiles
import io.github.daniele21.localllm.runtime.ActivationResidencyCoordinator
import io.github.daniele21.localllm.runtime.ActivationResidencyInferenceBackend
import io.github.daniele21.localllm.runtime.BackendContextConfiguration
import io.github.daniele21.localllm.runtime.BackendContextHandle
import io.github.daniele21.localllm.runtime.BackendGenerationMetrics
import io.github.daniele21.localllm.runtime.BackendGenerationOutcome
import io.github.daniele21.localllm.runtime.BackendGenerationRequest
import io.github.daniele21.localllm.runtime.BackendModelCapabilities
import io.github.daniele21.localllm.runtime.BackendModelHandle
import io.github.daniele21.localllm.runtime.BackendModelSource
import io.github.daniele21.localllm.runtime.BackendPromptPlan
import io.github.daniele21.localllm.runtime.BackendPromptPlanningRequest
import io.github.daniele21.localllm.runtime.InferenceBackend
import io.github.daniele21.localllm.store.ModelImportErrorCode
import io.github.daniele21.localllm.store.ModelImportException
import io.github.daniele21.localllm.store.ModelStore
import io.github.daniele21.localllm.store.ModelStoreSnapshot
import io.github.daniele21.localllm.store.StoredModel
import io.github.daniele21.localllm.store.VerificationResult
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Emulator-only composition. The app, Service, Binder Host, Control Plane, activation/residency and
 * RuntimeOrchestrator are production code. Only GGUF bytes/native execution are deterministic.
 *
 * ModelStore reads intentionally mirror FileSystemModelStore semantics: presence is returned with
 * verified=false, while verify() is authoritative during RuntimeOrchestrator preparation. This is
 * required so the cross-app E2E catches Control Plane code that incorrectly treats read-time
 * `StoredModel.verified` as persisted integrity state.
 */
internal object HarnessRuntimePlatform {
    fun modelStore(context: Context): ModelStore = EmulatorE2eModelStore(context)

    fun backend(context: Context, activationResidency: ActivationResidencyCoordinator): InferenceBackend {
        @Suppress("UNUSED_VARIABLE")
        val unusedContext = context
        return ActivationResidencyInferenceBackend(
            delegate = DeterministicEmulatorInferenceBackend(),
            activationResidency = activationResidency,
        )
    }
}

private class EmulatorE2eModelStore(context: Context) : ModelStore {
    private val release = CuratedModelCatalog.releases.first()
    private val stored = StoredModel(
        digest = release.artifact.digest,
        file = File(context.noBackupFilesDir, "emulator-e2e-placeholder.gguf"),
        sizeBytes = release.artifact.sizeBytes,
        verified = false,
    )

    override fun find(digest: ModelDigest): StoredModel? =
        stored.takeIf { !EmulatorE2eModelAvailabilityGate.isUnavailable() && it.digest == digest }

    override fun import(source: File, artifact: GgufArtifact): StoredModel = throw ModelImportException(
        ModelImportErrorCode.INVALID_SOURCE,
        "The emulator E2E model store is pre-provisioned and immutable",
    )

    override fun verify(digest: ModelDigest): VerificationResult {
        if (EmulatorE2eModelAvailabilityGate.isUnavailable()) {
            return VerificationResult(
                valid = false,
                actualDigest = null,
                detail = "Emulator E2E model is intentionally unavailable",
            )
        }
        return VerificationResult(
            valid = digest == stored.digest,
            actualDigest = stored.digest.takeIf { digest == stored.digest },
            detail = if (digest == stored.digest) "Emulator E2E model identity is provisioned" else "Model is not provisioned",
        )
    }

    override fun remove(digest: ModelDigest): Boolean = false

    override fun snapshot(): ModelStoreSnapshot = if (EmulatorE2eModelAvailabilityGate.isUnavailable()) {
        ModelStoreSnapshot(modelCount = 0, totalBytes = 0L, entries = emptyList())
    } else {
        ModelStoreSnapshot(
            modelCount = 1,
            totalBytes = stored.sizeBytes,
            entries = listOf(stored),
        )
    }
}

private data class EmulatorE2eModelHandle(
    override val digest: ModelDigest,
    override val profileId: String,
    override val loadDurationMs: Long = 5,
) : BackendModelHandle

private data class EmulatorE2eContext(override val model: BackendModelHandle, override val contextSize: Int) : BackendContextHandle

private class DeterministicEmulatorInferenceBackend : InferenceBackend {
    override val id: String = "llama.cpp"
    override val revision: String = Qwen35RuntimeTuningProfiles.LLAMA_CPP_REVISION
    private val cancelledRequestIds = ConcurrentHashMap.newKeySet<String>()

    override fun initialize() = Unit

    override fun shutdown() {
        cancelledRequestIds.clear()
        EmulatorE2eGenerationGate.reset()
        EmulatorE2eBackendFailureGate.reset()
    }

    override fun loadModel(source: BackendModelSource, profile: GgufModelProfile): BackendModelHandle =
        EmulatorE2eModelHandle(source.digest, profile.id)

    override fun unloadModel(model: BackendModelHandle) = Unit

    override fun modelCapabilities(model: BackendModelHandle) = BackendModelCapabilities(
        maximumContextTokens = 4_096,
        supportsGrammar = true,
    )

    override fun planPrompt(model: BackendModelHandle, request: BackendPromptPlanningRequest): BackendPromptPlan {
        val input = EmulatorE2eAnalysisResponder.promptText(request.input)
        val prompt = listOfNotNull(request.systemPrompt, input).joinToString("\n")
        return BackendPromptPlan(
            prompt = prompt,
            tokenCount = (prompt.length / 4).coerceAtLeast(1),
            chatTemplateId = "crv-emulator-e2e",
            chatTemplateSource = ChatTemplateSource.FAMILY_FALLBACK,
        )
    }

    override fun createContext(
        model: BackendModelHandle,
        profile: GgufModelProfile,
        configuration: BackendContextConfiguration,
    ): BackendContextHandle = EmulatorE2eContext(model, configuration.contextSize)

    override fun releaseContext(context: BackendContextHandle) = Unit

    override fun generate(
        context: BackendContextHandle,
        request: BackendGenerationRequest,
        onChunk: (text: String, generatedTokens: Int) -> Boolean,
    ): BackendGenerationOutcome {
        if (
            !EmulatorE2eGenerationGate.awaitRelease {
                request.requestId in cancelledRequestIds
            }
        ) {
            cancelledRequestIds.remove(request.requestId)
            return BackendGenerationOutcome.Cancelled(
                BackendGenerationMetrics(
                    inputTokens = (request.prompt.length / 4).coerceAtLeast(1),
                    outputTokens = 0,
                    promptDurationMs = 2,
                    generationDurationMs = 0,
                ),
            )
        }

        check(!EmulatorE2eBackendFailureGate.consume()) {
            "Injected emulator E2E backend failure"
        }

        val output = EmulatorE2eAuraImportResponder.outputOrNull(request.prompt)
            ?: EmulatorE2eAnalysisResponder.output(request.prompt)
        val midpoint = (output.length / 2).coerceAtLeast(1)
        val chunks = listOf(output.substring(0, midpoint), output.substring(midpoint)).filter(String::isNotEmpty)
        var emitted = 0
        chunks.forEachIndexed { index, chunk ->
            if (request.requestId in cancelledRequestIds || !onChunk(chunk, index + 1)) {
                cancelledRequestIds.remove(request.requestId)
                return BackendGenerationOutcome.Cancelled(
                    BackendGenerationMetrics(
                        inputTokens = (request.prompt.length / 4).coerceAtLeast(1),
                        outputTokens = emitted,
                        promptDurationMs = 2,
                        generationDurationMs = 2,
                    ),
                )
            }
            emitted = index + 1
            Thread.sleep(75)
        }
        cancelledRequestIds.remove(request.requestId)
        return BackendGenerationOutcome.Completed(
            BackendGenerationMetrics(
                inputTokens = (request.prompt.length / 4).coerceAtLeast(1),
                outputTokens = emitted,
                promptDurationMs = 2,
                generationDurationMs = 150,
            ),
        )
    }

    override fun cancel(requestId: String): Boolean = cancelledRequestIds.add(requestId)
}

private object EmulatorE2eAnalysisResponder {
    private const val TEST_SURFACE = "Ada Lovelace"
    private val selectedType = Regex("\\\"selectedTypeIds\\\"\\s*:\\s*\\[\\s*\\\"([^\\\"]+)\\\"")
    private val segmentId = Regex("\\\"segmentId\\\"\\s*:\\s*\\\"(p[0-9]{4}-b[0-9]{4}(?:-f[0-9]{4})?)\\\"")

    fun promptText(input: GenerationInput): String = when (input) {
        is GenerationInput.Text -> input.value

        is GenerationInput.RawCompletion -> input.value

        is GenerationInput.Messages -> input.values.joinToString("\n") { it.content }

        // Emulator fakes only the deterministic text response, never native image inference.
        is GenerationInput.ImageQuestion -> input.question
    }

    fun output(prompt: String): String {
        val typeId = selectedType.find(prompt)?.groupValues?.get(1)
        val selectedSegmentId = segmentId.find(prompt)?.groupValues?.get(1)
        return if (typeId != null && selectedSegmentId != null && TEST_SURFACE in prompt) {
            "{\"schemaVersion\":1,\"findings\":[{\"typeId\":\"$typeId\",\"surface\":\"$TEST_SURFACE\",\"segmentId\":\"$selectedSegmentId\"}]}"
        } else {
            "{\"schemaVersion\":1,\"findings\":[]}"
        }
    }
}
