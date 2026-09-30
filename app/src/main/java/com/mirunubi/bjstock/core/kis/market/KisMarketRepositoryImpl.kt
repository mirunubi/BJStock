package com.mirunubi.bjstock.core.kis.market

import com.mirunubi.bjstock.core.audit.ApiErrorLogService
import com.mirunubi.bjstock.core.audit.KisApiErrorMapper
import com.mirunubi.bjstock.core.kis.KisAuthErrorKind
import com.mirunubi.bjstock.core.kis.KisAuthException
import com.mirunubi.bjstock.core.kis.KisAuthLogger
import com.mirunubi.bjstock.core.kis.KisAuthRepository
import com.mirunubi.bjstock.core.kis.KisCredentialStore
import com.mirunubi.bjstock.core.kis.KisEnvironmentConfig
import com.mirunubi.bjstock.core.model.ApiErrorProvider
import com.mirunubi.bjstock.core.network.kis.KisErrorBodyDto
import com.mirunubi.bjstock.core.network.kis.KisMarketApi
import com.mirunubi.bjstock.core.network.kis.KisMarketHeaders
import java.io.IOException
import java.io.InterruptedIOException
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import retrofit2.HttpException

class KisMarketRepositoryImpl(
    private val api: KisMarketApi,
    private val authRepository: KisAuthRepository,
    private val credentialStore: KisCredentialStore,
    private val logger: KisAuthLogger,
    private val apiErrorLog: ApiErrorLogService? = null,
    private val today: () -> LocalDate = { LocalDate.now(ZoneId.of("Asia/Seoul")) },
    private val baseUrl: suspend () -> String = {
        KisEnvironmentConfig.baseUrl(authRepository.selectedEnvironment())
    },
) : KisMarketRepository {
    override suspend fun inquireCurrentPrice(symbol: String): CurrentStockQuote {
        val code = KisDomesticSymbol.requireValid(symbol)
        val path = KisMarketApiConfig.INQUIRE_PRICE_PATH
        KisReadOnlyGuard.assertAllowed(path)
        logger.info("KIS inquire-price request started")
        val response = execute("KIS_CURRENT_PRICE", path, KisMarketApiConfig.TR_INQUIRE_PRICE) { url, headers ->
            api.inquirePrice(
                url = url,
                headers = headers,
                marketDivision = KisMarketApiConfig.marketDivisionCode(KisMarketDivision.KRX),
                symbol = code,
            )
        }
        ensureBusinessSuccess("KIS_CURRENT_PRICE", response.rtCd, response.msgCd, response.msg1)
        val quote = KisCurrentPriceMapper.map(code, response)
        logger.info("KIS request success")
        return quote
    }

    override suspend fun inquireDailyBars(
        symbol: String,
        startDate: LocalDate,
        endDate: LocalDate,
        adjustment: KisPriceAdjustment,
    ): List<DailyStockBar> {
        val code = KisDomesticSymbol.requireValid(symbol)
        requireValidRange(startDate, endDate)
        val path = KisMarketApiConfig.INQUIRE_DAILY_ITEMCHARTPRICE_PATH
        KisReadOnlyGuard.assertAllowed(path)
        logger.info("KIS inquire-daily-itemchartprice request started")
        val response = execute(
            "KIS_DAILY_PRICE",
            path,
            KisMarketApiConfig.TR_INQUIRE_DAILY_ITEMCHARTPRICE,
        ) { url, headers ->
            api.inquireDailyItemChartPrice(
                url = url,
                headers = headers,
                marketDivision = KisMarketApiConfig.marketDivisionCode(KisMarketDivision.KRX),
                symbol = code,
                startDate = KisMarketNumeric.formatKisDate(startDate),
                endDate = KisMarketNumeric.formatKisDate(endDate),
                period = KisMarketApiConfig.periodCode(KisChartPeriod.DAILY),
                adjustment = KisMarketApiConfig.priceAdjustmentCode(adjustment),
            )
        }
        ensureBusinessSuccess("KIS_DAILY_PRICE", response.rtCd, response.msgCd, response.msg1)
        val bars = KisDailyBarMapper.map(code, response)
        logger.info("KIS request success")
        return bars
    }

    private fun requireValidRange(startDate: LocalDate, endDate: LocalDate) {
        if (startDate.isAfter(endDate) || startDate.isAfter(today())) {
            throw KisMarketException(
                kind = KisMarketErrorKind.INVALID_DATE_RANGE,
                publicMessage = "잘못된 조회 기간",
            )
        }
    }

    private suspend fun ensureBusinessSuccess(
        operation: String,
        rtCd: String?,
        msgCd: String?,
        msg1: String?,
    ) {
        if (rtCd == null) {
            val error = KisMarketException(
                kind = KisMarketErrorKind.MALFORMED_RESPONSE,
                publicMessage = "KIS 응답 오류",
            )
            recordError(operation, error)
            throw error
        }
        if (rtCd != "0") {
            logger.info("KIS business error: ${msgCd ?: "unknown"}")
            val audit = KisMarketErrorAudit(msgCd = msgCd, msg1 = msg1, rtCd = rtCd)
            val error = if (KisRequestPolicy.isRateLimit(msgCd)) {
                rateLimited(audit)
            } else {
                KisMarketException(
                    kind = KisMarketErrorKind.BUSINESS,
                    publicMessage = "KIS 응답 오류",
                    audit = audit,
                )
            }
            recordError(operation, error)
            throw error
        }
    }

    private fun rateLimited(audit: KisMarketErrorAudit) = KisMarketException(
        kind = KisMarketErrorKind.RATE_LIMITED,
        publicMessage = "KIS 요청 한도 초과",
        audit = audit,
    )

    private fun parseErrorBody(error: HttpException): KisErrorBodyDto? = try {
        val raw = error.response()?.errorBody()?.string()
        if (raw.isNullOrBlank() || raw.length > MAX_ERROR_BODY_CHARS) {
            null
        } else {
            errorBodyJson.decodeFromString(KisErrorBodyDto.serializer(), raw)
        }
    } catch (_: Exception) {
        null
    }

    private suspend fun <T> execute(
        operation: String,
        path: String,
        trId: String,
        call: suspend (url: String, headers: Map<String, String>) -> T,
    ): T {
        val environment = authRepository.selectedEnvironment()
        val token = try {
            authRepository.getValidToken(environment)
        } catch (error: KisAuthException) {
            val wrapped = authFailure(error.kind, error.httpCode)
            // KisAuthRepository already recorded the failed KIS_OAUTH attempt; only the
            // no-attempt credential case has no row yet.
            if (error.kind == KisAuthErrorKind.CREDENTIAL_MISSING) {
                recordError(operation, wrapped)
            }
            throw wrapped
        }
        val credentials = credentialStore.loadCredentials(environment)
            ?: run {
                val wrapped = authFailure(KisAuthErrorKind.CREDENTIAL_MISSING, httpCode = null)
                recordError(operation, wrapped)
                throw wrapped
            }
        val url = KisMarketApiConfig.pathUrl(baseUrl(), path)
        val headers = KisMarketHeaders.of(token, credentials, trId)
        return try {
            call(url, headers)
        } catch (error: KisMarketException) {
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (error: HttpException) {
            val body = parseErrorBody(error)
            val audit = KisMarketErrorAudit(
                msgCd = body?.msgCd,
                httpCode = error.code(),
                msg1 = body?.msg1,
                rtCd = body?.rtCd,
            )
            logger.info("KIS HTTP error: ${error.code()} ${body?.msgCd ?: "unknown"}")
            val wrapped = when {
                error.code() == 401 -> KisMarketException(
                    kind = KisMarketErrorKind.AUTHENTICATION,
                    publicMessage = "인증 필요",
                    audit = audit,
                )
                KisRequestPolicy.isRateLimit(body?.msgCd) -> rateLimited(audit)
                else -> KisMarketException(
                    kind = KisMarketErrorKind.HTTP,
                    publicMessage = "연결 실패",
                    audit = audit,
                )
            }
            recordError(operation, wrapped)
            throw wrapped
        } catch (error: SerializationException) {
            val wrapped = KisMarketException(
                kind = KisMarketErrorKind.MALFORMED_RESPONSE,
                publicMessage = "KIS 응답 오류",
            )
            recordError(operation, wrapped)
            throw wrapped
        } catch (error: IOException) {
            val wrapped = if (error is InterruptedIOException) {
                KisMarketException(
                    kind = KisMarketErrorKind.NETWORK_TIMEOUT,
                    publicMessage = "연결 실패",
                )
            } else {
                KisMarketException(
                    kind = KisMarketErrorKind.HTTP,
                    publicMessage = "연결 실패",
                )
            }
            recordError(operation, wrapped)
            throw wrapped
        } catch (error: IllegalStateException) {
            // KisReadOnlyGuard / KisReadOnlyInterceptor trading-path violation: a programming
            // defect, not a provider failure. Callers map it to UNEXPECTED_EXCEPTION.
            throw error
        } catch (_: Exception) {
            val wrapped = KisMarketException(
                kind = KisMarketErrorKind.UNEXPECTED,
                publicMessage = "예상치 못한 오류",
            )
            recordError(operation, wrapped)
            throw wrapped
        }
    }

    private fun authFailure(kind: KisAuthErrorKind, httpCode: Int?) = KisMarketException(
        kind = KisMarketErrorKind.AUTHENTICATION,
        publicMessage = when (kind) {
            KisAuthErrorKind.CREDENTIAL_MISSING,
            KisAuthErrorKind.CREDENTIAL_REJECTED,
            KisAuthErrorKind.AUTH_REQUIRED,
            -> "인증 필요"
            KisAuthErrorKind.SERVER_ERROR,
            KisAuthErrorKind.NETWORK_TIMEOUT,
            KisAuthErrorKind.NETWORK_UNAVAILABLE,
            KisAuthErrorKind.MALFORMED_RESPONSE,
            KisAuthErrorKind.UNEXPECTED,
            -> "인증 토큰 발급 실패"
        },
        audit = KisMarketErrorAudit(httpCode = httpCode),
        authKind = kind,
    )

    private suspend fun recordError(operation: String, error: KisMarketException) {
        val log = apiErrorLog ?: return
        val authKind = error.authKind
        log.recordOrReport(
            provider = ApiErrorProvider.KIS,
            operation = operation,
            errorType = authKind?.let(KisApiErrorMapper::fromAuthKind)
                ?: KisApiErrorMapper.fromMarketKind(error.kind),
            safeMessage = diagnosticMessage(error),
            retryable = authKind?.let(KisApiErrorMapper::isRetryable)
                ?: KisApiErrorMapper.isRetryable(error.kind),
            httpStatus = error.audit?.httpCode,
            businessCode = error.audit?.msgCd,
        )
    }

    private fun diagnosticMessage(error: KisMarketException): String {
        val detail = error.audit?.msg1?.trim()?.take(MAX_MSG1_CHARS)
        return if (detail.isNullOrEmpty()) error.publicMessage else "${error.publicMessage}: $detail"
    }

    private companion object {
        const val MAX_ERROR_BODY_CHARS = 4_096
        const val MAX_MSG1_CHARS = 200
        val errorBodyJson = Json { ignoreUnknownKeys = true }
    }
}
