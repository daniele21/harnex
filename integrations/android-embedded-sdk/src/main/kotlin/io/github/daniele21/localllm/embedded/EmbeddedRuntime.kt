package io.github.daniele21.localllm.embedded

import android.content.Context
import android.os.Looper
import io.github.daniele21.localllm.catalog.CuratedModelCatalog
import io.github.daniele21.localllm.catalog.EmbeddedCatalogTargets
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
import io.github.daniele21.localllm.download.AllowedSourceHost
import io.github.daniele21.localllm.download.AllowlistedHttpsSourcePolicy
import io.github.daniele21.localllm.download.DownloadCancellationToken
import io.github.daniele21.localllm.download.DownloadProgressObserver
import io.github.daniele21.localllm.download.FileSystemVerifiedDownloadAccess
import io.github.daniele21.localllm.download.ModelDownloadResult
import io.github.daniele21.localllm.download.NeverCancelled
import io.github.daniele21.localllm.download.SecureModelDownloader
import io.github.daniele21.localllm.download.VerifiedDownloadHandle
import io.github.daniele21.localllm.install.ModelInstallationObserver
import io.github.daniele21.localllm.install.ModelInstallationResult
import io.github.daniele21.localllm.install.ResolvedInstallationProfile
import io.github.daniele21.localllm.install.VerifiedModelInstaller
import io.github.daniele21.localllm.llamacpp.LlamaCppGgufArtifactInspector
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
    private val visionProjector = EmbeddedVisionProjector(context)
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
            backend = LlamaCppInferenceBackend(
                nativeLibraryDir = File(context.applicationInfo.nativeLibraryDir),
                visionProjectorFile = visionProjector.file,
            ),
        )
    private val client: LocalLlmClient = InProcessLocalLlmClient(runtime)

    /** Optional multimodal capability keeps projector lifetime out of the text facade. */
    val vision: EmbeddedVisionCapability by lazy {
        EmbeddedVisionCapability(
            projector = visionProjector,
            client = client,
            applicationId = applicationId,
            useCaseId = useCaseId,
            verifyOwner = {
                requireWorkerThread()
                checkOpen()
            },
            isClosed = { closed.get() },
        )
    }

    private val provisioning by lazy {
        val release = CuratedModelCatalog.releases.single { it.id.modelId.value == EmbeddedModelPolicy.INITIAL_MODEL_ID }
        require(EmbeddedCatalogTargets.LOCAL_TEXT_GENERATION in release.allowedTargets) {
            "Model is not authorized for embedded installation"
        }
        val downloadRoot = File(context.noBackupFilesDir, VERIFIED_DOWNLOAD_DIRECTORY)
        val downloads =
            SecureModelDownloader(
                rootDirectory = downloadRoot,
                sourcePolicy =
                AllowlistedHttpsSourcePolicy(
                    setOf(
                        AllowedSourceHost("huggingface.co", includeSubdomains = true),
                        AllowedSourceHost("hf.co", includeSubdomains = true),
                    ),
                ),
            )
        downloads.recoverInterruptedDownloads()
        val verifiedAccess = FileSystemVerifiedDownloadAccess(downloadRoot)
        val installer =
            VerifiedModelInstaller(
                stagingDirectory = File(context.cacheDir, INSTALL_STAGING_DIRECTORY),
                verifiedDownloads = verifiedAccess,
                inspector = LlamaCppGgufArtifactInspector(),
                modelStore = modelStore,
            )
        EmbeddedModelProvisioning(
            release = release,
            target = EmbeddedCatalogTargets.LOCAL_TEXT_GENERATION,
            profile = ResolvedInstallationProfile(release.profileKey, resolved.model.artifact),
            download = downloads::download,
            install = installer::install,
        )
    }

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

    /**
     * Verified HTTPS transfer to app-private storage. The result's handle is needed by installModel.
     * May block for a large GGUF: call on a worker thread. Closing the SDK cancels the transfer.
     */
    fun downloadModel(
        observer: DownloadProgressObserver = DownloadProgressObserver {},
        cancellationToken: DownloadCancellationToken = NeverCancelled,
    ): ModelDownloadResult = downloadModelInternal(observer, cancellationToken, false)

    /** Long-running Android-managed transfers retain untrusted partial bytes after system stops. */
    fun downloadModelResumable(
        observer: DownloadProgressObserver = DownloadProgressObserver {},
        cancellationToken: DownloadCancellationToken = NeverCancelled,
    ): ModelDownloadResult = downloadModelInternal(observer, cancellationToken, true)

    private fun downloadModelInternal(
        observer: DownloadProgressObserver,
        cancellationToken: DownloadCancellationToken,
        preservePartialOnCancel: Boolean,
    ): ModelDownloadResult {
        requireWorkerThread()
        checkOpen()
        return provisioning.download(
            observer,
            DownloadCancellationToken { closed.get() || cancellationToken.isCancelled() },
            preservePartialOnCancel,
        )
    }

    /** Revalidate GGUF metadata, import atomically, and check SHA-256 before prepare(). */
    fun installModel(
        handle: VerifiedDownloadHandle,
        observer: ModelInstallationObserver = ModelInstallationObserver {},
    ): ModelInstallationResult {
        requireWorkerThread()
        checkOpen()
        return provisioning.install(handle, observer)
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
        internal const val VERIFIED_DOWNLOAD_DIRECTORY = "harnex-embedded-verified-downloads"
        internal const val INSTALL_STAGING_DIRECTORY = "harnex-embedded-install-staging"

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
