package io.github.daniele21.localllm.phonetest

import io.github.daniele21.localllm.contracts.ApplicationId
import io.github.daniele21.localllm.contracts.InferencePresetId
import io.github.daniele21.localllm.contracts.InferencePresetRef
import io.github.daniele21.localllm.contracts.UseCaseId
import io.github.daniele21.localllm.models.AppModelBinding
import io.github.daniele21.localllm.models.OutputMode
import io.github.daniele21.localllm.models.ResolvedUseCase

/** Host-owned identities and fixed use-case bindings for external shared-runtime clients. */
internal object HarnessSharedRuntimeBindings {
    /** Temporary legacy identity retained only through the RedactGuard cross-repository cutover. */
    val consoleApplicationId = ApplicationId("local-llm-console")

    /** Independent product identity used by daniele21/redactguard-android. */
    val redactGuardApplicationId = ApplicationId("redactguard")

    /** Independent product identity used by daniele21/personal-budget (Aura Finance). */
    val auraApplicationId = ApplicationId("aura-finance")

    /** Legacy surface retained only until the in-repo Console/OMBRA consumer is removed. */
    val consoleUseCaseId = UseCaseId("console-inference-playground")

    /** Host-owned PII-analysis use case. Consumers never provide model identity. */
    val ombraUseCaseId = UseCaseId("document-pii-detection")

    /** Host-owned bounded text-generation use case for general Consumer SDK workflows. */
    val genericTextUseCaseId = UseCaseId("generic-text-generation")

    /** Host-owned Aura spreadsheet schema-selection use case. */
    val auraSchemaInferenceUseCaseId = UseCaseId("aura-transaction-schema-inference")

    /** Host-owned Aura transaction category-selection use case. */
    val auraCategoryClassificationUseCaseId = UseCaseId("aura-transaction-category-classification")

    val ombraDefaultPreset =
        InferencePresetRef(InferencePresetId("qwen35-json"), PHONE_INFERENCE_PRESET_VERSION)
    val genericTextDefaultPreset =
        InferencePresetRef(InferencePresetId("qwen35-text-quality"), PHONE_INFERENCE_PRESET_VERSION)
    val auraDefaultPreset =
        InferencePresetRef(InferencePresetId("qwen35-json"), PHONE_INFERENCE_PRESET_VERSION)

    const val HOST_RELEASE_PACKAGE = "io.github.daniele21.localllm.phonetest"
    const val HOST_DEBUG_PACKAGE = "io.github.daniele21.localllm.phonetest.debug"
    const val CONSOLE_RELEASE_PACKAGE = "io.github.daniele21.localllm.console"
    const val CONSOLE_DEBUG_PACKAGE = "io.github.daniele21.localllm.console.debug"
    const val CONSOLE_INTERNAL_PACKAGE = "io.github.daniele21.localllm.console.internal"
    const val REDACTGUARD_RELEASE_PACKAGE = "io.github.daniele21.redactguard"
    const val REDACTGUARD_DEBUG_PACKAGE = "io.github.daniele21.redactguard.debug"
    const val AURA_RELEASE_PACKAGE = "com.staituned.aura"
    const val AURA_DEBUG_PACKAGE = "com.staituned.aura.debug"
    const val SR6_RELEASE_CONSUMER_PACKAGE = "io.github.daniele21.localllm.consumerfixture"

    val consoleUseCases: Set<UseCaseId> = setOf(consoleUseCaseId, ombraUseCaseId, genericTextUseCaseId)
    val redactGuardUseCases: Set<UseCaseId> = setOf(ombraUseCaseId)
    val auraUseCases: Set<UseCaseId> = setOf(auraSchemaInferenceUseCaseId, auraCategoryClassificationUseCaseId)
    val piiConsumerApplicationIds: Set<ApplicationId> = setOf(consoleApplicationId, redactGuardApplicationId)

