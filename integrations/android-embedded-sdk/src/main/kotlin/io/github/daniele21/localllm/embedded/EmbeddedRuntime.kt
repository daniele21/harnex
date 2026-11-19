package io.github.daniele21.localllm.embedded

import android.content.Context
import android.os.Looper
import io.github.daniele21.localllm.contracts.ApplicationId
import io.github.daniele21.localllm.contracts.ConversationMessage
import io.github.daniele21.localllm.contracts.GenerationHandle
import io.github.daniele21.localllm.contracts.GenerationInput
import io.github.daniele21.localllm.contracts.GenerationListener
import io.github.daniele21.localllm.contracts.GenerationOverrides
import io.github.daniele21.localllm.contracts.GenerationRequest
import io.github.daniele21.localllm.contracts.LocalLlmClient
import io.github.daniele21.localllm.contracts.PrepareResult
import io.github.daniele21.localllm.contracts.RequestId
import io.github.daniele21.localllm.contracts.SessionId
import io.github.daniele21.localllm.contracts.SessionKind
import io.github.daniele21.localllm.contracts.SessionOptions
import io.github.daniele21.localllm.contracts.UseCaseId
import io.github.daniele21.localllm.models.ModelProfileRegistry
import io.github.daniele21.localllm.models.ResolvedUseCase
import io.github.daniele21.localllm.runtime.LlamaCppInferenceBackend
import io.github.daniele21.localllm.runtime.RuntimeOrchestrator
import io.github.daniele21.localllm.store.FileSystemModelStore
import io.github.daniele21.localllm.transport.InProcessLocalLlmClient
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Configuration for an in-process Android inference engine. Model choice is explicit; Harnex
 * reviews the permitted model/profile pair. This does not represent a chat conversation.
 */
data class EmbeddedConfiguration(val modelId: String, val systemInstructions: String? = null) {
    init {
        require(modelId.isNotBlank()) { "Embedded model ID must not be blank" }
        require(
            systemInstructions == null ||
                (
                    systemInstructions.isNotBlank() && systemInstructions.length <= MAX_SYSTEM_INSTRUCTIONS &&
                        '\u0000' !in systemInstructions
                ),
        ) {
            "Embedded instructions must be nonblank, bounded and NUL-free"
        }
    }

    private companion object {
        const val MAX_SYSTEM_INSTRUCTIONS = 4_096
    }
}

enum class EmbeddedModelReadiness {
    NOT_INSTALLED,
    INVALID,
    VERIFIED,
}

/**
 * Bounded in-process lifetime owner over the same production RuntimeOrchestrator used by the Host.
 *
 * Calls involving disk, GGUF inspection, native runtime or generation must run off Android's main
 * thread. Callbacks can arrive from runtime threads; the application dispatches them to its UI.
 * The application owns its conversation/history and sends an explicit message list for every turn.
 */
class EmbeddedRuntime private constructor(context: Context, private val resolved: ResolvedUseCase) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val modelStore = FileSystemModelStore(File(context.noBackupFilesDir, MODEL_DIRECTORY))
    private val registry =
        object : ModelProfileRegistry {
            override fun resolve(applicationId: ApplicationId, useCaseId: UseCaseId): ResolvedUseCase {
                require(applicationId == resolved.binding.applicationId && useCaseId == resolved.binding.useCaseId) {
                    "Unknown embedded inference binding"
                }
                return resolved
            }
        }
    private val runtime =
        RuntimeOrchestrator(
            registry = registry,
            modelStore = modelStore,
            backend = LlamaCppInferenceBackend(File(context.applicationInfo.nativeLibraryDir)),
        )
    private val client: LocalLlmClient = InProcessLocalLlmClient(runtime)

    val modelId: String = EmbeddedModelPolicy.INITIAL_MODEL_ID
    val applicationId: ApplicationId = resolved.binding.applicationId
    val useCaseId: UseCaseId = resolved.binding.useCaseId

    fun modelReadiness(): EmbeddedModelReadiness {
        requireWorkerThread()
        checkOpen()
        val digest = resolved.model.artifact.digest
        if (modelStore.find(digest) == null) return EmbeddedModelReadiness.NOT_INSTALLED
        return if (modelStore.verify(digest).valid) EmbeddedModelReadiness.VERIFIED else EmbeddedModelReadiness.INVALID
    }

    fun prepare(): PrepareResult {
        requireWorkerThread()
        checkOpen()
        return client.prepare(applicationId, useCaseId)
    }

    /** Native context allocation is lazy; do not treat this technical session as chat history. */
    fun openSession(): SessionId {
        requireWorkerThread()
        checkOpen()
        return client.createSession(applicationId, useCaseId, SessionOptions(kind = SessionKind.STATELESS))
    }

    /** Input is the complete bounded history selected by the consumer for this single request. */
    fun generate(
        sessionId: SessionId,
        messages: List<ConversationMessage>,
        listener: GenerationListener,
        overrides: GenerationOverrides = GenerationOverrides(),
    ): GenerationHandle {
        requireWorkerThread()
        checkOpen()
        return client.generate(
            GenerationRequest(
                requestId = RequestId(UUID.randomUUID().toString()),
                sessionId = sessionId,
                applicationId = applicationId,
                useCaseId = useCaseId,
                input = GenerationInput.Messages(messages),
                overrides = overrides,
            ),
            listener,
        )
    }

    fun closeSession(sessionId: SessionId) {
        requireWorkerThread()
        checkOpen()
        client.closeSession(sessionId)
    }

    override fun close() {
        requireWorkerThread()
        if (closed.compareAndSet(false, true)) runtime.close()
    }

    private fun checkOpen() {
        check(!closed.get()) { "Embedded runtime is closed" }
    }

    companion object {
        internal const val MODEL_DIRECTORY = "harnex-embedded-models"

        /** No Binder service, Host installation, Host authorization or prompt-content audit store. */
        fun open(context: Context, configuration: EmbeddedConfiguration): EmbeddedRuntime {
            requireWorkerThread()
            val resolved = EmbeddedModelPolicy.resolve(configuration, Runtime.getRuntime().availableProcessors())
            return EmbeddedRuntime(context.applicationContext, resolved)
        }

        private fun requireWorkerThread() {
            val main = Looper.getMainLooper()
            check(main == null || Looper.myLooper() != main) {
                "Harnex embedded work must run off the Android main thread"
            }
        }
    }
}
