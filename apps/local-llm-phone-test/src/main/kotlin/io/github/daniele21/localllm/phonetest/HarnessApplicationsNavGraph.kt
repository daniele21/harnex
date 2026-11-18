package io.github.daniele21.localllm.phonetest

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController

internal fun NavGraphBuilder.installHarnessApplicationsFeatureGraph(
    navController: NavHostController,
    state: HarnessApplicationsReadState,
    mutationState: HarnessApplicationsMutationState,
    callbacks: HarnessApplicationsGraphCallbacks,
) {
    installApplicationsListRoute(navController, state, callbacks)
    installApplicationDetailRoute(navController, state, mutationState, callbacks)
    installAssignmentRoute(navController, state, callbacks)
    installPresetRoute(navController, state, mutationState, callbacks)
    installNewPresetRoute(navController, state, mutationState, callbacks)
    installTechnicalDetailsRoute(state, callbacks)
    installNewApplicationConnectionRoute(navController, state, mutationState, callbacks)
}
