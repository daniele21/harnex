package io.github.daniele21.localllm.embedded

import io.github.daniele21.localllm.contracts.ApplicationId
import io.github.daniele21.localllm.contracts.GenerationHandle
import io.github.daniele21.localllm.contracts.GenerationInput
import io.github.daniele21.localllm.contracts.GenerationListener
import io.github.daniele21.localllm.contracts.GenerationOverrides
import io.github.daniele21.localllm.contracts.GenerationRequest
import io.github.daniele21.localllm.contracts.LocalLlmClient
import io.github.daniele21.localllm.contracts.RequestId
import io.github.daniele21.localllm.contracts.SessionId
import io.github.daniele21.localllm.contracts.UseCaseId
import io.github.daniele21.localllm.download.DownloadCancellationToken
import io.github.daniele21.localllm.download.DownloadProgressObserver
import io.github.daniele21.localllm.download.NeverCancelled
import java.util.UUID

/**
 * Vision opt-in on the existing embedded model/session. No second inference engine
 * or persistent chat state; the enclosing EmbeddedRuntime remains the lifecycle owner.
 */
class EmbeddedVisionCapability internal constructor(
    private val projector: EmbeddedVisionProjector,
    private val client: LocalLlmClient,
    private val applicationId: ApplicationId,
    private val useCaseId: UseCaseId,
    private val verifyOwner: () -> Unit,
    private val isClosed: () -> Boolean,
) {
    private var projectorVerified = false

    fun readiness(): Boolean {
        verifyOwner()
        projectorVerified = projector.verified()
        return projectorVerified
    }

    fun prepare(
        observer: DownloadProgressObserver = DownloadProgressObserver {},
        cancellationToken: DownloadCancellationToken = NeverCancelled,
    ) {
        verifyOwner()
        projector.ensureReady(
            cancel = DownloadCancellationToken { isClosed() || cancellationToken.isCancelled() },
            progress = observer,
        )
        projectorVerified = projector.verified()
        check(projectorVerified) { "Vision projector is not verified" }
    }

    fun generate(
        sessionId: SessionId,
        question: String,
        imageBytes: ByteArray,
        listener: GenerationListener,
        overrides: GenerationOverrides = GenerationOverrides(),
    ): GenerationHandle {
        verifyOwner()
        check(projectorVerified) { "Prepare the verified projector before sending an image" }
        return client.generate(
            GenerationRequest(
                requestId = RequestId(UUID.randomUUID().toString()),
                sessionId = sessionId,
                applicationId = applicationId,
                useCaseId = useCaseId,
                input = GenerationInput.ImageQuestion(question, imageBytes),
                overrides = overrides,
            ),
            listener,
        )
    }
}
