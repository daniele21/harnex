package io.github.daniele21.localllm.catalog

import io.github.daniele21.localllm.contracts.ApplicationId
import io.github.daniele21.localllm.contracts.UseCaseId
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CuratedModelCatalogTest {
    @Test
    fun curatedDocumentValidatesAndRoundTripsCanonicalPayload() {
        val document = CuratedModelCatalog.document(GENERATED_AT, EXPIRES_AT)

        val validation = CatalogValidator().validate(document, GENERATED_AT + 1)
        assertTrue(validation.violations.toString(), validation.valid)
        assertEquals(6L, document.revision)

        val codec = CatalogJsonCodec()
        val encoded = codec.encode(document) as CatalogEncodeResult.Success
        val reencoded = codec.encode(document) as CatalogEncodeResult.Success
        assertArrayEquals(encoded.bytes, reencoded.bytes)

        val decoded = codec.decode(encoded.bytes) as CatalogDecodeResult.Success
        assertEquals(document, decoded.document)
    }

    @Test
    fun exposesFourteenQwen35CandidateReleasesForThePhonePlayground() {
        val target =
            CatalogTarget(
                applicationId = ApplicationId("play-internal-phone-test"),
                useCaseId = UseCaseId("manual-inference-playground"),
            )
        val releases = CatalogQueries.releasesForTarget(CuratedModelCatalog.document(GENERATED_AT, EXPIRES_AT), target)

        assertEquals(EXPECTED_MODEL_IDS, releases.mapTo(linkedSetOf()) { it.id.modelId.value })
        assertTrue(releases.all { it.artifact.architecture == "qwen35" })
        assertTrue(releases.all { it.availability == CatalogAvailability.CANDIDATE })
        assertTrue(releases.all { it.artifact.quantization.isNotBlank() })
        assertTrue(releases.all { it.compatibility.minSdk == 26 })
        assertTrue(releases.all { it.compatibility.supportedAbis == setOf("arm64-v8a") })
        assertTrue(releases.all { it.compatibility.supportedBackendIds == setOf("llama.cpp") })
    }

    @Test
    fun embeddedLocalInferenceTargetExposesOnlyReviewedModelWithoutWideningPhoneTargets() {
        val releases =
            CatalogQueries.releasesForTarget(
                CuratedModelCatalog.document(GENERATED_AT, EXPIRES_AT),
                EmbeddedCatalogTargets.LOCAL_TEXT_GENERATION,
            )

        assertEquals(listOf("qwen35-2b-q4-k-m"), releases.map { it.id.modelId.value })
        assertTrue(releases.single().availability == CatalogAvailability.CANDIDATE)
        assertTrue(releases.single().allowedTargets.contains(EmbeddedCatalogTargets.LOCAL_TEXT_GENERATION))
        assertTrue(
            CuratedModelCatalog.releases.filterNot { it.id.modelId.value == "qwen35-2b-q4-k-m" }
                .none { EmbeddedCatalogTargets.LOCAL_TEXT_GENERATION in it.allowedTargets },
        )
    }

    @Test
    fun fourBReleaseSetContainsOnlyUnslothFourBitArtifacts() {
        val releases = CuratedModelCatalog.releases.filter { it.id.modelId.value.startsWith("qwen35-4b-") }

        assertEquals(SEVEN_FOUR_BIT_IDS, releases.mapTo(linkedSetOf()) { it.id.modelId.value })
        assertEquals(
            setOf("IQ4_XS", "IQ4_NL", "Q4_0", "Q4_1", "Q4_K_S", "Q4_K_M", "UD-Q4_K_XL"),
            releases.mapTo(linkedSetOf()) { it.artifact.quantization },
        )
        assertTrue(releases.all { it.compatibility.minRamBytes == 7_000_000_000 })
        assertTrue(releases.all { it.compatibility.recommendedRamBytes == 12_000_000_000 })
        assertTrue(releases.all { it.artifact.downloadUri.toString().contains("e87f176479d0855a907a41277aca2f8ee7a09523") })
    }

    @Test
    fun fourBAdmissionUsesAndroidReportedRamFloorWithoutTreatingEightGbAsExactBytes() {
        val release = CuratedModelCatalog.releases.first { it.id.modelId.value == "qwen35-4b-q4-k-m" }
        val target =
            CatalogTarget(
                applicationId = ApplicationId("play-internal-phone-test"),
                useCaseId = UseCaseId("manual-inference-playground"),
            )
        val evaluator =
            CatalogCompatibilityEvaluator(
                versionMatcher = AcceptingVersionMatcher,
                profileResolver = AcceptingProfileResolver,
            )

        val compatible = evaluator.evaluate(
            release = release,
            target = target,
            device = fourBDevice(totalMemoryBytes = 7_500_000_000),
        )
        val incompatible = evaluator.evaluate(
            release = release,
            target = target,
            device = fourBDevice(totalMemoryBytes = 6_900_000_000),
        )

        assertTrue(compatible.reasons.toString(), compatible.compatible)
        assertTrue(compatible.warnings.contains(CatalogCompatibilityWarning.RAM_BELOW_RECOMMENDED))
        assertFalse(incompatible.compatible)
        assertTrue(incompatible.reasons.contains(CatalogCompatibilityReason.INSUFFICIENT_RAM))
    }

    @Test
    fun candidateReleaseRemainsSelectableWithExplicitWarning() {
        val target =
            CatalogTarget(
                applicationId = ApplicationId("play-internal-phone-test"),
                useCaseId = UseCaseId("manual-inference-playground"),
            )
        val evaluator =
            CatalogCompatibilityEvaluator(
                versionMatcher = AcceptingVersionMatcher,
                profileResolver = AcceptingProfileResolver,
            )

        val result =
            evaluator.evaluate(
                release = CuratedModelCatalog.releases.first(),
                target = target,
                device =
                CatalogDeviceProfile(
                    sdkInt = 36,
                    supportedAbis = setOf("arm64-v8a"),
                    totalMemoryBytes = 8_000_000_000,
                    availableStorageBytes = 4_000_000_000,
                    harnessVersion = "0.3.0",
                    backendId = "llama.cpp",
                ),
            )

        assertTrue(result.reasons.toString(), result.compatible)
        assertEquals(listOf(CatalogCompatibilityWarning.RELEASE_CANDIDATE), result.warnings)
        assertFalse(result.reasons.contains(CatalogCompatibilityReason.RELEASE_UNAVAILABLE))
    }

    private fun fourBDevice(totalMemoryBytes: Long): CatalogDeviceProfile = CatalogDeviceProfile(
        sdkInt = 36,
        supportedAbis = setOf("arm64-v8a"),
        totalMemoryBytes = totalMemoryBytes,
        availableStorageBytes = 10_000_000_000,
        harnessVersion = "0.3.0",
        backendId = "llama.cpp",
    )

    private object AcceptingVersionMatcher : CatalogVersionMatcher {
        override fun isInRange(currentVersion: String, minimumInclusive: String?, maximumExclusive: String?): Boolean = true
    }

    private object AcceptingProfileResolver : CatalogProfileResolver {
        override fun supports(profileKey: ModelProfileKey, target: CatalogTarget): Boolean = true
    }

    private companion object {
        const val GENERATED_AT = 1_800_000_000_000
        const val EXPIRES_AT = 1_800_086_400_000
        val SEVEN_FOUR_BIT_IDS =
            setOf(
                "qwen35-4b-ud-q4-k-xl",
                "qwen35-4b-q4-k-m",
                "qwen35-4b-q4-k-s",
                "qwen35-4b-iq4-xs",
                "qwen35-4b-iq4-nl",
                "qwen35-4b-q4-0",
                "qwen35-4b-q4-1",
            )
        val EXPECTED_MODEL_IDS =
            setOf(
                "qwen35-08b-q4-k-m",
                "qwen35-08b-q5-k-m",
                "qwen35-08b-q8-0",
                "qwen35-08b-ud-iq2-xxs",
                "qwen35-2b-q4-k-m",
                "qwen35-2b-q5-k-m",
                "qwen35-2b-ud-iq2-xxs",
            ) + SEVEN_FOUR_BIT_IDS
    }
}