    /**
     * Selects peer package identities from the exact installed Host package, not from debuggability.
     *
     * The release-identity emulator topology is intentionally debuggable while using the production Host package.
     * Treating BuildConfig.DEBUG as package identity would make that topology observe the wrong consumer package.
     */
    fun usesDebugClientPackageTopology(hostPackageName: String): Boolean = when (hostPackageName) {
        HOST_DEBUG_PACKAGE -> true
        HOST_RELEASE_PACKAGE -> false
        else -> error("Unsupported Harnex host package identity: $hostPackageName")
    }

    fun consolePackages(debugHost: Boolean): Set<String> = if (debugHost) {
        setOf(CONSOLE_DEBUG_PACKAGE, CONSOLE_INTERNAL_PACKAGE)
    } else {
        setOf(CONSOLE_RELEASE_PACKAGE)
    }

    fun redactGuardPackages(debugHost: Boolean): Set<String> = if (debugHost) {
        setOf(REDACTGUARD_DEBUG_PACKAGE)
    } else {
        setOf(REDACTGUARD_RELEASE_PACKAGE)
    }

    fun auraPackages(debugHost: Boolean): Set<String> = if (debugHost) {
        setOf(AURA_DEBUG_PACKAGE)
    } else {
        setOf(AURA_RELEASE_PACKAGE)
    }

    fun modelProfileId(useCaseId: String, catalogProfileKey: String): String? {
        require(catalogProfileKey.isNotBlank()) { "Catalog profile key must not be blank" }
        val suffix = when (useCaseId) {
            consoleUseCaseId.value -> CONSOLE_PROFILE_SUFFIX
            ombraUseCaseId.value -> OMBRA_PROFILE_SUFFIX
            genericTextUseCaseId.value -> GENERIC_TEXT_PROFILE_SUFFIX
            auraSchemaInferenceUseCaseId.value -> AURA_SCHEMA_PROFILE_SUFFIX
            auraCategoryClassificationUseCaseId.value -> AURA_CATEGORY_PROFILE_SUFFIX
            else -> return null
        }
        return "$catalogProfileKey-$suffix"
    }

    /** Exact model-profile identity exposed by the current document-PII runtime environment. */
    fun ombraModelProfileId(catalogProfileKey: String): String = requireNotNull(modelProfileId(ombraUseCaseId.value, catalogProfileKey))

    fun resolveConsole(model: ImportedPhoneModel): ResolvedUseCase {
        val resolved =
            resolvedPhoneUseCase(
                model = model,
                maxOutputTokens = CONSOLE_DEFAULT_MAX_OUTPUT_TOKENS,
                useCaseValue = consoleUseCaseId.value,
                profileSuffix = CONSOLE_PROFILE_SUFFIX,
                contextSize = CONSOLE_CONTEXT_SIZE,
            )
        return resolved.copy(
            binding = AppModelBinding(
                applicationId = consoleApplicationId,
                useCaseId = consoleUseCaseId,
                useCaseProfileId = resolved.useCase.id,
            ),
        )
    }

    /**
     * Resolves the document-PII runtime for any application identity already authorized and assigned by the
     * control plane. Built-in and user-created applications share the same host-owned runtime contract.
     */
    fun resolveOmbra(model: ImportedPhoneModel, applicationId: ApplicationId = consoleApplicationId): ResolvedUseCase =
        resolveJsonSchemaUseCase(
            model = model,
            applicationId = applicationId,
            useCaseId = ombraUseCaseId,
            defaultPreset = ombraDefaultPreset,
            profileSuffix = OMBRA_PROFILE_SUFFIX,
            maxOutputTokens = STRUCTURED_DEFAULT_MAX_OUTPUT_TOKENS,
            contextSize = STRUCTURED_CONTEXT_SIZE,
        )

    /** Bounded TEXT runtime shared by user-created Consumer applications after explicit authorization. */
    fun resolveGenericText(model: ImportedPhoneModel, applicationId: ApplicationId = consoleApplicationId): ResolvedUseCase {
        val resolved =
            resolvedPhoneUseCase(
                model = model,
                maxOutputTokens = GENERIC_TEXT_DEFAULT_MAX_OUTPUT_TOKENS,
                useCaseValue = genericTextUseCaseId.value,
                profileSuffix = GENERIC_TEXT_PROFILE_SUFFIX,
                contextSize = GENERIC_TEXT_CONTEXT_SIZE,
            )
        return resolved.copy(
            binding = AppModelBinding(
                applicationId = applicationId,
                useCaseId = genericTextUseCaseId,
                useCaseProfileId = resolved.useCase.id,
            ),
        )
    }

