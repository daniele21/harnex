@file:Suppress("FunctionName")

package io.github.daniele21.localllm.phonetest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.daniele21.localllm.ui.designsystem.HarnessCard
import io.github.daniele21.localllm.ui.designsystem.HarnessMinimumTouchTarget
import io.github.daniele21.localllm.ui.designsystem.HarnessPrimaryButton
import io.github.daniele21.localllm.ui.designsystem.HarnessStatusBadge
import io.github.daniele21.localllm.ui.designsystem.HarnessStatusTone
import io.github.daniele21.localllm.ui.designsystem.LocalHarnessSpacing

@Composable
internal fun HarnessConnectionControlCard(
    application: HarnessApplicationSummary,
    saving: Boolean,
    onConnectionEnabledChanged: (Boolean) -> Unit,
) {
    when (application.status) {
        HarnessApplicationStatus.PENDING -> HarnessAuthorizationRequiredCard(
            application = application,
            saving = saving,
            identityChanged = false,
            onAuthorize = { onConnectionEnabledChanged(true) },
        )

        HarnessApplicationStatus.IDENTITY_CHANGED -> HarnessAuthorizationRequiredCard(
            application = application,
            saving = saving,
            identityChanged = true,
            onAuthorize = { onConnectionEnabledChanged(true) },
        )

        HarnessApplicationStatus.AUTHORIZED,
        HarnessApplicationStatus.DISABLED,
        -> HarnessConnectionToggleCard(
            application = application,
            saving = saving,
            onConnectionEnabledChanged = onConnectionEnabledChanged,
        )

        HarnessApplicationStatus.UNAVAILABLE -> HarnessCard(
            modifier = Modifier.testTag("application-connection-control"),
        ) {
            HarnessStatusBadge("Unavailable", HarnessStatusTone.ERROR)
            Text("App connection unavailable", style = MaterialTheme.typography.titleMedium)
            Text(
                "Harnex cannot verify the installed application identity, so access remains blocked.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HarnessAuthorizationRequiredCard(
    application: HarnessApplicationSummary,
    saving: Boolean,
    identityChanged: Boolean,
    onAuthorize: () -> Unit,
) {
    HarnessCard(
        emphasized = true,
        modifier = Modifier.testTag("application-connection-control"),
    ) {
        HarnessStatusBadge(
            label = if (identityChanged) "Identity review required" else "Approval required",
            tone = HarnessStatusTone.WARNING,
        )
        Text(
            if (identityChanged) "Review the new app identity" else "Allow ${application.displayName} to use Harnex",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            if (identityChanged) {
                "The installed app identity changed. Harnex blocked access until you explicitly approve the exact identity shown below."
            } else {
                "Harnex detected this app on the device, but it cannot use the shared runtime until you explicitly approve the exact identity shown below."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(verticalArrangement = Arrangement.spacedBy(LocalHarnessSpacing.current.xSmall)) {
            Text(
                "Package · ${application.packageName}",
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                "Signing certificate SHA-256 · ${application.signerSha256}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Assigned use cases stay configured, but they cannot run for this app until approval succeeds.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HarnessPrimaryButton(
            text = if (identityChanged) "Review & allow ${application.displayName}" else "Allow ${application.displayName}",
            modifier = Modifier.fillMaxWidth().testTag("application-connection-authorize"),
            enabled = !saving,
            onClick = onAuthorize,
        )
    }
}

@Composable
private fun HarnessConnectionToggleCard(
    application: HarnessApplicationSummary,
    saving: Boolean,
    onConnectionEnabledChanged: (Boolean) -> Unit,
) {
    val enabled = application.status == HarnessApplicationStatus.AUTHORIZED
    HarnessCard(modifier = Modifier.testTag("application-connection-control")) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = HarnessMinimumTouchTarget),
            horizontalArrangement = Arrangement.spacedBy(LocalHarnessSpacing.current.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(LocalHarnessSpacing.current.xSmall),
            ) {
                Text(
                    if (enabled) "Connection enabled" else "Connection paused",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (enabled) {
                        "This exact app identity can authenticate to Harnex for its configured use cases. Turn this off to pause access without removing configuration."
                    } else {
                        "Access is paused. Assigned use cases and presets are retained; turn this on to allow the same approved app identity again."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onConnectionEnabledChanged,
                enabled = !saving,
                modifier = Modifier.testTag("application-connection-enabled"),
            )
        }
    }
}
