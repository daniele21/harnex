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
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import io.github.daniele21.localllm.ui.designsystem.HarnessTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

    @Test
    fun createConnectionButtonSubmitsExactVisibleForm() {
        var submission: ConnectionSubmission? = null
        composeRule.setContent {
            HarnessTheme(darkTheme = false) {
                HarnessCreateApplicationConnectionScreen(
                    options = listOf(genericTextOption()),
                    mutationState = HarnessApplicationsMutationState.Idle,
                    onCreate = { applicationId, displayName, packageName, signer, useCaseId, presetId, presetRevision ->
                        submission =
                            ConnectionSubmission(
                                applicationId = applicationId,
                                displayName = displayName,
                                packageName = packageName,
                                signerSha256 = signer,
                                useCaseId = useCaseId,
                                presetId = presetId,
                                presetRevision = presetRevision,
                            )
                    },
                    onReload = {},
                    onClearFeedback = {},
                    onDone = {},
                )
            }
        }

        composeRule.onNodeWithTag("connection-display-name").performTextInput("Hello Harnex")
        composeRule.onNodeWithTag("connection-application-id").performTextInput("hello-harnex")
        composeRule.onNodeWithTag("connection-package-name").performTextInput("io.github.daniele21.harnex.hello")
        composeRule.onNodeWithTag("connection-signer-sha256").performTextInput("a".repeat(64))
        composeRule.onNodeWithTag("create-application-connection").performScrollToNode(hasTestTag("connection-create"))
        composeRule.onNodeWithTag("connection-create").assertIsEnabled().performClick()

        composeRule.runOnIdle {
            assertNotNull(submission)
            val submitted = requireNotNull(submission)
            assertEquals("hello-harnex", submitted.applicationId)
            assertEquals("Hello Harnex", submitted.displayName)
            assertEquals("io.github.daniele21.harnex.hello", submitted.packageName)
            assertEquals("a".repeat(64), submitted.signerSha256)
            assertEquals("generic-text-generation", submitted.useCaseId)
            assertEquals("qwen35-text-quality", submitted.presetId)
            assertEquals(1, submitted.presetRevision)
        }
    }

    @Test
    fun createConnectionAlwaysShowsMutationOutcomeNearAction() {
        val mutationState = mutableStateOf<HarnessApplicationsMutationState>(HarnessApplicationsMutationState.Saving)
        composeRule.setContent {
            HarnessTheme(darkTheme = false) {
                HarnessCreateApplicationConnectionScreen(
                    options = listOf(genericTextOption()),
                    mutationState = mutationState.value,
                    onCreate = { _, _, _, _, _, _, _ -> },
                    onReload = {},
                    onClearFeedback = {},
                    onDone = {},
                )
            }
        }

        composeRule.onNodeWithTag("create-application-connection").performScrollToNode(hasTestTag("connection-create"))
        composeRule.onNodeWithTag("connection-create").assertTextEquals("Creating connection…")
        composeRule.onNodeWithTag("create-application-connection").performScrollToNode(hasText("Saving"))
        composeRule.onNodeWithText("Saving").assertExists()
        holdForMediaEvidence()
        captureAuthorizationEvidence("create-saving")

        composeRule.runOnIdle {
            mutationState.value = HarnessApplicationsMutationState.Saved(
                "Application connection created and enabled.",
            )
        }
        composeRule.onNodeWithTag("create-application-connection").performScrollToNode(hasText("Connection ready"))
        composeRule.onNodeWithText("Connection ready").assertExists()
        composeRule.onNodeWithText("Application connection created and enabled.").assertExists()
        holdForMediaEvidence()
        captureAuthorizationEvidence("create-saved")

        composeRule.runOnIdle {
            mutationState.value = HarnessApplicationsMutationState.Failed(
                "Package name is already connected",
            )
        }
        composeRule.onNodeWithTag("create-application-connection").performScrollToNode(hasText("Connection not created"))
        composeRule.onNodeWithText("Connection not created").assertExists()
        composeRule.onNodeWithText("Package name is already connected").assertExists()
        holdForMediaEvidence()
        captureAuthorizationEvidence("create-failed")
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
    private fun genericTextOption() = HarnessConnectionUseCaseOption(
        useCaseId = "generic-text-generation",
        useCaseRevision = 1,
        displayName = "Generic text generation",
        description = "Run bounded stateless local text generation",
        presets = listOf(
            HarnessConnectionPresetOption(
                presetId = "qwen35-text-quality",
                revision = 1,
                displayName = "Quality",
                description = "General-purpose non-thinking local text generation",
            ),
        ),
    )
}
