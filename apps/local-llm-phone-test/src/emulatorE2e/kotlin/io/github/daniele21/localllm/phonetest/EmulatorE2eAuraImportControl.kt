package io.github.daniele21.localllm.phonetest

import android.content.Context
import io.github.daniele21.localllm.contracts.UseCaseId
import java.util.concurrent.atomic.AtomicBoolean

internal data class EmulatorE2eAuraControlResult(val success: Boolean, val detail: String)

/** Emulator-only Aura control surface layered on the canonical phone Control Plane owners. */
internal object EmulatorE2eAuraImportControl {
    fun authorize(context: Context): EmulatorE2eAuraControlResult {
        val access = HarnessRuntimeGraph.from(context).controlPlaneStore
        val gateway = StoreHarnessCustomPresetGateway(access)
        return when (
            val result = gateway.setApplicationConnectionEnabled(
                HarnessSetApplicationConnectionEnabledCommand(
                    applicationId = HarnessSharedRuntimeBindings.auraApplicationId.value,
                    enabled = true,
                ),
            )
        ) {
            is HarnessControlPlaneMutationResult.Success -> EmulatorE2eAuraControlResult(true, status(context))

            is HarnessControlPlaneMutationResult.Rejected -> EmulatorE2eAuraControlResult(false, result.message)

            is HarnessControlPlaneMutationResult.StaleRevision -> EmulatorE2eAuraControlResult(
                false,
                "stale_revision:${result.expectedRevision}:${result.actualRevision}",
            )
        }
    }

    fun setSchemaEnabled(context: Context, enabled: Boolean): EmulatorE2eAuraControlResult =
        setAssignmentEnabled(context, HarnessSharedRuntimeBindings.auraSchemaInferenceUseCaseId, enabled)

    fun setCategoryEnabled(context: Context, enabled: Boolean): EmulatorE2eAuraControlResult =
        setAssignmentEnabled(context, HarnessSharedRuntimeBindings.auraCategoryClassificationUseCaseId, enabled)

    fun status(context: Context): String {
        val access = HarnessRuntimeGraph.from(context).controlPlaneStore
        val snapshot = StoreHarnessCustomPresetGateway(access).snapshot()
        val aura = snapshot.applications.singleOrNull {
            it.applicationId == HarnessSharedRuntimeBindings.auraApplicationId.value
        }
        val schema = aura?.assignments?.singleOrNull {
            it.useCaseId == HarnessSharedRuntimeBindings.auraSchemaInferenceUseCaseId.value
        }
        val category = aura?.assignments?.singleOrNull {
            it.useCaseId == HarnessSharedRuntimeBindings.auraCategoryClassificationUseCaseId.value
        }
        return buildString {
            append("application=")
            append(aura?.status?.name ?: "ABSENT")
            append(";schema=")
            append(schema?.status?.name ?: "ABSENT")
            append(";category=")
            append(category?.status?.name ?: "ABSENT")
            append(";model_unavailable=")
            append(EmulatorE2eModelAvailabilityGate.isUnavailable())
        }
    }

    private fun setAssignmentEnabled(context: Context, useCaseId: UseCaseId, enabled: Boolean): EmulatorE2eAuraControlResult {
        val access = HarnessRuntimeGraph.from(context).controlPlaneStore
        return runCatching {
            access.transact { current ->
                val latest = current.bindings
                    .filter {
                        it.applicationId == HarnessSharedRuntimeBindings.auraApplicationId &&
                            it.useCaseId == useCaseId
                    }
                    .maxByOrNull { it.revision }
                    ?: error("Aura assignment is unavailable for ${useCaseId.value}")
                if (latest.enabled == enabled) return@transact current

                val anotherCurrentDefaultExists = current.currentBindings(latest.applicationId).any { binding ->
                    binding.useCaseId != useCaseId && binding.isDefault
                }
                val nextIsDefault = enabled &&
                    (
                        latest.isDefault ||
                            (useCaseId == HarnessSharedRuntimeBindings.auraSchemaInferenceUseCaseId && !anotherCurrentDefaultExists)
                        )
                val next = latest.copy(
                    revision = latest.revision + 1,
                    enabled = enabled,
                    isDefault = nextIsDefault,
                )
                val nextExposures = current.exposures
                    .filter {
                        it.bindingId == latest.bindingId &&
                            it.bindingRevision == latest.revision
                    }
                    .map { it.copy(bindingRevision = next.revision) }
                current.copy(
                    bindings = current.bindings + next,
                    exposures = current.exposures + nextExposures,
                )
            }
            EmulatorE2eAuraControlResult(true, status(context))
        }.getOrElse { error ->
            EmulatorE2eAuraControlResult(false, error.message ?: "control_plane_update_failed")
        }
    }
}

