package io.github.daniele21.localllm.embedded

import io.github.daniele21.localllm.catalog.CuratedModelCatalog
import io.github.daniele21.localllm.catalog.EmbeddedCatalogTargets
import io.github.daniele21.localllm.download.DownloadCancellationToken
import io.github.daniele21.localllm.download.DownloadFailure
import io.github.daniele21.localllm.download.DownloadFailureCode
import io.github.daniele21.localllm.download.DownloadOperationId
import io.github.daniele21.localllm.download.DownloadProgressObserver
import io.github.daniele21.localllm.download.ModelDownloadResult
import io.github.daniele21.localllm.download.VerifiedDownloadHandle
import io.github.daniele21.localllm.install.ModelInstallationFailureCode
import io.github.daniele21.localllm.install.ModelInstallationId
import io.github.daniele21.localllm.install.ModelInstallationObserver
import io.github.daniele21.localllm.install.ModelInstallationRequest
import io.github.daniele21.localllm.install.ModelInstallationResult
import io.github.daniele21.localllm.install.ResolvedInstallationProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedModelProvisioningTest {
    private val release =
        CuratedModelCatalog.releases.single { it.id.modelId.value == EmbeddedModelPolicy.INITIAL_MODEL_ID }
    private val profile =
        EmbeddedModelPolicy.resolve(EmbeddedConfiguration(EmbeddedModelPolicy.INITIAL_MODEL_ID), 8)

    @Test
    fun downloadsOnlyTheCatalogArtifactAndForwardsCancellation() {
        val expected = ModelDownloadResult.Failure(
            DownloadOperationId("test-download"),
            DownloadFailure(DownloadFailureCode.NETWORK_FAILURE, true, "synthetic"),
        )
        val cancellation = DownloadCancellationToken { true }
        var observedCancellation: DownloadCancellationToken? = null
        val provisioning =
            EmbeddedModelProvisioning(
                release = release,
                target = EmbeddedCatalogTargets.LOCAL_TEXT_GENERATION,
                profile = ResolvedInstallationProfile(release.profileKey, profile.model.artifact),
                download = { request, _, token ->
                    assertEquals(release.artifact, request.artifact)
                    observedCancellation = token
                    expected
                },
                install = { _, _ -> error("Install must remain explicit") },
            )

        assertSame(expected, provisioning.download(DownloadProgressObserver {}, cancellation))
        assertSame(cancellation, observedCancellation)
        assertTrue(observedCancellation!!.isCancelled())
    }

    @Test
    fun installsOnlyTheReviewedTargetAndResolvedArtifact() {
        val handle = VerifiedDownloadHandle(release.artifact.digest.sha256)
        val expected = ModelInstallationResult.Failure(
            ModelInstallationId("test-install"),
            ModelInstallationFailureCode.VERIFIED_DOWNLOAD_UNAVAILABLE,
            "synthetic",
        )
        var requested: ModelInstallationRequest? = null
        val provisioning =
            EmbeddedModelProvisioning(
                release = release,
                target = EmbeddedCatalogTargets.LOCAL_TEXT_GENERATION,
                profile = ResolvedInstallationProfile(release.profileKey, profile.model.artifact),
                download = { _, _, _ -> error("Download must remain explicit") },
                install = { request, _ ->
                    requested = request
                    expected
                },
            )

        assertSame(expected, provisioning.install(handle, ModelInstallationObserver {}))
        assertEquals(handle, requested!!.handle)
        assertEquals(release, requested!!.release)
        assertEquals(EmbeddedCatalogTargets.LOCAL_TEXT_GENERATION, requested!!.target)
        assertEquals(release.profileKey, requested!!.profile.key)
        assertEquals(profile.model.artifact, requested!!.profile.artifact)
    }
}
