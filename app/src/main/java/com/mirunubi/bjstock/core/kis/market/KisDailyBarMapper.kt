package com.mirunubi.bjstock.core.kis.market

import com.mirunubi.bjstock.core.network.kis.KisDailyBarOutputDto
import com.mirunubi.bjstock.core.network.kis.KisDailyChartResponseDto

object KisDailyBarMapper {
    fun map(symbol: String, response: KisDailyChartResponseDto): List<DailyStockBar> {
        val rows = response.output2.orEmpty()
        val unique = LinkedHashMap<String, DailyStockBar>()
        rows.forEach { row ->
            if (isBlankRow(row)) {
                return@forEach
            }
            val bar = mapRow(symbol, row)
            unique["${bar.symbol}|${bar.tradeDate}"] = bar
        }
        return unique.values.sortedBy { it.tradeDate }
    }

    fun mapRow(symbol: String, row: KisDailyBarOutputDto): DailyStockBar {
        return DailyStockBar(
            symbol = symbol,
            tradeDate = KisMarketNumeric.parseTradeDate(row.tradeDate, "stck_bsop_date"),
            openPrice = KisMarketNumeric.parseWon(row.openPrice, "stck_oprc"),
            highPrice = KisMarketNumeric.parseWon(row.highPrice, "stck_hgpr"),
            lowPrice = KisMarketNumeric.parseWon(row.lowPrice, "stck_lwpr"),
            closePrice = KisMarketNumeric.parseWon(row.closePrice, "stck_clpr"),
            volume = KisMarketNumeric.parseWon(row.volume, "acml_vol"),
            tradingValue = KisMarketNumeric.parseOptionalWon(row.tradingValue, "acml_tr_pbmn"),
        )
    }

    private fun isBlankRow(row: KisDailyBarOutputDto): Boolean {
        return listOf(
            row.tradeDate,
            row.openPrice,
            row.highPrice,
            row.lowPrice,
            row.closePrice,
            row.volume,
            row.tradingValue,
        ).all { it.isNullOrBlank() }
    }
}
