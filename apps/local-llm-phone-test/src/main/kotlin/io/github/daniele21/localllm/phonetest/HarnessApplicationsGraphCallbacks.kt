package io.github.daniele21.localllm.phonetest

import io.github.daniele21.localllm.models.PresetGenerationOverrides

internal data class HarnessApplicationsGraphCallbacks(
    val onRefresh: () -> Unit,
    val onRefreshObservedIdentity: () -> Unit,
    val onSetDefaultPreset: (String, HarnessAssignmentSummary, HarnessPresetSummary) -> Unit,
    val onSetApplicationConnectionEnabled: (String, Boolean) -> Unit,
    val onCreateApplicationConnection: (String, String, String, String, String, String, Int) -> Unit,
    val onCreateCustomPreset: (
        String,
        HarnessAssignmentSummary,
        HarnessPresetSummary,
        String,
        String?,
        Int?,
        PresetGenerationOverrides?,
    ) -> Unit,
    val onClearMutationFeedback: () -> Unit,
)
