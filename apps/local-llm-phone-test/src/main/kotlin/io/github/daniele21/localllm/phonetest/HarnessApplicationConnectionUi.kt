@file:Suppress("FunctionName")

package io.github.daniele21.localllm.phonetest

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import io.github.daniele21.localllm.ui.designsystem.HarnessCard
import io.github.daniele21.localllm.ui.designsystem.HarnessEmptyState
import io.github.daniele21.localllm.ui.designsystem.HarnessPrimaryButton
import io.github.daniele21.localllm.ui.designsystem.HarnessRecoveryCard
import io.github.daniele21.localllm.ui.designsystem.HarnessStatusBadge
import io.github.daniele21.localllm.ui.designsystem.HarnessStatusTone
import io.github.daniele21.localllm.ui.designsystem.LocalHarnessSpacing

internal fun NavGraphBuilder.installNewApplicationConnectionRoute(
    navController: NavHostController,
    state: HarnessApplicationsReadState,
    mutationState: HarnessApplicationsMutationState,
    callbacks: HarnessApplicationsGraphCallbacks,
) {
    composable(HarnessApplicationRoutes.NEW_APPLICATION_ROUTE) {
        LaunchedEffect(Unit) { callbacks.onClearMutationFeedback() }
        HarnessApplicationsRouteContent(state = state, onRefresh = callbacks.onRefresh) { snapshot ->
            HarnessCreateApplicationConnectionScreen(
                options = snapshot.connectionOptions,
                mutationState = mutationState,
                onCreate = callbacks.onCreateApplicationConnection,
                onReload = callbacks.onRefresh,
                onClearFeedback = callbacks.onClearMutationFeedback,
                onDone = { navController.popBackStack() },
            )
        }
    }
}

@Composable
internal fun HarnessCreateApplicationConnectionScreen(
    options: List<HarnessConnectionUseCaseOption>,
    mutationState: HarnessApplicationsMutationState,
    onCreate: (String, String, String, String, String, String, Int) -> Unit,
    onReload: () -> Unit,
    onClearFeedback: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (options.isEmpty()) {
        HarnessEmptyState(
            title = "No connectable use cases",
            detail = "A published use case with at least one available preset is required before an app connection can be created.",
            modifier = modifier,
        )
        return
    }

    var displayName by rememberSaveable { mutableStateOf("") }
    var applicationId by rememberSaveable { mutableStateOf("") }
    var packageName by rememberSaveable { mutableStateOf("") }
    var signerSha256 by rememberSaveable { mutableStateOf("") }
    var selectedUseCaseId by rememberSaveable { mutableStateOf(options.first().useCaseId) }
    var selectedPresetKey by rememberSaveable {
        mutableStateOf(options.first().presets.first().identityKey())
    }
    val selectedUseCase = options.firstOrNull { it.useCaseId == selectedUseCaseId } ?: options.first()
    val selectedPreset = selectedUseCase.presets.firstOrNull { it.identityKey() == selectedPresetKey }
        ?: selectedUseCase.presets.first()
    val saving = mutationState == HarnessApplicationsMutationState.Saving
    val saved = mutationState is HarnessApplicationsMutationState.Saved
    val signerValid = signerSha256.length == SHA256_FINGERPRINT_LENGTH && signerSha256.all(Char::isHexDigit)
    val formValid = displayName.isNotBlank() && applicationId.isNotBlank() && packageName.isNotBlank() && signerValid

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("create-application-connection"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(LocalHarnessSpacing.current.large),
        verticalArrangement = Arrangement.spacedBy(LocalHarnessSpacing.current.medium),
    ) {
        item { ConnectionScreenHeader() }
        item {
            ConnectionIdentitySection(
                model = ConnectionIdentityModel(
                    displayName,
                    applicationId,
                    packageName,
                    signerSha256,
                    signerValid,
                    !saving && !saved,
                ),
                onDisplayNameChanged = { value ->
                    displayName = value
                    onClearFeedback()
                },
                onApplicationIdChanged = { value ->
                    applicationId = value
                    onClearFeedback()
                },
                onPackageNameChanged = { value ->
                    packageName = value.trim()
                    onClearFeedback()
                },
                onSignerChanged = { candidate ->
                    normalizeSignerFingerprint(candidate)?.let { signerSha256 = it }
                    onClearFeedback()
                },
            )
        }
        item {
            ConnectionAccessSelection(
                options = options,
                selectedUseCase = selectedUseCase,
                selectedPreset = selectedPreset,
                enabled = !saving && !saved,
                onUseCaseSelected = { option ->
                    selectedUseCaseId = option.useCaseId
                    selectedPresetKey = option.presets.first().identityKey()
                    onClearFeedback()
                },
                onPresetSelected = { preset ->
                    selectedPresetKey = preset.identityKey()
                    onClearFeedback()
                },
            )
        }
        item { ConnectionReviewCard(displayName, packageName, selectedUseCase, selectedPreset) }
        if (!saved) {
            item {
                ConnectionCreateAction(
                    saving = saving,
                    enabled = formValid && !saving,
                    submission = ConnectionSubmission(
                        applicationId.trim(),
                        displayName.trim(),
                        packageName.trim(),
                        signerSha256,
                        selectedUseCase.useCaseId,
                        selectedPreset.presetId,
                        selectedPreset.revision,
                    ),
                    onCreate = onCreate,
                )
            }
        }
        item { ConnectionMutationFeedback(mutationState, onReload, onClearFeedback, onDone) }
    }
}

private data class ConnectionIdentityModel(
    val displayName: String,
    val applicationId: String,
    val packageName: String,
    val signerSha256: String,
    val signerValid: Boolean,
    val enabled: Boolean,
)

