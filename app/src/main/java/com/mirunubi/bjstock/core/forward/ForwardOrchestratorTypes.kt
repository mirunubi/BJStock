package com.mirunubi.bjstock.core.forward

import java.time.LocalDate

sealed class ForwardOrchestratorResult {
    data class Ok(
        val processedDates: List<LocalDate>,
        val message: String? = null,
    ) : ForwardOrchestratorResult()

    data class Blocked(
        val marketDate: LocalDate?,
        val errorCode: String,
        val errorMessage: String,
        val retryable: Boolean,
    ) : ForwardOrchestratorResult()

    data class NoOp(val reason: String) : ForwardOrchestratorResult()
}

enum class ForwardErrorCode {
    AUTH_REQUIRED,
    MISSING_TRADING_POLICY,
    INSUFFICIENT_WARMUP_DATA,
    EMPTY_UNIVERSE,
    INVALID_RUN,
    DATA_INTEGRITY_ERROR,
    SNAPSHOT_MISSING_PRICE,
    NETWORK_FAILURE,
    CYCLE_FAILED,
}

interface ForwardMarketDataGateway {
    suspend fun ensureCredentials(): Boolean

    /**
     * Sync daily bars for instruments through [throughDate].
     * Sequential per instrument. Returns false on hard failure.
     */
    suspend fun syncUniverseTo(
        instrumentIds: List<Long>,
        throughDate: LocalDate,
    ): MarketSyncOutcome
}

data class MarketSyncOutcome(
    val success: Boolean,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val retryable: Boolean = false,
)

/** Room-only gateway for unit tests / offline fixtures (no network). */
class LocalOnlyMarketDataGateway : ForwardMarketDataGateway {
    override suspend fun ensureCredentials(): Boolean = true

    override suspend fun syncUniverseTo(
        instrumentIds: List<Long>,
        throughDate: LocalDate,
    ): MarketSyncOutcome = MarketSyncOutcome(success = true)
}
