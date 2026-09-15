package io.github.daniele21.localllm.phonetest

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import io.github.daniele21.localllm.ui.designsystem.HarnessTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File

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
    fun pendingAuthorizationJourneyTransitionsToConnectedControl() {
        composeRule.setContent {
            var status by remember { mutableStateOf(HarnessApplicationStatus.PENDING) }
            HarnessTheme(darkTheme = false) {
                HarnessConnectionControlCard(
                    application = application(status),
                    saving = false,
                    onConnectionEnabledChanged = { enabled ->
                        status = if (enabled) {
                            HarnessApplicationStatus.AUTHORIZED
                        } else {
                            HarnessApplicationStatus.DISABLED
                        }
                    },
                )
            }
        }

        composeRule.onNodeWithTag("application-connection-authorize").assertIsEnabled()
        holdForMediaEvidence()
        captureAuthorizationEvidence("needs-approval")

        composeRule.onNodeWithTag("application-connection-authorize").performClick()
        composeRule.onNodeWithTag("application-connection-enabled").assertIsEnabled().assertIsOn()
        holdForMediaEvidence()
        captureAuthorizationEvidence("connected")
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

    private fun holdForMediaEvidence() {
        composeRule.waitForIdle()
        SystemClock.sleep(1_000)
    }

    private fun captureAuthorizationEvidence(name: String) {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val evidenceDir = File(
            requireNotNull(targetContext.getExternalFilesDir(null)),
            "ui-evidence/application-authorization",
        )
        if (!evidenceDir.exists()) {
            check(evidenceDir.mkdirs()) { "Unable to create application authorization evidence directory" }
        }
        File(evidenceDir, "$name.png").outputStream().use { stream ->
            check(
                composeRule.onRoot().captureToImage().asAndroidBitmap().compress(
                    Bitmap.CompressFormat.PNG,
                    100,
                    stream,
                ),
            ) {
                "Unable to write application authorization UI evidence $name"
            }
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
