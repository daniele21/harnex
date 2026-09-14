package io.github.daniele21.localllm.phonetest

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.daniele21.localllm.ui.designsystem.HarnessTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HarnessApplicationConnectionControlUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun pendingApplicationRequiresExplicitApprovalAction() {
        var requested: Boolean? = null
        composeRule.setContent {
            HarnessTheme(darkTheme = false) {
                HarnessConnectionControlCard(
                    application = application(HarnessApplicationStatus.PENDING),
                    saving = false,
                    onConnectionEnabledChanged = { requested = it },
                )
            }
        }

        composeRule
            .onNodeWithTag("application-connection-authorize")
            .assertIsEnabled()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(true, requested)
        }
    }

    @Test
    fun changedIdentityRequiresExplicitReauthorizationAction() {
        var requested: Boolean? = null
        composeRule.setContent {
            HarnessTheme(darkTheme = false) {
                HarnessConnectionControlCard(
                    application = application(HarnessApplicationStatus.IDENTITY_CHANGED),
                    saving = false,
                    onConnectionEnabledChanged = { requested = it },
                )
            }
        }

        composeRule
            .onNodeWithTag("application-connection-authorize")
            .assertIsEnabled()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(true, requested)
        }
    }

    @Test
    fun authorizedApplicationUsesConnectionToggleForPausing() {
        var requested: Boolean? = null
        composeRule.setContent {
            HarnessTheme(darkTheme = false) {
                HarnessConnectionControlCard(
                    application = application(HarnessApplicationStatus.AUTHORIZED),
                    saving = false,
                    onConnectionEnabledChanged = { requested = it },
                )
            }
        }

        composeRule
            .onNodeWithTag("application-connection-enabled")
            .assertIsEnabled()
            .performClick()

        composeRule.runOnIdle {
            assertEquals(false, requested)
        }
    }

    private fun application(status: HarnessApplicationStatus) = HarnessApplicationSummary(
        applicationId = "aura-finance",
        displayName = "Aura Finance",
        packageName = "com.staituned.aura",
        signerSha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        status = status,
        firstSeenAtEpochMs = 1L,
        lastSeenAtEpochMs = 1L,
        assignments = emptyList(),
    )
}
