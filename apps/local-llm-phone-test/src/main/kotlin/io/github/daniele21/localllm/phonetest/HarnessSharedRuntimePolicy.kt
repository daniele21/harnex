package io.github.daniele21.localllm.phonetest

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import io.github.daniele21.localllm.contracts.ApplicationId
import io.github.daniele21.localllm.contracts.UseCaseId
import io.github.daniele21.localllm.integration.servicehost.AuthorizedClientPolicy
import io.github.daniele21.localllm.integration.servicehost.SigningCertificateSha256
import io.github.daniele21.localllm.models.ApplicationRegistrationState
import io.github.daniele21.localllm.models.HostControlPlaneState
import java.security.MessageDigest

/** Exact package/use-case policy for the proof host and external clients registered in the control plane. */
internal object HarnessSharedRuntimePolicy {
    fun authorizedClients(context: Context): List<AuthorizedClientPolicy> {
        val hostSigningCertificates = currentPackageSigningCertificates(context, context.packageName, includeHistory = true)
        val debugClientPackageTopology =
            HarnessSharedRuntimeBindings.usesDebugClientPackageTopology(context.packageName)
        val internal = AuthorizedClientPolicy(
            packageName = context.packageName,
            applicationId = HarnessRuntimeGraph.APPLICATION_ID,
            allowedUseCases = HarnessRuntimePurpose.entries.map(HarnessRuntimePurpose::useCaseId).toSet(),
            acceptedSigningCertificates = hostSigningCertificates,
        )
        val consoleClients = HarnessSharedRuntimeBindings.consolePackages(debugClientPackageTopology).map { packageName ->
            AuthorizedClientPolicy(
                packageName = packageName,
                applicationId = HarnessSharedRuntimeBindings.consoleApplicationId,
                allowedUseCases = HarnessSharedRuntimeBindings.consoleUseCases,
                acceptedSigningCertificates = hostSigningCertificates,
            )
        }
        val redactGuardClients = independentlySignedClients(
            context = context,
            packageNames = HarnessSharedRuntimeBindings.redactGuardPackages(debugClientPackageTopology),
            applicationId = HarnessSharedRuntimeBindings.redactGuardApplicationId,
            allowedUseCases = HarnessSharedRuntimeBindings.redactGuardUseCases,
        )
        val auraClients = independentlySignedClients(
            context = context,
            packageNames = HarnessSharedRuntimeBindings.auraPackages(debugClientPackageTopology),
            applicationId = HarnessSharedRuntimeBindings.auraApplicationId,
            allowedUseCases = HarnessSharedRuntimeBindings.auraUseCases,
        )
        val releaseEvidenceClient = if (debugClientPackageTopology) {
            emptyList()
        } else {
            listOf(
                AuthorizedClientPolicy(
                    packageName = HarnessSharedRuntimeBindings.SR6_RELEASE_CONSUMER_PACKAGE,
                    applicationId = HarnessSharedRuntimeBindings.consoleApplicationId,
                    allowedUseCases = HarnessSharedRuntimeBindings.consoleUseCases,
                    acceptedSigningCertificates = hostSigningCertificates,
                ),
            )
        }
        // Independently signed products appear in this bootstrap list only so startup reconciliation can observe
        // their source-backed package/signer identity. They are deliberately excluded from static live trust until
        // the user explicitly authorizes the persisted Control Plane registration.
        return listOf(internal) + consoleClients + redactGuardClients + auraClients + releaseEvidenceClient
    }

    /**
     * Projects the current persisted app-connection state into the Binder security boundary.
     * Same-publisher built-ins keep their reviewed signing policy. Independently signed consumers use the exact
     * package/signing identity persisted after explicit user authorization.
     */
    fun liveAuthorizedClients(
        basePolicies: Collection<AuthorizedClientPolicy>,
        state: HostControlPlaneState,
    ): List<AuthorizedClientPolicy> {
        val internal = basePolicies.filter { it.applicationId == HarnessRuntimeGraph.APPLICATION_ID }
        val controlPlaneSignerApplicationIds = setOf(
            HarnessSharedRuntimeBindings.redactGuardApplicationId,
            HarnessSharedRuntimeBindings.auraApplicationId,
        )
        val builtInApplicationIds = basePolicies
            .asSequence()
            .map(AuthorizedClientPolicy::applicationId)
            .filter { it != HarnessRuntimeGraph.APPLICATION_ID && it !in controlPlaneSignerApplicationIds }
            .toSet()
        val enabledApplications = state.applications
            .filter { it.state == ApplicationRegistrationState.AUTHORIZED }
            .associateBy { it.applicationId }
        val enabledBuiltIns = basePolicies.filter { policy ->
            policy.applicationId in builtInApplicationIds && enabledApplications.containsKey(policy.applicationId)
        }
        val latestBindings = state.bindings
            .groupBy { it.bindingId }
            .mapValues { (_, revisions) -> revisions.maxBy { it.revision } }
            .values
        val approvedExternal = enabledApplications.values
            .filter { it.applicationId !in builtInApplicationIds }
            .mapNotNull { application ->
                val allowed = latestBindings
                    .filter { it.applicationId == application.applicationId && it.enabled }
                    .map { it.useCaseId }
                    .toSet()
                if (allowed.isEmpty()) {
                    null
                } else {
                    AuthorizedClientPolicy(
                        packageName = application.packageName,
                        applicationId = application.applicationId,
                        allowedUseCases = allowed,
                        acceptedSigningCertificates = setOf(SigningCertificateSha256.parse(application.signerSha256)),
                    )
                }
            }
        return (internal + enabledBuiltIns + approvedExternal).also { policies ->
            require(policies.map(AuthorizedClientPolicy::packageName).distinct().size == policies.size) {
                "Live application connections must have unique package names"
            }
        }
    }

