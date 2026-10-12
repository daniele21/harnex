package io.github.daniele21.localllm.phonetest

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController

/**
 * App-level product graph composition used by the phone Harness shell.
 *
 * The historical function name is retained at the MainActivity boundary while feature route
 * ownership stays separated between Applications and inference Activity.
 */
internal fun NavGraphBuilder.installHarnessApplicationsGraph(
    navController: NavHostController,
    state: HarnessApplicationsReadState,
    mutationState: HarnessApplicationsMutationState,
    callbacks: HarnessApplicationsGraphCallbacks,
) {
    installHarnessApplicationsFeatureGraph(
        navController = navController,
        state = state,
        mutationState = mutationState,
        callbacks = callbacks,
    )
    installHarnessInferenceActivityGraph(navController)
}
