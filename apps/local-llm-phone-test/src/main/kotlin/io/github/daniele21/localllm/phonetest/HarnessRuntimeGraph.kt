package io.github.daniele21.localllm.phonetest

import android.content.Context
import io.github.daniele21.localllm.audit.InferenceAuditOrigin
import io.github.daniele21.localllm.audit.InferenceAuditOriginKind
import io.github.daniele21.localllm.audit.InferenceAuditRepository
import io.github.daniele21.localllm.audit.room.RoomInferenceAuditRepository
import io.github.daniele21.localllm.contracts.ApplicationId
import io.github.daniele21.localllm.contracts.ConsumerLimits
import io.github.daniele21.localllm.contracts.ConsumerLocalLlmClient
import io.github.daniele21.localllm.contracts.ConsumerOutputConstraintKind
import io.github.daniele21.localllm.contracts.ConsumerReasoningCapability
import io.github.daniele21.localllm.contracts.LocalLlmClient
import io.github.daniele21.localllm.contracts.ModelDigest
import io.github.daniele21.localllm.contracts.SessionKind
import io.github.daniele21.localllm.contracts.UseCaseId
import io.github.daniele21.localllm.integration.servicehost.AuthorizedCaller
import io.github.daniele21.localllm.integration.servicehost.AuthorizedClientPolicy
import io.github.daniele21.localllm.integration.servicehost.AuthorizedConsumerClientFactory
import io.github.daniele21.localllm.integration.servicehost.AuthorizedRuntimeClientFactory
import io.github.daniele21.localllm.models.ModelProfileRegistry
import io.github.daniele21.localllm.models.ResolvedUseCase
import io.github.daniele21.localllm.models.controlplane.room.RoomHostControlPlaneStoreOwner
import io.github.daniele21.localllm.observability.GenerationRunRecord
import io.github.daniele21.localllm.observability.StructuredLog
import io.github.daniele21.localllm.observability.TelemetryRepository
import io.github.daniele21.localllm.observability.TelemetryRetentionPolicy
import io.github.daniele21.localllm.observability.room.RoomTelemetryRepository
import io.github.daniele21.localllm.runtime.ActivationIdFactory
import io.github.daniele21.localllm.runtime.ActivationResidencyCoordinator
import io.github.daniele21.localllm.runtime.ConsumerCapabilityPolicyService
import io.github.daniele21.localllm.runtime.ConsumerLocalLlmFacade
import io.github.daniele21.localllm.runtime.ConsumerUseCasePolicy
import io.github.daniele21.localllm.runtime.InMemoryConsumerUseCasePolicyRegistry
import io.github.daniele21.localllm.runtime.InferenceAuditLocalLlmClient
import io.github.daniele21.localllm.runtime.InferenceAuditOriginResolver
import io.github.daniele21.localllm.runtime.OneShotInferenceAuditEffectivePromptBridge
import io.github.daniele21.localllm.runtime.RuntimeMemoryPressure
import io.github.daniele21.localllm.runtime.RuntimeOrchestrator
import io.github.daniele21.localllm.runtime.UseCaseActivationId
import io.github.daniele21.localllm.runtime.UseCaseActivationLeaseRegistry
import io.github.daniele21.localllm.store.ModelStore
import io.github.daniele21.localllm.transport.InProcessLocalLlmClient
import java.util.UUID

private const val DEFAULT_RUN_LIMIT = 50
private const val DEFAULT_LOG_LIMIT = 200