/** Emulator-only model-readiness switch. Production ModelStore behavior is unchanged. */
internal object EmulatorE2eModelAvailabilityGate {
    private val unavailable = AtomicBoolean(false)

    fun setUnavailable(value: Boolean) {
        unavailable.set(value)
    }

    fun isUnavailable(): Boolean = unavailable.get()

    fun reset() {
        unavailable.set(false)
    }
}

/** Deterministic JSON responder for Aura's constrained schema/interpretation/category use cases. */
internal object EmulatorE2eAuraImportResponder {
    private data class SchemaSelection(
        val sheet: String,
        val header: String,
        val date: String,
        val amount: String,
        val description: String,
    )

    private val sheetId = Regex("\\\"sheets\\\"\\s*:\\s*\\[\\s*\\{\\s*\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
    private val headerId = Regex("\\\"headerCandidates\\\"\\s*:\\s*\\[\\s*\\{\\s*\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
    private val dateCandidateId = Regex("\\\"dateCandidates\\\"\\s*:\\s*\\[\\s*\\{\\s*\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
    private val amountCandidateId = Regex("\\\"amountCandidates\\\"\\s*:\\s*\\[\\s*\\{\\s*\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
    private val descriptionColumnId = Regex("\\\"descriptionCandidateColumnIds\\\"\\s*:\\s*\\[\\s*\\\"([^\\\"]+)\\\"")
    private val categoryId = Regex("\\\"categories\\\"\\s*:\\s*\\[\\s*\\{\\s*\\\"id\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
    private val groupId = Regex("\\\"id\\\"\\s*:\\s*\\\"(group-[0-9]+)\\\"")

    fun outputOrNull(prompt: String): String? = when {
        "\"task\":\"select-transaction-schema\"" in prompt -> schemaOutput(prompt)
        "\"task\":\"interpret-transaction-source\"" in prompt -> EmulatorE2eAuraInterpretationResponder.output(prompt)
        "\"task\":\"classify-transaction-categories\"" in prompt -> categoryOutput(prompt)
        else -> null
    }

    private fun schemaOutput(prompt: String): String {
        val selection = schemaSelection(prompt)
            ?: return "{\"status\":\"ambiguous\",\"ambiguities\":[\"header\"]}"
        return buildString {
            append("{\"status\":\"resolved\",\"sheetId\":\"")
            append(jsonEscape(selection.sheet))
            append("\",\"headerCandidateId\":\"")
            append(jsonEscape(selection.header))
            append("\",\"dateCandidateId\":\"")
            append(jsonEscape(selection.date))
            append("\",\"descriptionColumnIds\":[\"")
            append(jsonEscape(selection.description))
            append("\"],\"amountCandidateId\":\"")
            append(jsonEscape(selection.amount))
            append("\"}")
        }
    }

    private fun schemaSelection(prompt: String): SchemaSelection? {
        val values = listOf(
            sheetId.find(prompt)?.groupValues?.get(1),
            headerId.find(prompt)?.groupValues?.get(1),
            dateCandidateId.find(prompt)?.groupValues?.get(1),
            amountCandidateId.find(prompt)?.groupValues?.get(1),
            descriptionColumnId.find(prompt)?.groupValues?.get(1),
        )
        if (values.any { it == null }) return null
        return SchemaSelection(
            sheet = requireNotNull(values[0]),
            header = requireNotNull(values[1]),
            date = requireNotNull(values[2]),
            amount = requireNotNull(values[3]),
            description = requireNotNull(values[4]),
        )
    }

    private fun categoryOutput(prompt: String): String {
        val category = categoryId.find(prompt)?.groupValues?.get(1)
            ?: return "{\"items\":[]}"
        val itemsSection = prompt.substringAfter("\"items\":", missingDelimiterValue = "")
        val ids = groupId.findAll(itemsSection).map { it.groupValues[1] }.distinct().toList()
        return ids.joinToString(prefix = "{\"items\":[", postfix = "]}") { id ->
            "{\"id\":\"${jsonEscape(id)}\",\"categoryId\":\"${jsonEscape(category)}\"}"
        }
    }

    private fun jsonEscape(value: String): String = buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
    }
}