    /** Runtime resolver used by the Consumer control plane after assignment/authorization has already succeeded. */
    fun resolveConsumerUseCase(model: ImportedPhoneModel, applicationId: ApplicationId, useCaseId: UseCaseId): ResolvedUseCase =
        when (useCaseId) {
            ombraUseCaseId -> resolveOmbra(model, applicationId)

            genericTextUseCaseId -> resolveGenericText(model, applicationId)

            auraSchemaInferenceUseCaseId,
            auraCategoryClassificationUseCaseId,
            -> {
                require(applicationId == auraApplicationId) { "Aura import runtime requires the Aura application identity" }
                val profileSuffix = when (useCaseId) {
                    auraSchemaInferenceUseCaseId -> AURA_SCHEMA_PROFILE_SUFFIX
                    auraCategoryClassificationUseCaseId -> AURA_CATEGORY_PROFILE_SUFFIX
                    else -> error("Unsupported Aura import useCaseId ${useCaseId.value}")
                }
                resolveJsonSchemaUseCase(
                    model = model,
                    applicationId = applicationId,
                    useCaseId = useCaseId,
                    defaultPreset = auraDefaultPreset,
                    profileSuffix = profileSuffix,
                    maxOutputTokens = STRUCTURED_DEFAULT_MAX_OUTPUT_TOKENS,
                    contextSize = STRUCTURED_CONTEXT_SIZE,
                )
            }

            else -> error("Unsupported Consumer useCaseId ${useCaseId.value}")
        }

    private const val CONSOLE_DEFAULT_MAX_OUTPUT_TOKENS = 512
    private const val CONSOLE_CONTEXT_SIZE = 4_096
    private const val GENERIC_TEXT_DEFAULT_MAX_OUTPUT_TOKENS = 512
    private const val GENERIC_TEXT_CONTEXT_SIZE = 4_096
    private const val STRUCTURED_DEFAULT_MAX_OUTPUT_TOKENS = 512
    private const val STRUCTURED_CONTEXT_SIZE = 4_096
    private const val CONSOLE_PROFILE_SUFFIX = "shared-console"
    private const val OMBRA_PROFILE_SUFFIX = "ombra-pii"
    private const val GENERIC_TEXT_PROFILE_SUFFIX = "generic-text"
    private const val AURA_SCHEMA_PROFILE_SUFFIX = "aura-import-schema"
    private const val AURA_CATEGORY_PROFILE_SUFFIX = "aura-import-category"
}

private fun resolveJsonSchemaUseCase(
    model: ImportedPhoneModel,
    applicationId: ApplicationId,
    useCaseId: UseCaseId,
    defaultPreset: InferencePresetRef,
    profileSuffix: String,
    maxOutputTokens: Int,
    contextSize: Int,
): ResolvedUseCase {
    val resolved =
        resolvedPhoneUseCase(
            model = model,
            maxOutputTokens = maxOutputTokens,
            useCaseValue = useCaseId.value,
            profileSuffix = profileSuffix,
            contextSize = contextSize,
        )
    val useCase =
        resolved.useCase.copy(
            outputMode = OutputMode.JSON_SCHEMA,
            defaultPreset = defaultPreset,
        )
    check(useCase.presets.any { it.ref == defaultPreset && OutputMode.JSON_SCHEMA in it.allowedOutputModes }) {
        "Structured Consumer default preset must support JSON_SCHEMA"
    }
    return resolved.copy(
        binding = AppModelBinding(
            applicationId = applicationId,
            useCaseId = useCaseId,
            useCaseProfileId = useCase.id,
        ),
        useCase = useCase,
    )
}