/** Process-scoped owner of the embedded Harness runtime, control plane and observability sources. */
internal class HarnessRuntimeGraph private constructor(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val registry = HarnessPhoneBindingRegistry()
    private val activationLeases = UseCaseActivationLeaseRegistry(
        idFactory = ActivationIdFactory { UseCaseActivationId(UUID.randomUUID().toString()) },
    )

    val authorizedClientPolicies: List<AuthorizedClientPolicy> = HarnessSharedRuntimePolicy.authorizedClients(appContext)

    private val controlPlaneStoreOwner =
        RoomHostControlPlaneStoreOwner.open(appContext, CONTROL_PLANE_DATABASE_NAME)
    private val telemetryRepositoryOwner = RoomTelemetryRepository.open(
        context = appContext,
        databaseName = TELEMETRY_DATABASE_NAME,
        retention = TelemetryRetentionPolicy(
            maxRuns = MAX_RETAINED_RUNS,
            maxLogs = MAX_RETAINED_LOGS,
            maxResourceSnapshots = MAX_RETAINED_RESOURCE_SNAPSHOTS,
        ),
    )
    private val inferenceAuditRepositoryOwner = RoomInferenceAuditRepository.open(
        context = appContext,
        databaseName = INFERENCE_AUDIT_DATABASE_NAME,
    )
    private val effectivePromptBridge = OneShotInferenceAuditEffectivePromptBridge()
    val inferenceActivitySource = HarnessInferenceActivitySource(inferenceAuditRepositoryOwner)
    val auditStartupState = inferenceActivitySource.reconcileInterrupted(System.currentTimeMillis())

    init {
        val genericTextSpec = HarnessSharedRuntimePolicy.builtInGenericTextControlPlaneSpec(authorizedClientPolicies)
        val auraSpecs = HarnessSharedRuntimePolicy.builtInAuraControlPlaneSpecs(authorizedClientPolicies)
        HarnessControlPlaneStartup(
            store = controlPlaneStoreOwner.store,
            reconciler =
            HarnessControlPlaneReconciler(
                HarnessSharedRuntimePolicy.builtInOmbraControlPlaneSpec(authorizedClientPolicies),
            ),
            additionalReconcilers =
            listOf(HarnessControlPlaneReconciler(genericTextSpec)) + auraSpecs.map(::HarnessControlPlaneReconciler),
        ).reconcile()
    }

    val activationResidency = ActivationResidencyCoordinator(activationLeases)
    val modelStore: ModelStore = HarnessRuntimePlatform.modelStore(appContext)
    val controlPlaneStore = HarnessPhoneControlPlaneAccess(
        context = appContext,
        store = controlPlaneStoreOwner.store,
        applicationsRuntimeSource = RuntimeGraphHarnessApplicationsRuntimeSource(
            activeResolved = registry::activeResolved,
            runtimeSnapshot = ::runtimeSnapshot,
        ),
    )
    val telemetryRepository: TelemetryRepository = telemetryRepositoryOwner
    val inferenceAuditRepository: InferenceAuditRepository = inferenceAuditRepositoryOwner

    val loadedModelDigest: ModelDigest?
        get() = synchronized(lock) { runtime?.runtimeSnapshot()?.loadedModel }

    var selectedModel: ImportedPhoneModel?
        get() = registry.selectedModel
        set(value) {
            registry.selectedModel = value
        }

    private var runtime: RuntimeOrchestrator? = null
    private var runtimeClient: LocalLlmClient? = null
    private val sharedRuntimeClientFacade = HarnessSharedRuntimeClient(
        activeClient = { synchronized(lock) { runtimeClient } },
        prepareClient = {
            synchronized(lock) {
                ensureRuntime()
                runtimeClient
            }
        },
    )

    val sharedRuntimeClient: LocalLlmClient
        get() = sharedRuntimeClientFacade

    val legacyRuntimeClientFactory = AuthorizedRuntimeClientFactory { caller ->
        externalAuditedRuntimeClient(
            caller = caller,
            delegate = sharedRuntimeClientFacade,
            auditRepository = inferenceAuditRepository,
            telemetryRepository = telemetryRepository,
            effectivePromptBridge = effectivePromptBridge,
        )
    }

    val consumerClientFactory = AuthorizedConsumerClientFactory { caller ->
        val applicationId = caller.applicationId
        val policies =
            when (applicationId) {
                HarnessSharedRuntimeBindings.consoleApplicationId -> {
                    val legacyConsolePolicy =
                        ConsumerUseCasePolicy(
                            applicationId = applicationId,
                            useCaseId = HarnessSharedRuntimeBindings.consoleUseCaseId,
                            revision = "shared-console-consumer-v1",
                            exposedPresets = emptySet(),
                            defaultPreset = null,
                            reasoning = ConsumerReasoningCapability.NOT_SUPPORTED,
                            outputConstraints = setOf(ConsumerOutputConstraintKind.TEXT),
                            defaultOutputConstraint = ConsumerOutputConstraintKind.TEXT,
                            sessionKinds = setOf(SessionKind.STATELESS),
                            defaultSessionKind = SessionKind.STATELESS,
                            limits =
                            ConsumerLimits(
                                maxInputCharacters = 32_768,
                                maxConversationMessages = 128,
                                maxJsonSchemaCharacters = 32_768,
                            ),
                        )
                    listOf(
                        legacyConsolePolicy,
                        HarnessOmbraConsumerPolicy.create(applicationId),
                        HarnessGenericTextConsumerPolicy.create(applicationId),
                    )
                }

                HarnessSharedRuntimeBindings.redactGuardApplicationId ->
                    listOf(HarnessOmbraConsumerPolicy.create(applicationId))

                HarnessSharedRuntimeBindings.auraApplicationId -> {
                    val state = controlPlaneStore.snapshot()
                    require(state.isAuthorizedAuraConsumer(applicationId)) {
                        "Consumer API is not configured for applicationId ${applicationId.value}"
                    }
                    HarnessSharedRuntimeBindings.auraUseCases
                        .sortedBy(UseCaseId::value)
                        .map { useCaseId -> HarnessAuraImportConsumerPolicy.create(applicationId, useCaseId) }
                }

                else -> {
                    val state = controlPlaneStore.snapshot()
                    val configuredPolicies = buildList {
                        if (state.isAuthorizedOmbraConsumer(applicationId)) {
                            add(HarnessOmbraConsumerPolicy.create(applicationId))
                        }
                        if (state.isAuthorizedGenericTextConsumer(applicationId)) {
                            add(HarnessGenericTextConsumerPolicy.create(applicationId))
                        }
                    }
                    require(configuredPolicies.isNotEmpty()) {
                        "Consumer API is not configured for applicationId ${applicationId.value}"
                    }
                    configuredPolicies
                }
            }
        val fallbackPolicyRegistry = InMemoryConsumerUseCasePolicyRegistry(policies)
        val configuredProfileResolver = HarnessConfiguredConsumerProfileResolver(
            store = controlPlaneStoreOwner.store,
            modelStore = modelStore,
        )
        val capabilityPolicy =
            ConsumerCapabilityPolicyService(
                profileRegistry = HarnessConsumerCapabilityProfileRegistry(
                    activeBindings = registry,
                    configuredResolver = configuredProfileResolver::resolve,
                ),
                modelStore = modelStore,
                policyRegistry = HarnessActivationAwareConsumerPolicyRegistry(
                    applicationId = applicationId,
                    bindings = registry,
                    fallback = fallbackPolicyRegistry,
                ),
            )
        ConsumerLocalLlmFacade(
            applicationId,
            capabilityPolicy,
            externalAuditedRuntimeClient(
                caller = caller,
                delegate = sharedRuntimeClientFacade,
                auditRepository = inferenceAuditRepository,
                telemetryRepository = telemetryRepository,
                effectivePromptBridge = effectivePromptBridge,
            ),
        )
    }

    fun installActivationBinding(
        activationId: UseCaseActivationId,
        applicationId: ApplicationId,
        useCaseId: UseCaseId,
        resolved: ResolvedUseCase,
    ) {
        registry.installActivationBinding(activationId, applicationId, useCaseId, resolved)
    }

    fun removeActivationBinding(activationId: UseCaseActivationId) {
        registry.removeActivationBinding(activationId)
    }

    fun harnessFor(model: ImportedPhoneModel, purpose: HarnessRuntimePurpose): PhoneHarness = synchronized(lock) {
        Qwen35PhoneModelPolicy.requireCurated(model)
        registry.selectedModel = model
        ensureRuntime()
        val resolved = registry.resolve(APPLICATION_ID, purpose.useCaseId)
        val auditedClient = InferenceAuditLocalLlmClient(
            delegate = requireNotNull(runtimeClient),
            auditRepository = inferenceAuditRepository,
            telemetryRepository = telemetryRepository,
            effectivePromptResolver = effectivePromptBridge,
            originResolver = InferenceAuditOriginResolver { request ->
                InferenceAuditOrigin(
                    kind = InferenceAuditOriginKind.HARNEX_INTERNAL,
                    applicationId = request.applicationId,
                    useCaseId = request.useCaseId,
                )
            },
        )
        PhoneHarness(
            runtime = requireNotNull(runtime),
            client = auditedClient,
            applicationId = resolved.binding.applicationId,
            useCaseId = resolved.binding.useCaseId,
        )
    }

    fun releaseModel(digest: ModelDigest) {
        synchronized(lock) {
            if (activationResidency.protects(digest)) return
            if (runtime?.runtimeSnapshot()?.loadedModel != digest) return
            closeRuntimeLocked()
        }
    }

    fun unloadIdleModel(): Boolean = synchronized(lock) {
        runtime?.unloadIdleModel() ?: false
    }

    fun handleMemoryPressure(pressure: RuntimeMemoryPressure) = synchronized(lock) {
        runtime?.handleMemoryPressure(pressure)
    }

    fun runtimeSnapshot() = synchronized(lock) {
        runtime?.runtimeSnapshot()
    }

    override fun close() {
        synchronized(lock) {
            closeRuntimeLocked()
            registry.clearActivationBindings()
            registry.selectedModel = null
        }
        inferenceAuditRepositoryOwner.close()
        telemetryRepositoryOwner.close()
        controlPlaneStoreOwner.close()
    }

    private fun ensureRuntime() {
        if (runtime != null) return

        val backend = HarnessRuntimePlatform.backend(appContext, activationResidency)
        val orchestrator = RuntimeOrchestrator(
            registry = registry,
            modelStore = modelStore,
            backend = backend,
            telemetryRepository = telemetryRepository,
            effectivePromptSink = effectivePromptBridge,
        )
        runtime = orchestrator
        runtimeClient = InProcessLocalLlmClient(orchestrator)
    }

    private fun closeRuntimeLocked() {
        runtime?.close()
        effectivePromptBridge.clear()
        runtime = null
        runtimeClient = null
    }

    companion object {
        private const val CONTROL_PLANE_DATABASE_NAME = "harness-control-plane.db"
        private const val TELEMETRY_DATABASE_NAME = "harnex-telemetry.db"
        private const val INFERENCE_AUDIT_DATABASE_NAME = "harnex-inference-audit.db"
        private const val MAX_RETAINED_RUNS = 200
        private const val MAX_RETAINED_LOGS = 1_000
        private const val MAX_RETAINED_RESOURCE_SNAPSHOTS = 200
        internal val APPLICATION_ID = ApplicationId("play-internal-phone-test")

        @Volatile
        private var instance: HarnessRuntimeGraph? = null

        fun from(context: Context): HarnessRuntimeGraph = instance ?: synchronized(this) {
            instance ?: HarnessRuntimeGraph(context).also { instance = it }
        }
    }
}

