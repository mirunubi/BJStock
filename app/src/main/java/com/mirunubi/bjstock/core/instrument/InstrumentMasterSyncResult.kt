package com.mirunubi.bjstock.core.instrument

import com.mirunubi.bjstock.core.model.Board
import java.time.Instant

data class InstrumentMasterSyncResult(
    val board: Board,
    val downloaded: Boolean,
    val total: Int,
    val parsed: Int,
    val skippedUnsupported: Int,
    val malformed: Int,
    val inserted: Int,
    val updated: Int,
    val deactivated: Int,
    val startedAt: Instant,
    val completedAt: Instant,
    val success: Boolean,
    val failureReason: String? = null,
)

class InstrumentMasterException(
    val kind: InstrumentMasterErrorKind,
    val publicMessage: String,
) : Exception(publicMessage)

enum class InstrumentMasterErrorKind {
    DOWNLOAD_FAILED,
    INCOMPLETE_MASTER,
    UNSUPPORTED_BOARD,
}
