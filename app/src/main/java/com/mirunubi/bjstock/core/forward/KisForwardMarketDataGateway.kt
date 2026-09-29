package com.mirunubi.bjstock.core.forward

import com.mirunubi.bjstock.core.audit.ApiErrorLogService
import com.mirunubi.bjstock.core.audit.KisApiErrorMapper
import com.mirunubi.bjstock.core.error.AppErrorCode
import com.mirunubi.bjstock.core.error.AppErrorMapper
import com.mirunubi.bjstock.core.error.ErrorCategory
import com.mirunubi.bjstock.core.error.SafeAppError
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
                return historicalSyncFailure(ex, OPERATION_HISTORICAL_SYNC)
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
                return unrecognisedFailure(ex, OPERATION_FORWARD_SYNC)
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
            } catch (ex: KisMarketException) {
                recordGenericError(ex.message ?: "history prepare failed")
                return MarketSyncOutcome(
                    success = false,
                    errorCode = ForwardErrorCode.NETWORK_FAILURE.name,
                    errorMessage = ForwardTestOrchestrator.sanitizeError(
                        ex.message ?: "history prepare failed",
                    ),
                    retryable = true,
                )
            } catch (ex: HistoricalSyncException) {
                return historicalSyncFailure(ex, OPERATION_FORWARD_SYNC)
            } catch (ex: Exception) {
                return unrecognisedFailure(ex, OPERATION_FORWARD_SYNC)
            }
        }
        return MarketSyncOutcome(success = true)
    }

    private suspend fun historicalSyncFailure(
        ex: HistoricalSyncException,
        operation: String,
    ): MarketSyncOutcome {
        val code = AppErrorMapper.fromHistoricalSyncErrorKind(ex.kind)
        recordSyncError(ex, code, operation)
        return MarketSyncOutcome(
            success = false,
            errorCode = code.name,
            errorMessage = ForwardTestOrchestrator.sanitizeError(ex.publicMessage),
            retryable = code.isRetryableAutomatically,
        )
    }

    /** [Throwable.message] is never used; only the canonical safe message and class name. */
    private suspend fun unrecognisedFailure(ex: Exception, operation: String): MarketSyncOutcome {
        val error = AppErrorMapper.fromThrowable(ex, logicalEndpoint = operation)
        val message = listOfNotNull(
            error.safeMessage,
            error.diagnostics.exceptionType?.let { "($it)" },
        ).joinToString(" ")
        val retryable = error.code.isRetryableAutomatically
        recordUnrecognisedError(error, message, retryable, operation)
        return MarketSyncOutcome(
            success = false,
            errorCode = if (error.category == ErrorCategory.TRANSIENT) {
                ForwardErrorCode.NETWORK_FAILURE.name
            } else {
                error.code.name
            },
            errorMessage = message,
            retryable = retryable,
        )
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

    private suspend fun recordSyncError(
        ex: HistoricalSyncException,
        code: AppErrorCode,
        operation: String,
    ) {
        val log = apiErrorLog ?: return
        runCatching {
            log.record(
                provider = ApiErrorProvider.KIS,
                operation = operation,
                errorType = when (ex.kind) {
                    HistoricalSyncErrorKind.INSTRUMENT_NOT_FOUND -> ApiErrorType.KIS_BUSINESS_ERROR
                    else -> ApiErrorType.NETWORK_TIMEOUT
                },
                safeMessage = ex.publicMessage,
                retryable = code.isRetryableAutomatically,
            )
        }
    }

    private suspend fun recordUnrecognisedError(
        error: SafeAppError,
        message: String,
        retryable: Boolean,
        operation: String,
    ) {
        val log = apiErrorLog ?: return
        runCatching {
            log.record(
                provider = ApiErrorProvider.KIS,
                operation = operation,
                errorType = ApiErrorType.NETWORK_TIMEOUT,
                safeMessage = message,
                retryable = retryable,
                httpStatus = error.diagnostics.httpStatus,
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

    private companion object {
        const val OPERATION_FORWARD_SYNC = "KIS_FORWARD_SYNC"
        const val OPERATION_HISTORICAL_SYNC = "KIS_HISTORICAL_SYNC"
    }
}