private fun externalAuditedRuntimeClient(
    caller: AuthorizedCaller,
    delegate: LocalLlmClient,
    auditRepository: InferenceAuditRepository,
    telemetryRepository: TelemetryRepository,
    effectivePromptBridge: OneShotInferenceAuditEffectivePromptBridge,
): LocalLlmClient = InferenceAuditLocalLlmClient(
    delegate = delegate,
    auditRepository = auditRepository,
    telemetryRepository = telemetryRepository,
    effectivePromptResolver = effectivePromptBridge,
    originResolver = InferenceAuditOriginResolver { request ->
        InferenceAuditOrigin(
            kind = InferenceAuditOriginKind.EXTERNAL_CONSUMER,
            applicationId = request.applicationId,
            useCaseId = request.useCaseId,
            verifiedPackageName = caller.packageName,
        )
    },
)

internal fun HarnessRuntimeGraph.recentRuns(limit: Int = DEFAULT_RUN_LIMIT): List<GenerationRunRecord> =
    telemetryRepository.recentRuns(limit)

internal fun HarnessRuntimeGraph.recentLogs(limit: Int = DEFAULT_LOG_LIMIT): List<StructuredLog> = telemetryRepository.recentLogs(limit)

internal enum class HarnessRuntimePurpose(val useCaseId: UseCaseId) {
    PLAYGROUND(UseCaseId("manual-inference-playground")),
    PHYSICAL_VALIDATION(UseCaseId("physical-device-validation")),
}