    fun builtInOmbraControlPlaneSpec(policies: Collection<AuthorizedClientPolicy>): HarnessBuiltInControlPlaneSpec {
        val applications = requirementsForUseCases(
            policies = policies,
            useCaseIds = setOf(HarnessSharedRuntimeBindings.ombraUseCaseId),
            independentlySignedApplicationIds = setOf(HarnessSharedRuntimeBindings.redactGuardApplicationId),
        )
        return HarnessBuiltInControlPlaneSpec.ombra(applications)
    }

    fun builtInGenericTextControlPlaneSpec(policies: Collection<AuthorizedClientPolicy>): HarnessBuiltInControlPlaneSpec {
        val applications = requirementsForUseCases(
            policies = policies,
            useCaseIds = setOf(HarnessSharedRuntimeBindings.genericTextUseCaseId),
            independentlySignedApplicationIds = emptySet(),
        )
        return HarnessBuiltInControlPlaneSpec.genericText(applications)
    }

    /**
     * Seeds Aura only when its exact installed package/signing identity was observed. The independently signed Aura
     * registration starts PENDING and does not enter live Binder trust until explicit control-plane authorization.
     */
    fun builtInAuraControlPlaneSpecs(policies: Collection<AuthorizedClientPolicy>): List<HarnessBuiltInControlPlaneSpec> {
        val auraPolicies = policies.filter { policy ->
            policy.applicationId == HarnessSharedRuntimeBindings.auraApplicationId &&
                policy.allowedUseCases.any(HarnessSharedRuntimeBindings.auraUseCases::contains)
        }
        if (auraPolicies.isEmpty()) return emptyList()

        val applications = requirementsForUseCases(
            policies = auraPolicies,
            useCaseIds = HarnessSharedRuntimeBindings.auraUseCases,
            independentlySignedApplicationIds = setOf(HarnessSharedRuntimeBindings.auraApplicationId),
        )
        return listOf(
            HarnessBuiltInControlPlaneSpec.auraSchema(applications),
            HarnessBuiltInControlPlaneSpec.auraCategory(applications),
        )
    }

    private fun requirementsForUseCases(
        policies: Collection<AuthorizedClientPolicy>,
        useCaseIds: Set<UseCaseId>,
        independentlySignedApplicationIds: Set<ApplicationId>,
    ): List<HarnessBuiltInApplicationRequirement> = policies
        .filter { policy -> policy.allowedUseCases.any(useCaseIds::contains) }
        .groupBy(AuthorizedClientPolicy::applicationId)
        .map { (applicationId, applicationPolicies) ->
            val independentlySigned = applicationId in independentlySignedApplicationIds
            val signersByPackage = applicationPolicies
                .groupBy(AuthorizedClientPolicy::packageName)
                .mapValues { (_, packagePolicies) ->
                    packagePolicies
                        .flatMap(AuthorizedClientPolicy::acceptedSigningCertificates)
                        .map(SigningCertificateSha256::hex)
                        .toSet()
                }
            HarnessBuiltInApplicationRequirement(
                applicationId = applicationId,
                acceptedPackageNames = signersByPackage.keys,
                acceptedSignerSha256 = signersByPackage.values.flatten().toSet(),
                displayName = harnessApplicationDisplayName(applicationId),
                initialState = if (independentlySigned) {
                    ApplicationRegistrationState.PENDING
                } else {
                    ApplicationRegistrationState.AUTHORIZED
                },
                allowObservedSignerChange = independentlySigned,
                acceptedSignerSha256ByPackage = signersByPackage,
            )
        }

    private fun independentlySignedClients(
        context: Context,
        packageNames: Set<String>,
        applicationId: ApplicationId,
        allowedUseCases: Set<UseCaseId>,
    ): List<AuthorizedClientPolicy> = packageNames.mapNotNull { packageName ->
        installedCurrentSigningCertificates(context, packageName)?.let { signingCertificates ->
            AuthorizedClientPolicy(
                packageName = packageName,
                applicationId = applicationId,
                allowedUseCases = allowedUseCases,
                acceptedSigningCertificates = signingCertificates,
            )
        }
    }

    private fun installedCurrentSigningCertificates(context: Context, packageName: String): Set<SigningCertificateSha256>? = runCatching {
        currentPackageSigningCertificates(context, packageName, includeHistory = false)
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun currentPackageSigningCertificates(
        context: Context,
        packageName: String,
        includeHistory: Boolean,
    ): Set<SigningCertificateSha256> {
        val packageManager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val packageInfo = packageManager.getPackageInfo(packageName, flags)
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = requireNotNull(packageInfo.signingInfo) { "Package signing information is unavailable" }
            if (includeHistory && !signingInfo.hasMultipleSigners()) {
                signingInfo.signingCertificateHistory.orEmpty().toList()
            } else {
                signingInfo.apkContentsSigners.orEmpty().toList()
            }
        } else {
            packageInfo.signatures.orEmpty().toList()
        }
        check(signatures.isNotEmpty()) { "Package signing certificate is unavailable" }
        return signatures.map(::sha256).toSet()
    }

    private fun sha256(signature: Signature): SigningCertificateSha256 {
        val digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
        val hex = digest.joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return SigningCertificateSha256.parse(hex)
    }
}

private fun harnessApplicationDisplayName(applicationId: ApplicationId): String = when (applicationId) {
    HarnessSharedRuntimeBindings.consoleApplicationId -> "Local LLM Console"
    HarnessSharedRuntimeBindings.redactGuardApplicationId -> "RedactGuard"
    HarnessSharedRuntimeBindings.auraApplicationId -> "Aura Finance"
    else -> applicationId.value
}
