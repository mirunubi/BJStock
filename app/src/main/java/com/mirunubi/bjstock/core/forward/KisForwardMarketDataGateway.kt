package com.mirunubi.bjstock.core.forward

import com.mirunubi.bjstock.core.audit.ApiErrorLogService
import com.mirunubi.bjstock.core.audit.KisApiErrorMapper
import com.mirunubi.bjstock.core.error.AppErrorCode
import com.mirunubi.bjstock.core.error.AppErrorMapper
import com.mirunubi.bjstock.core.kis.KisCredentialStore
import com.mirunubi.bjstock.core.kis.KisSettingsStore
import com.mirunubi.bjstock.core.kis.market.KisMarketErrorKind
import com.mirunubi.bjstock.core.kis.market.KisMarketException
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
        var totals = MarketSyncOutcome(success = true)
        for (instrumentId in instrumentIds.sorted()) {
            val result = try {
                val latest = localRepository.findLatest(instrumentId)
                if (latest == null) {
                    val start = throughDate.minusDays(ForwardTestConfig.HISTORY_PREPARE_CALENDAR_DAYS)
                    historicalSync(instrumentId, start, throughDate)
                } else {
                    syncFromLatest(instrumentId, throughDate)
                }
            } catch (ex: HistoricalSyncException) {
                return historicalSyncFailure(ex, OPERATION_HISTORICAL_SYNC).withCountsOf(totals)
            } catch (ex: KisMarketException) {
                return marketFailure(ex).withCountsOf(totals)
            } catch (ex: Exception) {
                return unrecognisedFailure(ex, OPERATION_FORWARD_SYNC).withCountsOf(totals)
            }
            totals = totals.copy(
                requestedStart = listOfNotNull(totals.requestedStart, result.requestedStart).min(),
                insertedCount = totals.insertedCount + result.insertedCount,
                updatedCount = totals.updatedCount + result.updatedCount,
                unchangedCount = totals.unchangedCount + result.unchangedCount,
            )
        }
        return totals
    }

    private fun MarketSyncOutcome.withCountsOf(totals: MarketSyncOutcome) = copy(
        requestedStart = totals.requestedStart,
        insertedCount = totals.insertedCount,
        updatedCount = totals.updatedCount,
        unchangedCount = totals.unchangedCount,
    )

    suspend fun prepareHistory(
        instrumentIds: List<Long>,
        startDate: LocalDate,
    ): MarketSyncOutcome {
        val from = startDate.minusDays(ForwardTestConfig.HISTORY_PREPARE_CALENDAR_DAYS)
        for (instrumentId in instrumentIds.sorted()) {
            try {
                historicalSync(instrumentId, from, startDate)
            } catch (ex: KisMarketException) {
                return marketFailure(ex)
            } catch (ex: HistoricalSyncException) {
                return historicalSyncFailure(ex, OPERATION_FORWARD_SYNC)
            } catch (ex: Exception) {
                return unrecognisedFailure(ex, OPERATION_FORWARD_SYNC)
            }
        }
        return MarketSyncOutcome(success = true)
    }

    private suspend fun marketFailure(ex: KisMarketException): MarketSyncOutcome {
        val code = AppErrorMapper.fromKisMarketErrorKind(ex.kind)
        if (ex.kind !in REPOSITORY_RECORDED_KINDS) {
            record(
                code = code,
                operation = OPERATION_FORWARD_SYNC,
                safeMessage = ex.publicMessage,
                httpStatus = ex.audit?.httpCode,
                businessCode = ex.audit?.msgCd,
            )
        }
        return failure(code, ForwardTestOrchestrator.sanitizeError(ex.publicMessage))
    }

    private suspend fun historicalSyncFailure(
        ex: HistoricalSyncException,
        operation: String,
    ): MarketSyncOutcome {
        val code = AppErrorMapper.fromHistoricalSyncErrorKind(ex.kind)
        record(code, operation, ex.publicMessage)
        return failure(code, ForwardTestOrchestrator.sanitizeError(ex.publicMessage))
    }

    /** [Throwable.message] is never used; only the canonical safe message and class name. */
    private suspend fun unrecognisedFailure(ex: Exception, operation: String): MarketSyncOutcome {
        val error = AppErrorMapper.fromThrowable(ex, logicalEndpoint = operation)
        val message = listOfNotNull(
            error.safeMessage,
            error.diagnostics.exceptionType?.let { "($it)" },
        ).joinToString(" ")
        record(error.code, operation, message, httpStatus = error.diagnostics.httpStatus)
        return failure(error.code, message)
    }

    private fun failure(code: AppErrorCode, message: String?): MarketSyncOutcome {
        val retryable = code.isRetryableAutomatically
        return MarketSyncOutcome(
            success = false,
            errorCode = if (retryable) ForwardErrorCode.NETWORK_FAILURE.name else code.name,
            errorMessage = message,
            retryable = retryable,
        )
    }

    private suspend fun record(
        code: AppErrorCode,
        operation: String,
        safeMessage: String,
        httpStatus: Int? = null,
        businessCode: String? = null,
    ) {
        val log = apiErrorLog ?: return
        runCatching {
            log.record(
                provider = ApiErrorProvider.KIS,
                operation = operation,
                errorType = KisApiErrorMapper.fromAppErrorCode(code) ?: ApiErrorType.UNEXPECTED,
                safeMessage = safeMessage,
                retryable = code.isRetryableAutomatically,
                httpStatus = httpStatus,
                businessCode = businessCode,
            )
        }
    }

    private companion object {
        const val OPERATION_FORWARD_SYNC = "KIS_FORWARD_SYNC"
        const val OPERATION_HISTORICAL_SYNC = "KIS_HISTORICAL_SYNC"

        /**
         * Kinds that KisMarketRepositoryImpl already records once per provider attempt.
         * INVALID_SYMBOL / INVALID_DATE_RANGE are raised before the request and
         * MAPPING_FAILURE after it returns, so only the gateway records those.
         */
        val REPOSITORY_RECORDED_KINDS = setOf(
            KisMarketErrorKind.AUTHENTICATION,
            KisMarketErrorKind.HTTP,
            KisMarketErrorKind.BUSINESS,
            KisMarketErrorKind.RATE_LIMITED,
            KisMarketErrorKind.NETWORK_TIMEOUT,
            KisMarketErrorKind.MALFORMED_RESPONSE,
        )
    }
}
