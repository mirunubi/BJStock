package com.mirunubi.bjstock.core.instrument

import com.mirunubi.bjstock.core.model.Board
import com.mirunubi.bjstock.core.model.InstrumentType
import java.time.LocalDate

data class InstrumentMasterEntry(
    val symbol: String,
    val standardCode: String?,
    val name: String,
    val market: String,
    val board: Board,
    val instrumentType: InstrumentType,
    val listedDate: LocalDate?,
)

enum class MstRowCategory {
    /** Supported BJStock instrument (numeric 6-digit short code); eligible for persistence. */
    TARGET_VALID,

    /** Structurally valid source row whose security type is outside the supported universe. */
    SKIPPED_UNSUPPORTED,

    /** Broken, truncated, or unparseable source row. Counts toward the malformed-ratio guard. */
    MALFORMED,
}

data class InstrumentMasterParseStats(
    val totalLines: Int,
    val parsedRows: Int,
    val skippedUnsupportedRows: Int,
    val malformedRows: Int,
    val duplicateSymbols: Int,
)

data class InstrumentMasterParseResult(
    val entries: List<InstrumentMasterEntry>,
    val stats: InstrumentMasterParseStats,
)
