package io.github.daniele21.localllm.catalog

import io.github.daniele21.localllm.contracts.ApplicationId
import io.github.daniele21.localllm.contracts.ModelDigest
import io.github.daniele21.localllm.contracts.UseCaseId
import java.net.URI

internal object CuratedModelReleaseFactory {
    private val phoneTestApplicationId = ApplicationId("play-internal-phone-test")
    private val phoneTestUseCases =
        setOf(
            "manual-inference-playground",
            "physical-device-validation",
        )

    @Suppress("LongParameterList")
    fun release(
        modelId: String,
        displayName: String,
        description: String,
        downloadUrl: String,
        sha256: String,
        sizeBytes: Long,
        fileName: String,
        architecture: String,
        quantization: String,
        minRamBytes: Long,
        recommendedRamBytes: Long,
        profileKey: String,
        licenseId: String,
        sourceUrl: String,
        useCases: Set<String>,
        allowEmbeddedLocalTextGeneration: Boolean = false,
    ): CatalogModelRelease = CatalogModelRelease(
        id =
        CatalogReleaseId(
            modelId = CatalogModelId(modelId),
            version = CatalogModelVersion(MODEL_VERSION),
        ),
        displayName = displayName,
        description = description,
        artifact =
        CatalogGgufArtifact(
            digest = ModelDigest(sha256),
            sizeBytes = sizeBytes,
            downloadUri = URI(downloadUrl),
            architecture = architecture,
            quantization = quantization,
            fileName = fileName,
        ),
        compatibility =
        CatalogCompatibility(
            minSdk = MIN_ANDROID_API,
            supportedAbis = setOf(ARM64_ABI),
            minRamBytes = minRamBytes,
            recommendedRamBytes = recommendedRamBytes,
            minFreeStorageBytes = 0,
            supportedBackendIds = setOf(LLAMA_CPP_BACKEND),
        ),
        availability = CatalogAvailability.CANDIDATE,
        allowedTargets =
        (useCases + phoneTestUseCases).mapTo(linkedSetOf()) { useCase ->
            CatalogTarget(phoneTestApplicationId, UseCaseId(useCase))
        }.apply {
            if (allowEmbeddedLocalTextGeneration) add(EmbeddedCatalogTargets.LOCAL_TEXT_GENERATION)
        },
        profileKey = ModelProfileKey(profileKey),
        license =
        CatalogLicense(
            id = licenseId,
            displayName = licenseId,
            sourceUri = URI(sourceUrl),
        ),
    )

    private const val MODEL_VERSION = "1.0.0"
    private const val MIN_ANDROID_API = 26
    private const val ARM64_ABI = "arm64-v8a"
    private const val LLAMA_CPP_BACKEND = "llama.cpp"
}
