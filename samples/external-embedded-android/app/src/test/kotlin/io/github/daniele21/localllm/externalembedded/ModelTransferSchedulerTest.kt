package io.github.daniele21.localllm.externalembedded

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelTransferSchedulerTest {
    @Test
    fun missingUserInitiatedJobPermissionIdentifiesOutdatedApk() {
        assertEquals(
            "Installed APK lacks download permission · install updated APK",
            ModelTransferScheduler.downloadSecurityStatus(uidt = true, uidtPermissionGranted = false),
        )
    }

    @Test
    fun grantedPermissionDoesNotBlameNotificationOrBatterySettings() {
        assertEquals(
            "Android rejected background download · collect device diagnostics",
            ModelTransferScheduler.downloadSecurityStatus(uidt = true, uidtPermissionGranted = true),
        )
    }

    @Test
    fun olderAndroidReportsForegroundServiceRejectionSeparately() {
        assertEquals(
            "Android rejected foreground download · collect device diagnostics",
            ModelTransferScheduler.downloadSecurityStatus(uidt = false, uidtPermissionGranted = true),
        )
    }
}
