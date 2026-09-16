package io.github.daniele21.localllm.phonetest

import android.content.Context
import io.github.daniele21.localllm.contracts.UseCaseId
import org.json.JSONArray
import org.json.JSONObject
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
    private val isoDate = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
    private val dayMonthSlashDate = Regex("^[0-9]{1,2}/[0-9]{1,2}/[0-9]{4}$")
    private val dayMonthDashDate = Regex("^[0-9]{1,2}-[0-9]{1,2}-[0-9]{4}$")

    fun outputOrNull(prompt: String): String? = when {
        "\"task\":\"select-transaction-schema\"" in prompt -> schemaOutput(prompt)
        "\"task\":\"interpret-transaction-source\"" in prompt -> interpretationOutput(prompt)
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

    private fun interpretationOutput(prompt: String): String {
        val request = runCatching { JSONObject(prompt) }.getOrNull()
            ?: return ambiguousInterpretation("layout")
        val sheets = request.optJSONObject("document")?.optJSONArray("sheets")
        val sheet = sheets?.optJSONObject(0)
            ?: return ambiguousInterpretation("sheet")
        val resolvedSheetId = sheet.optString("id").takeIf { it.isNotBlank() }
            ?: return ambiguousInterpretation("sheet")
        val rows = sheet.optJSONArray("rows")
            ?: return ambiguousInterpretation("layout")
        val headerRow = rows.optJSONObject(0)
            ?: return ambiguousInterpretation("layout")
        val firstDataRow = rows.optJSONObject(1)
            ?: return ambiguousInterpretation("layout")
        val headerCells = headerRow.optJSONArray("cells")
            ?: return ambiguousInterpretation("layout")
        val firstDataCells = firstDataRow.optJSONArray("cells")
            ?: return ambiguousInterpretation("layout")
        val headerRowNumber = headerRow.optInt("rowNumber", 1)
        val firstDataRowNumber = firstDataRow.optInt("rowNumber", headerRowNumber + 1)
        if (firstDataRowNumber <= headerRowNumber) return ambiguousInterpretation("layout")

        if (headerCells.length() == 1 && firstDataCells.length() == 1) {
            return delimitedCellInterpretation(
                sheetId = resolvedSheetId,
                headerRowNumber = headerRowNumber,
                firstDataRowNumber = firstDataRowNumber,
                headerCell = headerCells.optStringValue(0),
                firstDataCell = firstDataCells.optStringValue(0),
            ) ?: ambiguousInterpretation("layout")
        }

        if (headerCells.length() >= 3 && firstDataCells.length() >= 3) {
            return gridInterpretation(
                sheetId = resolvedSheetId,
                headerRowNumber = headerRowNumber,
                firstDataRowNumber = firstDataRowNumber,
                firstDataCells = firstDataCells,
            ) ?: ambiguousInterpretation("date")
        }

        return ambiguousInterpretation("layout")
    }

    private fun gridInterpretation(sheetId: String, headerRowNumber: Int, firstDataRowNumber: Int, firstDataCells: JSONArray): String? {
        val parser = dateParser(firstDataCells.optStringValue(0)) ?: return null
        val amountValue = firstDataCells.optStringValue(2)?.trim()?.replace(',', '.') ?: return null
        val polarity = when {
            amountValue.startsWith("-") -> "signed-negative-expense"
            amountValue.toDoubleOrNull() != null -> "signed-positive-expense"
            else -> return null
        }
        return resolvedInterpretation(
            sheetId = sheetId,
            layout = JSONObject()
                .put("kind", "grid")
                .put("headerRowNumber", headerRowNumber)
                .put("firstDataRowNumber", firstDataRowNumber),
            dateParser = parser,
            dateColumnIndex = 0,
            descriptionColumnIndexes = intArrayOf(1),
            amount = JSONObject()
                .put("strategy", polarity)
                .put("columnIndex", 2),
        )
    }

    private fun delimitedCellInterpretation(
        sheetId: String,
        headerRowNumber: Int,
        firstDataRowNumber: Int,
        headerCell: String?,
        firstDataCell: String?,
    ): String? {
        if (headerCell == null || firstDataCell == null) return null
        val candidates = listOf(';', '|', '\t', ',')
        val delimiter = candidates.firstOrNull { candidate ->
            headerCell.count { it == candidate } >= 2 &&
                firstDataCell.count { it == candidate } == headerCell.count { it == candidate }
        } ?: return null
        val stripOuterQuotes = headerCell.startsWith('"') && headerCell.endsWith('"')
        val normalizedFirstData = if (stripOuterQuotes && firstDataCell.startsWith('"') && firstDataCell.endsWith('"')) {
            firstDataCell.substring(1, firstDataCell.length - 1)
        } else {
            firstDataCell
        }
        val logicalCells = normalizedFirstData.split(delimiter)
        if (logicalCells.size < 4) return null
        val parser = dateParser(logicalCells[0]) ?: return null

        return resolvedInterpretation(
            sheetId = sheetId,
            layout = JSONObject()
                .put("kind", "delimited-cell")
                .put("sourceColumnIndex", 0)
                .put("delimiter", delimiter.toString())
                .put("stripOuterQuotes", stripOuterQuotes)
                .put("headerRowNumber", headerRowNumber)
                .put("firstDataRowNumber", firstDataRowNumber),
            dateParser = parser,
            dateColumnIndex = 0,
            descriptionColumnIndexes = intArrayOf(1),
            amount = JSONObject()
                .put("strategy", "debit-credit")
                .put("debitColumnIndex", 2)
                .put("creditColumnIndex", 3),
        )
    }

    private fun resolvedInterpretation(
        sheetId: String,
        layout: JSONObject,
        dateParser: String,
        dateColumnIndex: Int,
        descriptionColumnIndexes: IntArray,
        amount: JSONObject,
    ): String {
        val descriptionIndexes = JSONArray()
        descriptionColumnIndexes.forEach { descriptionIndexes.put(it) }
        return JSONObject()
            .put("status", "resolved")
            .put(
                "plan",
                JSONObject()
                    .put("contractVersion", 1)
                    .put("sheetId", sheetId)
                    .put("layout", layout)
                    .put(
                        "date",
                        JSONObject()
                            .put("columnIndex", dateColumnIndex)
                            .put("parser", dateParser),
                    )
                    .put("description", JSONObject().put("columnIndexes", descriptionIndexes))
                    .put("amount", amount),
            )
            .toString()
    }

    private fun ambiguousInterpretation(area: String): String = JSONObject()
        .put("status", "ambiguous")
        .put("ambiguities", JSONArray().put(area))
        .toString()

    private fun dateParser(value: String?): String? {
        val normalized = value?.trim() ?: return null
        return when {
            isoDate.matches(normalized) -> "iso-date"
            dayMonthSlashDate.matches(normalized) -> "dmy-slash"
            dayMonthDashDate.matches(normalized) -> "dmy-dash"
            else -> null
        }
    }

    private fun JSONArray.optStringValue(index: Int): String? = opt(index).takeIf { it != null && it !== JSONObject.NULL }?.toString()

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