@Composable
private fun ConnectionIdentitySection(
    model: ConnectionIdentityModel,
    onDisplayNameChanged: (String) -> Unit,
    onApplicationIdChanged: (String) -> Unit,
    onPackageNameChanged: (String) -> Unit,
    onSignerChanged: (String) -> Unit,
) {
    HarnessCard {
        Text("Application identity", style = MaterialTheme.typography.titleMedium)
        Text(
            "Package and signer are checked at the Binder security boundary. They must match the installed consumer app exactly.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ConnectionTextField(
            model.displayName,
            onDisplayNameChanged,
            "App name",
            "Human-readable name shown in Harness",
            model.enabled,
            "connection-display-name",
        )
        ConnectionTextField(
            model.applicationId,
            onApplicationIdChanged,
            "Application ID",
            "Stable Harness identity for this consumer app",
            model.enabled,
            "connection-application-id",
        )
        ConnectionTextField(
            model.packageName,
            onPackageNameChanged,
            "Android package",
            "Example: com.example.myapp",
            model.enabled,
            "connection-package-name",
        )
        ConnectionTextField(
            value = model.signerSha256,
            onValueChange = onSignerChanged,
            label = "Signing certificate SHA-256",
            supportingText = if (model.signerValid || model.signerSha256.isBlank()) {
                "64 hexadecimal characters. Spaces and ':' are removed when pasted."
            } else {
                "Enter the complete 64-character SHA-256 fingerprint."
            },
            enabled = model.enabled,
            testTag = "connection-signer-sha256",
            isError = model.signerSha256.isNotBlank() && !model.signerValid,
        )
    }
}

@Composable
private fun ConnectionAccessSelection(
    options: List<HarnessConnectionUseCaseOption>,
    selectedUseCase: HarnessConnectionUseCaseOption,
    selectedPreset: HarnessConnectionPresetOption,
    enabled: Boolean,
    onUseCaseSelected: (HarnessConnectionUseCaseOption) -> Unit,
    onPresetSelected: (HarnessConnectionPresetOption) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(LocalHarnessSpacing.current.medium)) {
        Text("Use case", style = MaterialTheme.typography.titleMedium)
        options.forEach { option ->
            HarnessCard(
                emphasized = option.useCaseId == selectedUseCase.useCaseId,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("connection-use-case-${option.useCaseId}")
                    .clickable(enabled = enabled) { onUseCaseSelected(option) },
            ) {
                Text(option.displayName, style = MaterialTheme.typography.titleMedium)
                Text(option.description, style = MaterialTheme.typography.bodySmall)
                if (option.useCaseId == selectedUseCase.useCaseId) {
                    HarnessStatusBadge("Selected", HarnessStatusTone.SUCCESS)
                }
            }
        }
        Text("Initial preset", style = MaterialTheme.typography.titleMedium)
        selectedUseCase.presets.forEach { preset ->
            HarnessCard(
                emphasized = preset.identityKey() == selectedPreset.identityKey(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("connection-preset-${preset.presetId}")
                    .clickable(enabled = enabled) { onPresetSelected(preset) },
            ) {
                Text(preset.displayName, style = MaterialTheme.typography.titleMedium)
                Text(preset.description, style = MaterialTheme.typography.bodySmall)
                if (preset.identityKey() == selectedPreset.identityKey()) {
                    HarnessStatusBadge("Default on connection", HarnessStatusTone.SUCCESS)
                }
            }
        }
    }
}

@Composable
private fun ConnectionTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    supportingText: String,
    enabled: Boolean,
    testTag: String,
    isError: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = { Text(supportingText) },
        enabled = enabled,
        isError = isError,
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(testTag),
    )
}

@Composable
private fun ConnectionMutationFeedback(
    state: HarnessApplicationsMutationState,
    onReload: () -> Unit,
    onClearFeedback: () -> Unit,
    onDone: () -> Unit,
) {
    when (state) {
        HarnessApplicationsMutationState.Idle -> Unit

        HarnessApplicationsMutationState.Saving -> HarnessCard {
            HarnessStatusBadge("Saving", HarnessStatusTone.INFO)
            Text("Persisting the application identity, assignment and default preset.")
        }

        is HarnessApplicationsMutationState.Saved -> HarnessCard(emphasized = true) {
            HarnessStatusBadge("Connection ready", HarnessStatusTone.SUCCESS)
            Text(state.message)
            HarnessPrimaryButton("Done", onClick = onDone)
        }

        is HarnessApplicationsMutationState.Conflict -> HarnessRecoveryCard(
            title = "Configuration changed",
            detail = state.message,
            actionLabel = "Reload changes",
            onAction = onReload,
            tone = HarnessStatusTone.WARNING,
        )

        is HarnessApplicationsMutationState.Failed -> HarnessRecoveryCard(
            title = "Connection not created",
            detail = state.message,
            actionLabel = "Review fields",
            onAction = onClearFeedback,
            tone = HarnessStatusTone.ERROR,
        )
    }
}

internal fun normalizeSignerFingerprint(value: String): String? {
    val normalized = value.filterNot { it.isWhitespace() || it == ':' }.lowercase()
    return normalized.takeIf {
        it.length <= SHA256_FINGERPRINT_LENGTH && it.all(Char::isHexDigit)
    }
}

private fun Char.isHexDigit(): Boolean = isDigit() || lowercaseChar() in 'a'..'f'

private fun HarnessConnectionPresetOption.identityKey(): String = "$presetId:$revision"

private const val SHA256_FINGERPRINT_LENGTH = 64
