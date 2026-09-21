package com.mirunubi.bjstock.core.instrument

import com.mirunubi.bjstock.core.model.Board

object InstrumentMasterConfig {
    const val MARKET_KRX = "KRX"
    const val KOSPI_URL =
        "https://new.real.download.dws.co.kr/common/master/kospi_code.mst.zip"
    const val KOSDAQ_URL =
        "https://new.real.download.dws.co.kr/common/master/kosdaq_code.mst.zip"
    const val KOSPI_TAIL_BYTES = 228
    const val KOSDAQ_TAIL_BYTES = 222
    const val FIRST_SYNC_MIN_PARSED = 100
    const val EXISTING_MIN_RATIO = 0.80
    const val MAX_INVALID_RATIO = 0.20

    fun url(board: Board): String = when (board) {
        Board.KOSPI -> KOSPI_URL
        Board.KOSDAQ -> KOSDAQ_URL
        Board.OTHER -> error("OTHER has no master download URL")
    }

    fun tailBytes(board: Board): Int = when (board) {
        Board.KOSPI -> KOSPI_TAIL_BYTES
        Board.KOSDAQ -> KOSDAQ_TAIL_BYTES
        Board.OTHER -> error("OTHER has no MST tail length")
    }
}

data class InstrumentMasterSyncPolicy(
    val firstSyncMinParsed: Int = InstrumentMasterConfig.FIRST_SYNC_MIN_PARSED,
    val existingMinRatio: Double = InstrumentMasterConfig.EXISTING_MIN_RATIO,
    val maxInvalidRatio: Double = InstrumentMasterConfig.MAX_INVALID_RATIO,
)
