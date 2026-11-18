package io.github.daniele21.localllm.phonetest

import io.github.daniele21.localllm.contracts.ApplicationId
import io.github.daniele21.localllm.contracts.ConsumerOutputConstraintKind
import io.github.daniele21.localllm.contracts.SessionKind
import io.github.daniele21.localllm.models.ApplicationRegistrationState
import io.github.daniele21.localllm.models.ApplicationUseCaseBinding
import io.github.daniele21.localllm.models.HostControlPlaneState
import io.github.daniele21.localllm.models.InMemoryHostControlPlaneStore
import io.github.daniele21.localllm.models.OutputMode
import io.github.daniele21.localllm.models.PresetLifecycleState
import io.github.daniele21.localllm.models.RegisteredApplication
import io.github.daniele21.localllm.models.UseCaseDefinitionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessGenericTextUseCaseTest {
    @Test
    fun `generic text seed is active published bounded and connectable`() {
        val spec = HarnessBuiltInControlPlaneSpec.genericText(listOf(requirement()))

        assertEquals(HarnessSharedRuntimeBindings.genericTextUseCaseId, spec.useCase.useCaseId)
        assertEquals("Generic text generation", spec.useCase.displayName)
        assertEquals(UseCaseDefinitionState.ACTIVE, spec.useCase.state)
        assertEquals(OutputMode.TEXT, spec.useCase.requirements.outputMode)
        assertEquals(SessionKind.STATELESS, spec.useCase.requirements.sessionKind)
        assertFalse(spec.useCase.requirements.reasoningSupported)
        assertEquals(12_000, spec.useCase.requirements.maxInputCharacters)
        assertEquals(PresetLifecycleState.PUBLISHED, spec.preset.state)
        assertEquals(
            HarnessSharedRuntimeBindings.genericTextDefaultPreset,
            spec.preset.execution.inferencePreset,
        )
        assertFalse(spec.isDefaultBinding)

        val reconciled = HarnessControlPlaneReconciler(spec).reconcile(HostControlPlaneState(), 1L)
        assertTrue(reconciled is HarnessControlPlaneReconciliationResult.Success)
        val state = (reconciled as HarnessControlPlaneReconciliationResult.Success).state
        val snapshot = StoreHarnessApplicationsGateway(InMemoryHostControlPlaneStore(state)).snapshot()
        val option = snapshot.connectionOptions.single {
            it.useCaseId == HarnessSharedRuntimeBindings.genericTextUseCaseId.value
        }

        assertEquals("Generic text generation", option.displayName)
        assertEquals(listOf("Quality"), option.presets.map { it.displayName })
    }

    @Test
    fun `generic text consumer policy exposes text only without reasoning`() {
        val applicationId = ApplicationId("hello-harnex")
        val policy = HarnessGenericTextConsumerPolicy.create(applicationId)

        assertEquals(applicationId, policy.applicationId)
        assertEquals(HarnessSharedRuntimeBindings.genericTextUseCaseId, policy.useCaseId)
        assertEquals(
            setOf(ConsumerOutputConstraintKind.TEXT),
            policy.outputConstraints,
        )
        assertEquals(ConsumerOutputConstraintKind.TEXT, policy.defaultOutputConstraint)
        assertEquals(setOf(SessionKind.STATELESS), policy.sessionKinds)
        assertEquals(1, policy.limits.maxConversationMessages)
        assertEquals(HarnessGenericTextConsumerPolicy.MAX_INPUT_CHARACTERS, policy.limits.maxInputCharacters)
        assertEquals(
            HarnessSharedRuntimeBindings.genericTextDefaultPreset,
            policy.defaultPreset,
        )
    }

    @Test
    fun `generic text requires authorized application and enabled binding`() {
        val applicationId = ApplicationId("hello-harnex")
        val authorized = genericConsumerState(
            applicationId = applicationId,
            registrationState = ApplicationRegistrationState.AUTHORIZED,
            bindingEnabled = true,
        )
        val disabledApplication = genericConsumerState(
            applicationId = applicationId,
            registrationState = ApplicationRegistrationState.DISABLED,
            bindingEnabled = true,
        )
        val disabledBinding = genericConsumerState(
            applicationId = applicationId,
            registrationState = ApplicationRegistrationState.AUTHORIZED,
            bindingEnabled = false,
        )

        assertTrue(authorized.isAuthorizedGenericTextConsumer(applicationId))
        assertFalse(disabledApplication.isAuthorizedGenericTextConsumer(applicationId))
        assertFalse(disabledBinding.isAuthorizedGenericTextConsumer(applicationId))
    }

    private fun genericConsumerState(
        applicationId: ApplicationId,
        registrationState: ApplicationRegistrationState,
        bindingEnabled: Boolean,
    ) = HostControlPlaneState(
        applications = listOf(
            RegisteredApplication(
                applicationId = applicationId,
                packageName = "io.github.daniele21.harnex.hello",
                signerSha256 = "a".repeat(64),
                displayName = "Hello Harnex",
                state = registrationState,
                firstSeenAtEpochMs = 1L,
                lastSeenAtEpochMs = 1L,
            ),
        ),
        useCases = listOf(HarnessBuiltInControlPlaneSpec.genericText(listOf(requirement())).useCase),
        bindings = listOf(
            ApplicationUseCaseBinding(
                bindingId = "connection:${applicationId.value}:generic-text-generation",
                applicationId = applicationId,
                useCaseId = HarnessSharedRuntimeBindings.genericTextUseCaseId,
                revision = 1,
                enabled = bindingEnabled,
                isDefault = bindingEnabled,
            ),
        ),
    )

    private fun requirement() = HarnessBuiltInApplicationRequirement(
        applicationId = ApplicationId("hello-harnex"),
        acceptedPackageNames = setOf("io.github.daniele21.harnex.hello"),
        acceptedSignerSha256 = setOf("a".repeat(64)),
        displayName = "Hello Harnex",
    )
}
