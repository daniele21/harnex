package io.github.daniele21.localllm.phonetest

import io.github.daniele21.localllm.contracts.ApplicationId
import io.github.daniele21.localllm.contracts.UseCaseId
import io.github.daniele21.localllm.models.ApplicationRegistrationState
import io.github.daniele21.localllm.models.HostControlPlaneState

/**
 * Runtime-side eligibility for a control-plane-owned external Consumer API application.
 * Binder package/signature authorization is necessary but not sufficient: the application must also remain
 * authorized in the canonical control plane and hold at least one current enabled reviewed assignment.
 */
internal fun HostControlPlaneState.isAuthorizedConsumerForAnyUseCase(applicationId: ApplicationId, useCaseIds: Set<UseCaseId>): Boolean {
    val application = applications.singleOrNull { it.applicationId == applicationId } ?: return false
    if (application.state != ApplicationRegistrationState.AUTHORIZED) return false
    return useCaseIds.any { useCaseId -> latestBinding(applicationId, useCaseId)?.enabled == true }
}

internal fun HostControlPlaneState.isAuthorizedOmbraConsumer(applicationId: ApplicationId): Boolean =
    isAuthorizedConsumerForAnyUseCase(applicationId, setOf(HarnessSharedRuntimeBindings.ombraUseCaseId))

internal fun HostControlPlaneState.isAuthorizedGenericTextConsumer(applicationId: ApplicationId): Boolean =
    isAuthorizedConsumerForAnyUseCase(applicationId, setOf(HarnessSharedRuntimeBindings.genericTextUseCaseId))

internal fun HostControlPlaneState.isAuthorizedAuraConsumer(applicationId: ApplicationId): Boolean =
    applicationId == HarnessSharedRuntimeBindings.auraApplicationId &&
        isAuthorizedConsumerForAnyUseCase(applicationId, HarnessSharedRuntimeBindings.auraUseCases)
