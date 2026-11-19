package io.github.daniele21.localllm.embedded

import io.github.daniele21.localllm.catalog.CatalogModelRelease
import io.github.daniele21.localllm.catalog.CatalogTarget
import io.github.daniele21.localllm.download.DownloadCancellationToken
import io.github.daniele21.localllm.download.DownloadProgressObserver
import io.github.daniele21.localllm.download.ModelDownloadRequest
import io.github.daniele21.localllm.download.ModelDownloadResult
import io.github.daniele21.localllm.download.VerifiedDownloadHandle
import io.github.daniele21.localllm.install.ModelInstallationObserver
import io.github.daniele21.localllm.install.ModelInstallationRequest
import io.github.daniele21.localllm.install.ModelInstallationResult
import io.github.daniele21.localllm.install.ResolvedInstallationProfile

/**
 * Binds the reviewed embedded catalog release to the existing verified downloader and installer.
 * Download and installation deliberately remain separate; no model selection or transfer policy
 * is reimplemented here.
 */
internal class EmbeddedModelProvisioning(
    private val release: CatalogModelRelease,
    private val target: CatalogTarget,
    private val profile: ResolvedInstallationProfile,
    private val download: (ModelDownloadRequest, DownloadProgressObserver, DownloadCancellationToken) -> ModelDownloadResult,
    private val install: (ModelInstallationRequest, ModelInstallationObserver) -> ModelInstallationResult,
) {
    fun download(observer: DownloadProgressObserver, cancellationToken: DownloadCancellationToken): ModelDownloadResult =
        download(ModelDownloadRequest(release.artifact), observer, cancellationToken)

    fun install(handle: VerifiedDownloadHandle, observer: ModelInstallationObserver): ModelInstallationResult = install(
        ModelInstallationRequest(
            handle = handle,
            release = release,
            target = target,
            profile = profile,
        ),
        observer,
    )
}