internal class HarnessPhoneBindingRegistry : ModelProfileRegistry {
    private data class ActivationBinding(
        val activationId: UseCaseActivationId,
        val applicationId: ApplicationId,
        val useCaseId: UseCaseId,
        val resolved: ResolvedUseCase,
    )

    private val lock = Any()
    private var model: ImportedPhoneModel? = null
    private val activationBindings = LinkedHashMap<UseCaseActivationId, ActivationBinding>()

    var selectedModel: ImportedPhoneModel?
        get() = synchronized(lock) { model }
        set(value) {
            value?.let(Qwen35PhoneModelPolicy::requireCurated)
            synchronized(lock) { model = value }
        }

    fun installActivationBinding(
        activationId: UseCaseActivationId,
        applicationId: ApplicationId,
        useCaseId: UseCaseId,
        resolved: ResolvedUseCase,
    ) {
        require(resolved.binding.applicationId == applicationId) { "Activation binding application mismatch" }
        require(resolved.binding.useCaseId == useCaseId) { "Activation binding use-case mismatch" }
        synchronized(lock) {
            activationBindings[activationId] = ActivationBinding(activationId, applicationId, useCaseId, resolved)
        }
    }

    fun activeResolved(applicationId: ApplicationId, useCaseId: UseCaseId): ResolvedUseCase? = synchronized(lock) {
        activationBindings.values.lastOrNull {
            it.applicationId == applicationId && it.useCaseId == useCaseId
        }?.resolved
    }

