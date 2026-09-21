package com.mirunubi.bjstock.core.kis.market

/**
 * Central KIS market-data constants.
 *
 * Path strings and wire codes live here so feature code does not hardcode them.
 *
 * FID_ORG_ADJ_PRC mapping is taken from the official KIS sample:
 * examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py
 * ("0:수정주가 1:원주가").
 */
object KisMarketApiConfig {
    const val QUOTATIONS_PREFIX = "/uapi/domestic-stock/v1/quotations/"
    const val TRADING_PATH_MARKER = "/trading/"

    const val INQUIRE_PRICE_PATH = QUOTATIONS_PREFIX + "inquire-price"
    const val INQUIRE_DAILY_ITEMCHARTPRICE_PATH =
        QUOTATIONS_PREFIX + "inquire-daily-itemchartprice"

    const val TR_INQUIRE_PRICE = "FHKST01010100"
    const val TR_INQUIRE_DAILY_ITEMCHARTPRICE = "FHKST03010100"

    const val MARKET_DIV_KRX = "J"
    const val PERIOD_DAILY = "D"
    const val CUST_TYPE_PERSONAL = "P"

    const val ADJUSTED_PRICE_CODE = "0"
    const val UNADJUSTED_PRICE_CODE = "1"

    const val SOURCE_KIS = "KIS"

    fun priceAdjustmentCode(adjustment: KisPriceAdjustment): String = when (adjustment) {
        KisPriceAdjustment.ADJUSTED -> ADJUSTED_PRICE_CODE
        KisPriceAdjustment.UNADJUSTED -> UNADJUSTED_PRICE_CODE
    }

    fun marketDivisionCode(division: KisMarketDivision): String = when (division) {
        KisMarketDivision.KRX -> MARKET_DIV_KRX
    }

    fun periodCode(period: KisChartPeriod): String = when (period) {
        KisChartPeriod.DAILY -> PERIOD_DAILY
    }

    fun pathUrl(baseUrl: String, path: String): String = baseUrl.trimEnd('/') + path
}
