package com.mirunubi.bjstock.core.instrument

import androidx.room.withTransaction
import com.mirunubi.bjstock.core.database.BJStockDatabase
import com.mirunubi.bjstock.core.database.dao.InstrumentDao
import com.mirunubi.bjstock.core.database.entity.InstrumentEntity
import com.mirunubi.bjstock.core.model.Board
import java.time.Instant

class InstrumentMasterSynchronizer(
    private val downloader: InstrumentMasterDownloader,
    private val parser: KisMstParser,
    private val database: BJStockDatabase,
    private val instrumentDao: InstrumentDao,
    private val policy: InstrumentMasterSyncPolicy = InstrumentMasterSyncPolicy(),
    private val now: () -> Instant = { Instant.now() },
) {
    suspend fun sync(board: Board): InstrumentMasterSyncResult {
        if (board == Board.OTHER) {
            throw InstrumentMasterException(
                kind = InstrumentMasterErrorKind.UNSUPPORTED_BOARD,
                publicMessage = "OTHER has no instrument master",
            )
        }
        val startedAt = now()
        val existingActive = instrumentDao.countActiveByMarketAndBoard(
            InstrumentMasterConfig.MARKET_KRX,
            board,
        )
        val mstBytes = try {
            downloader.downloadMstBytes(board)
        } catch (error: InstrumentMasterException) {
            return failed(
                board = board,
                startedAt = startedAt,
                downloaded = false,
                parsed = 0,
                invalid = 0,
                reason = error.publicMessage,
            )
        }
        val parsed = parser.parse(mstBytes, board)
        val completeness = completenessFailure(parsed.stats, existingActive)
        if (completeness != null) {
            return failed(
                board = board,
                startedAt = startedAt,
                downloaded = true,
                parsed = parsed.stats.parsedRows,
                invalid = parsed.stats.invalidRows,
                reason = completeness,
            )
        }
        return database.withTransaction {
            persistBoard(board, parsed, startedAt)
        }
    }

    private suspend fun persistBoard(
        board: Board,
        parsed: InstrumentMasterParseResult,
        startedAt: Instant,
    ): InstrumentMasterSyncResult {
        val updatedAt = now()
        var inserted = 0
        var updated = 0
        val masterSymbols = HashSet<String>(parsed.entries.size)
        parsed.entries.forEach { entry ->
            masterSymbols += entry.symbol
            val existing = instrumentDao.findByMarketAndSymbol(entry.market, entry.symbol)
            if (existing == null) {
                instrumentDao.insert(
                    InstrumentEntity(
                        market = entry.market,
                        symbol = entry.symbol,
                        name = entry.name,
                        standardCode = entry.standardCode,
                        board = entry.board,
                        instrumentType = entry.instrumentType,
                        listedDate = entry.listedDate,
                        isActive = true,
                        createdAt = updatedAt,
                        updatedAt = updatedAt,
                    ),
                )
                inserted += 1
            } else {
                instrumentDao.update(
                    existing.copy(
                        name = entry.name,
                        standardCode = entry.standardCode,
                        board = entry.board,
                        instrumentType = entry.instrumentType,
                        listedDate = entry.listedDate ?: existing.listedDate,
                        isActive = true,
                        updatedAt = updatedAt,
                    ),
                )
                updated += 1
            }
        }
        var deactivated = 0
        instrumentDao.findActiveByMarketAndBoard(InstrumentMasterConfig.MARKET_KRX, board)
            .forEach { row ->
                if (row.symbol !in masterSymbols) {
                    instrumentDao.deactivateById(row.id, updatedAt)
                    deactivated += 1
                }
            }
        return InstrumentMasterSyncResult(
            board = board,
            downloaded = true,
            parsed = parsed.stats.parsedRows,
            inserted = inserted,
            updated = updated,
            deactivated = deactivated,
            invalid = parsed.stats.invalidRows,
            startedAt = startedAt,
            completedAt = now(),
            success = true,
        )
    }

    private fun completenessFailure(
        stats: InstrumentMasterParseStats,
        existingActive: Int,
    ): String? {
        if (existingActive == 0 && stats.parsedRows < policy.firstSyncMinParsed) {
            return "Incomplete master: parsed ${stats.parsedRows} < ${policy.firstSyncMinParsed}"
        }
        if (existingActive > 0 && stats.parsedRows < existingActive * policy.existingMinRatio) {
            return "Incomplete master: parsed ${stats.parsedRows} below 80% of $existingActive active"
        }
        if (stats.totalLines > 0 &&
            stats.invalidRows.toDouble() / stats.totalLines.toDouble() > policy.maxInvalidRatio
        ) {
            return "Incomplete master: invalid ratio too high"
        }
        return null
    }

    private fun failed(
        board: Board,
        startedAt: Instant,
        downloaded: Boolean,
        parsed: Int,
        invalid: Int,
        reason: String,
    ): InstrumentMasterSyncResult = InstrumentMasterSyncResult(
        board = board,
        downloaded = downloaded,
        parsed = parsed,
        inserted = 0,
        updated = 0,
        deactivated = 0,
        invalid = invalid,
        startedAt = startedAt,
        completedAt = now(),
        success = false,
        failureReason = reason,
    )
}
