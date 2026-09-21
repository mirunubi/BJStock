package com.mirunubi.bjstock.core.kis.market

import com.mirunubi.bjstock.core.network.kis.KisCurrentPriceOutputDto
import com.mirunubi.bjstock.core.network.kis.KisCurrentPriceResponseDto

object KisCurrentPriceMapper {
    fun map(symbol: String, response: KisCurrentPriceResponseDto): CurrentStockQuote {
        val output = response.output
            ?: throw KisMarketException(
                kind = KisMarketErrorKind.MALFORMED_RESPONSE,
                publicMessage = "KIS 응답 오류",
            )
        return map(symbol, output)
    }

    fun map(symbol: String, output: KisCurrentPriceOutputDto): CurrentStockQuote {
        return CurrentStockQuote(
            symbol = symbol,
            currentPrice = KisMarketNumeric.parseWon(output.currentPrice, "stck_prpr"),
            previousCloseDifference = KisMarketNumeric.parseWon(
                output.previousCloseDifference,
                "prdy_vrss",
            ),
            changeRate = KisMarketNumeric.parsePercentAsScaledRatio(output.changeRate, "prdy_ctrt"),
            openPrice = KisMarketNumeric.parseWon(output.openPrice, "stck_oprc"),
            highPrice = KisMarketNumeric.parseWon(output.highPrice, "stck_hgpr"),
            lowPrice = KisMarketNumeric.parseWon(output.lowPrice, "stck_lwpr"),
            volume = KisMarketNumeric.parseWon(output.volume, "acml_vol"),
            tradingValue = KisMarketNumeric.parseOptionalWon(output.tradingValue, "acml_tr_pbmn"),
            businessDate = KisMarketNumeric.parseOptionalTradeDate(output.businessDate),
            source = KisMarketApiConfig.SOURCE_KIS,
        )
    }
}
