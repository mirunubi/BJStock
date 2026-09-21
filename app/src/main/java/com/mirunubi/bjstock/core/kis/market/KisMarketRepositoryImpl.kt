package com.mirunubi.bjstock.core.kis.market

import com.mirunubi.bjstock.core.kis.KisAuthException
import com.mirunubi.bjstock.core.kis.KisAuthLogger
import com.mirunubi.bjstock.core.kis.KisAuthRepository
import com.mirunubi.bjstock.core.kis.KisCredentialStore
import com.mirunubi.bjstock.core.kis.KisEnvironmentConfig
import com.mirunubi.bjstock.core.network.kis.KisMarketApi
import com.mirunubi.bjstock.core.network.kis.KisMarketHeaders
import java.io.IOException
import java.io.InterruptedIOException
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.SerializationException
import retrofit2.HttpException

class KisMarketRepositoryImpl(
    private val api: KisMarketApi,
    private val authRepository: KisAuthRepository,
    private val credentialStore: KisCredentialStore,
    private val logger: KisAuthLogger,
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
        val response = execute(path, KisMarketApiConfig.TR_INQUIRE_PRICE) { url, headers ->
            api.inquirePrice(
                url = url,
                headers = headers,
                marketDivision = KisMarketApiConfig.marketDivisionCode(KisMarketDivision.KRX),
                symbol = code,
            )
        }
        ensureBusinessSuccess(response.rtCd, response.msgCd, response.msg1)
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
        val response = execute(path, KisMarketApiConfig.TR_INQUIRE_DAILY_ITEMCHARTPRICE) { url, headers ->
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
        ensureBusinessSuccess(response.rtCd, response.msgCd, response.msg1)
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

    private fun ensureBusinessSuccess(rtCd: String?, msgCd: String?, msg1: String?) {
        if (rtCd == null) {
            throw KisMarketException(
                kind = KisMarketErrorKind.MALFORMED_RESPONSE,
                publicMessage = "KIS 응답 오류",
            )
        }
        if (rtCd != "0") {
            logger.info("KIS business error: ${msgCd ?: "unknown"}")
            throw KisMarketException(
                kind = KisMarketErrorKind.BUSINESS,
                publicMessage = "KIS 응답 오류",
                audit = KisMarketErrorAudit(msgCd = msgCd, msg1 = msg1),
            )
        }
    }

    private suspend fun <T> execute(
        path: String,
        trId: String,
        call: suspend (url: String, headers: Map<String, String>) -> T,
    ): T {
        val environment = authRepository.selectedEnvironment()
        val token = try {
            authRepository.getValidToken(environment)
        } catch (error: KisAuthException) {
            throw KisMarketException(
                kind = KisMarketErrorKind.AUTHENTICATION,
                publicMessage = "인증 필요",
                audit = KisMarketErrorAudit(httpCode = error.httpCode),
            )
        }
        val credentials = credentialStore.loadCredentials(environment)
            ?: throw KisMarketException(
                kind = KisMarketErrorKind.AUTHENTICATION,
                publicMessage = "인증 필요",
            )
        val url = KisMarketApiConfig.pathUrl(baseUrl(), path)
        val headers = KisMarketHeaders.of(token, credentials, trId)
        return try {
            call(url, headers)
        } catch (error: KisMarketException) {
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (error: HttpException) {
            if (error.code() == 401) {
                throw KisMarketException(
                    kind = KisMarketErrorKind.AUTHENTICATION,
                    publicMessage = "인증 필요",
                    audit = KisMarketErrorAudit(httpCode = 401),
                )
            }
            throw KisMarketException(
                kind = KisMarketErrorKind.HTTP,
                publicMessage = "연결 실패",
                audit = KisMarketErrorAudit(httpCode = error.code()),
            )
        } catch (error: SerializationException) {
            throw KisMarketException(
                kind = KisMarketErrorKind.MALFORMED_RESPONSE,
                publicMessage = "KIS 응답 오류",
            )
        } catch (error: IOException) {
            if (error is InterruptedIOException) {
                throw KisMarketException(
                    kind = KisMarketErrorKind.NETWORK_TIMEOUT,
                    publicMessage = "연결 실패",
                )
            }
            throw KisMarketException(
                kind = KisMarketErrorKind.HTTP,
                publicMessage = "연결 실패",
            )
        } catch (error: IllegalStateException) {
            throw error
        } catch (_: Exception) {
            throw KisMarketException(
                kind = KisMarketErrorKind.MALFORMED_RESPONSE,
                publicMessage = "KIS 응답 오류",
            )
        }
    }
}
