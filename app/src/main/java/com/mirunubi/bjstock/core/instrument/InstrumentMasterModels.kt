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

data class InstrumentMasterParseStats(
    val totalLines: Int,
    val parsedRows: Int,
    val invalidRows: Int,
    val duplicateSymbols: Int,
)

data class InstrumentMasterParseResult(
    val entries: List<InstrumentMasterEntry>,
    val stats: InstrumentMasterParseStats,
)