    fun removeActivationBinding(activationId: UseCaseActivationId) {
        synchronized(lock) { activationBindings.remove(activationId) }
    }

    fun clearActivationBindings() {
        synchronized(lock) { activationBindings.clear() }
    }

    override fun resolve(applicationId: ApplicationId, useCaseId: UseCaseId): ResolvedUseCase {
        activeResolved(applicationId, useCaseId)?.let { return it }

        check(applicationId == HarnessRuntimeGraph.APPLICATION_ID) {
            "External consumer requires an active Harness control-plane activation"
        }
        val selected = synchronized(lock) { requireNotNull(model) { "No model selected" } }
        return resolveInternal(selected, useCaseId)
    }

    private fun resolveInternal(model: ImportedPhoneModel, useCaseId: UseCaseId): ResolvedUseCase = when (useCaseId) {
        HarnessRuntimePurpose.PLAYGROUND.useCaseId -> resolvedPhonePlaygroundUseCase(model)

        HarnessRuntimePurpose.PHYSICAL_VALIDATION.useCaseId -> resolvedPhoneUseCase(
            model = model,
            maxOutputTokens = VALIDATION_MAX_OUTPUT_TOKENS,
        )

        else -> error("Unknown useCaseId ${useCaseId.value}")
    }

    private companion object {
        const val VALIDATION_MAX_OUTPUT_TOKENS = 512
    }
}
