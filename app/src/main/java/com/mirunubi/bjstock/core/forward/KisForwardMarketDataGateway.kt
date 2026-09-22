package com.mirunubi.bjstock.core.forward

import com.mirunubi.bjstock.core.kis.KisCredentialStore
import com.mirunubi.bjstock.core.kis.KisSettingsStore
import com.mirunubi.bjstock.core.kis.market.KisMarketException
import com.mirunubi.bjstock.core.kis.market.KisMarketErrorKind
import com.mirunubi.bjstock.core.marketdata.HistoricalSyncErrorKind
import com.mirunubi.bjstock.core.marketdata.HistoricalSyncException
import com.mirunubi.bjstock.core.marketdata.MarketDataLocalRepository
import com.mirunubi.bjstock.core.marketdata.SyncDailyBarsFromLatestUseCase
import com.mirunubi.bjstock.core.marketdata.SyncHistoricalDailyBarsUseCase
import java.time.LocalDate

/**
 * Production market-data gateway for forward tests.
 * Syncs universe daily bars only — never full KOSPI/KOSDAQ master download.
 */
class KisForwardMarketDataGateway(
    private val credentials: KisCredentialStore,
    private val settings: KisSettingsStore,
    private val localRepository: MarketDataLocalRepository,
    private val syncFromLatest: SyncDailyBarsFromLatestUseCase,
    private val historicalSync: SyncHistoricalDailyBarsUseCase,
) : ForwardMarketDataGateway {
    override suspend fun ensureCredentials(): Boolean {
        val environment = settings.selectedEnvironment()
        return credentials.hasCredentials(environment)
    }

    override suspend fun syncUniverseTo(
        instrumentIds: List<Long>,
        throughDate: LocalDate,
    ): MarketSyncOutcome {
        for (instrumentId in instrumentIds.sorted()) {
            try {
                val latest = localRepository.findLatest(instrumentId)
                if (latest == null) {
                    val start = throughDate.minusDays(ForwardTestConfig.HISTORY_PREPARE_CALENDAR_DAYS)
                    historicalSync(instrumentId, start, throughDate)
                } else {
                    syncFromLatest(instrumentId, throughDate)
                }
            } catch (ex: HistoricalSyncException) {
                return MarketSyncOutcome(
                    success = false,
                    errorCode = when (ex.kind) {
                        HistoricalSyncErrorKind.INSTRUMENT_NOT_FOUND ->
                            ForwardErrorCode.DATA_INTEGRITY_ERROR.name
                        else -> ForwardErrorCode.NETWORK_FAILURE.name
                    },
                    errorMessage = ForwardTestOrchestrator.sanitizeError(ex.publicMessage),
                    retryable = ex.kind != HistoricalSyncErrorKind.INSTRUMENT_NOT_FOUND,
                )
            } catch (ex: KisMarketException) {
                val auth = ex.kind == KisMarketErrorKind.AUTHENTICATION
                return MarketSyncOutcome(
                    success = false,
                    errorCode = if (auth) {
                        ForwardErrorCode.AUTH_REQUIRED.name
                    } else {
                        ForwardErrorCode.NETWORK_FAILURE.name
                    },
                    errorMessage = ForwardTestOrchestrator.sanitizeError(ex.publicMessage),
                    retryable = !auth,
                )
            } catch (ex: Exception) {
                return MarketSyncOutcome(
                    success = false,
                    errorCode = ForwardErrorCode.NETWORK_FAILURE.name,
                    errorMessage = ForwardTestOrchestrator.sanitizeError(
                        ex.message ?: "network failure",
                    ),
                    retryable = true,
                )
            }
        }
        return MarketSyncOutcome(success = true)
    }

    suspend fun prepareHistory(
        instrumentIds: List<Long>,
        startDate: LocalDate,
    ): MarketSyncOutcome {
        val from = startDate.minusDays(ForwardTestConfig.HISTORY_PREPARE_CALENDAR_DAYS)
        for (instrumentId in instrumentIds.sorted()) {
            try {
                historicalSync(instrumentId, from, startDate)
            } catch (ex: Exception) {
                return MarketSyncOutcome(
                    success = false,
                    errorCode = ForwardErrorCode.NETWORK_FAILURE.name,
                    errorMessage = ForwardTestOrchestrator.sanitizeError(
                        ex.message ?: "history prepare failed",
                    ),
                    retryable = true,
                )
            }
        }
        return MarketSyncOutcome(success = true)
    }
}
