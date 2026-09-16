package io.github.daniele21.localllm.phonetest

import org.json.JSONArray
import org.json.JSONObject

private data class EmulatorE2eAuraInterpretationSource(
    val sheetId: String,
    val headerRowNumber: Int,
    val firstDataRowNumber: Int,
    val headerCells: JSONArray,
    val firstDataCells: JSONArray,
)

private data class EmulatorE2eAuraDelimitedCellShape(val delimiter: Char, val stripOuterQuotes: Boolean, val logicalCells: List<String>)

/** Emulator-only deterministic responder for Aura's declarative source-interpretation contract. */
internal object EmulatorE2eAuraInterpretationResponder {
    private val isoDate = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
    private val dayMonthSlashDate = Regex("^[0-9]{1,2}/[0-9]{1,2}/[0-9]{4}$")
    private val dayMonthDashDate = Regex("^[0-9]{1,2}-[0-9]{1,2}-[0-9]{4}$")
    private val delimiterCandidates = listOf(';', '|', '\t', ',')

    fun output(prompt: String): String {
        val source = sourceOrNull(prompt)
        return when {
            source == null -> ambiguousInterpretation("layout")

            source.headerCells.length() == 1 && source.firstDataCells.length() == 1 ->
                delimitedCellInterpretation(source) ?: ambiguousInterpretation("layout")

            source.headerCells.length() >= 3 && source.firstDataCells.length() >= 3 ->
                gridInterpretation(source) ?: ambiguousInterpretation("date")

            else -> ambiguousInterpretation("layout")
        }
    }

    private fun sourceOrNull(prompt: String): EmulatorE2eAuraInterpretationSource? {
        val request = runCatching { JSONObject(prompt) }.getOrNull()
        val sheet = request?.optJSONObject("document")?.optJSONArray("sheets")?.optJSONObject(0)
        val rows = sheet?.optJSONArray("rows")
        val headerRow = rows?.optJSONObject(0)
        val firstDataRow = rows?.optJSONObject(1)
        val sheetId = sheet?.optString("id")?.takeIf { it.isNotBlank() }
        val headerCells = headerRow?.optJSONArray("cells")
        val firstDataCells = firstDataRow?.optJSONArray("cells")
        val headerRowNumber = headerRow?.optInt("rowNumber", 1)
        val firstDataRowNumber = headerRowNumber?.let { firstDataRow?.optInt("rowNumber", it + 1) }
        val hasOrderedRows = headerRowNumber != null && firstDataRowNumber != null && firstDataRowNumber > headerRowNumber
        val isComplete = sheetId != null && headerCells != null && firstDataCells != null && hasOrderedRows

        return if (!isComplete) {
            null
        } else {
            EmulatorE2eAuraInterpretationSource(
                sheetId = requireNotNull(sheetId),
                headerRowNumber = requireNotNull(headerRowNumber),
                firstDataRowNumber = requireNotNull(firstDataRowNumber),
                headerCells = requireNotNull(headerCells),
                firstDataCells = requireNotNull(firstDataCells),
            )
        }
    }

    private fun gridInterpretation(source: EmulatorE2eAuraInterpretationSource): String? {
        val parser = dateParser(cellValue(source.firstDataCells, 0))
        val amountValue = cellValue(source.firstDataCells, 2)?.trim()?.replace(',', '.')
        val polarity = when {
            amountValue?.startsWith("-") == true -> "signed-negative-expense"
            amountValue?.toDoubleOrNull() != null -> "signed-positive-expense"
            else -> null
        }
        return if (parser == null || polarity == null) {
            null
        } else {
            resolvedInterpretation(
                sheetId = source.sheetId,
                layout = JSONObject()
                    .put("kind", "grid")
                    .put("headerRowNumber", source.headerRowNumber)
                    .put("firstDataRowNumber", source.firstDataRowNumber),
                dateParser = parser,
                dateColumnIndex = 0,
                descriptionColumnIndexes = intArrayOf(1),
                amount = JSONObject()
                    .put("strategy", polarity)
                    .put("columnIndex", 2),
            )
        }
    }

    private fun delimitedCellInterpretation(source: EmulatorE2eAuraInterpretationSource): String? {
        val shape = delimitedCellShape(
            headerCell = cellValue(source.headerCells, 0),
            firstDataCell = cellValue(source.firstDataCells, 0),
        )
        val parser = shape?.logicalCells?.firstOrNull()?.let(::dateParser)

        return if (shape == null || parser == null) {
            null
        } else {
            resolvedInterpretation(
                sheetId = source.sheetId,
                layout = JSONObject()
                    .put("kind", "delimited-cell")
                    .put("sourceColumnIndex", 0)
                    .put("delimiter", shape.delimiter.toString())
                    .put("stripOuterQuotes", shape.stripOuterQuotes)
                    .put("headerRowNumber", source.headerRowNumber)
                    .put("firstDataRowNumber", source.firstDataRowNumber),
                dateParser = parser,
                dateColumnIndex = 0,
                descriptionColumnIndexes = intArrayOf(1),
                amount = JSONObject()
                    .put("strategy", "debit-credit")
                    .put("debitColumnIndex", 2)
                    .put("creditColumnIndex", 3),
            )
        }
    }

    private fun delimitedCellShape(headerCell: String?, firstDataCell: String?): EmulatorE2eAuraDelimitedCellShape? {
        if (headerCell == null || firstDataCell == null) return null
        val delimiter = matchingDelimiter(headerCell, firstDataCell) ?: return null
        val stripOuterQuotes = hasOuterQuotes(headerCell)
        val logicalCells = normalizeDelimitedCell(firstDataCell, stripOuterQuotes).split(delimiter)
        if (logicalCells.size < 4) return null

        return EmulatorE2eAuraDelimitedCellShape(
            delimiter = delimiter,
            stripOuterQuotes = stripOuterQuotes,
            logicalCells = logicalCells,
        )
    }

    private fun matchingDelimiter(headerCell: String, firstDataCell: String): Char? = delimiterCandidates.firstOrNull { candidate ->
        val headerCount = headerCell.count { it == candidate }
        headerCount >= 2 && firstDataCell.count { it == candidate } == headerCount
    }

    private fun hasOuterQuotes(value: String): Boolean = value.startsWith('"') && value.endsWith('"')

    private fun normalizeDelimitedCell(value: String, stripOuterQuotes: Boolean): String =
        if (stripOuterQuotes && hasOuterQuotes(value)) value.substring(1, value.length - 1) else value

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
        val normalized = value?.trim()
        return when {
            normalized == null -> null
            isoDate.matches(normalized) -> "iso-date"
            dayMonthSlashDate.matches(normalized) -> "dmy-slash"
            dayMonthDashDate.matches(normalized) -> "dmy-dash"
            else -> null
        }
    }

    private fun cellValue(cells: JSONArray, index: Int): String? =
        cells.opt(index).takeIf { it != null && it !== JSONObject.NULL }?.toString()
}
