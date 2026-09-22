package com.mirunubi.bjstock.core.forward

import com.mirunubi.bjstock.core.audit.ApiErrorLogService
import com.mirunubi.bjstock.core.audit.KisApiErrorMapper
import com.mirunubi.bjstock.core.kis.KisCredentialStore
import com.mirunubi.bjstock.core.kis.KisSettingsStore
import com.mirunubi.bjstock.core.kis.market.KisMarketErrorKind
import com.mirunubi.bjstock.core.kis.market.KisMarketException
import com.mirunubi.bjstock.core.marketdata.HistoricalSyncErrorKind
import com.mirunubi.bjstock.core.marketdata.HistoricalSyncException
import com.mirunubi.bjstock.core.marketdata.MarketDataLocalRepository
import com.mirunubi.bjstock.core.marketdata.SyncDailyBarsFromLatestUseCase
import com.mirunubi.bjstock.core.marketdata.SyncHistoricalDailyBarsUseCase
import com.mirunubi.bjstock.core.model.ApiErrorProvider
import com.mirunubi.bjstock.core.model.ApiErrorType
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
    private val apiErrorLog: ApiErrorLogService? = null,
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
                recordSyncError(ex)
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
                recordMarketError(ex)
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
                recordGenericError(ex.message ?: "network failure")
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
                recordGenericError(ex.message ?: "history prepare failed")
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

    private suspend fun recordMarketError(ex: KisMarketException) {
        val log = apiErrorLog ?: return
        runCatching {
            log.record(
                provider = ApiErrorProvider.KIS,
                operation = "KIS_FORWARD_SYNC",
                errorType = KisApiErrorMapper.fromMarketKind(ex.kind),
                safeMessage = ex.publicMessage,
                retryable = KisApiErrorMapper.isRetryable(ex.kind),
                httpStatus = ex.audit?.httpCode,
                businessCode = ex.audit?.msgCd,
            )
        }
    }

    private suspend fun recordSyncError(ex: HistoricalSyncException) {
        val log = apiErrorLog ?: return
        runCatching {
            log.record(
                provider = ApiErrorProvider.KIS,
                operation = "KIS_HISTORICAL_SYNC",
                errorType = when (ex.kind) {
                    HistoricalSyncErrorKind.INSTRUMENT_NOT_FOUND -> ApiErrorType.KIS_BUSINESS_ERROR
                    else -> ApiErrorType.NETWORK_TIMEOUT
                },
                safeMessage = ex.publicMessage,
                retryable = ex.kind != HistoricalSyncErrorKind.INSTRUMENT_NOT_FOUND,
            )
        }
    }

    private suspend fun recordGenericError(message: String) {
        val log = apiErrorLog ?: return
        runCatching {
            log.record(
                provider = ApiErrorProvider.KIS,
                operation = "KIS_FORWARD_SYNC",
                errorType = ApiErrorType.NETWORK_TIMEOUT,
                safeMessage = message,
                retryable = true,
            )
        }
    }
}
