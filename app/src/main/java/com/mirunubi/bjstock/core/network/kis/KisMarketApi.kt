package com.mirunubi.bjstock.core.network.kis

import retrofit2.http.GET
import retrofit2.http.HeaderMap
import retrofit2.http.Query
import retrofit2.http.Url

interface KisMarketApi {
    @GET
    suspend fun inquirePrice(
        @Url url: String,
        @HeaderMap headers: Map<String, String>,
        @Query("FID_COND_MRKT_DIV_CODE") marketDivision: String,
        @Query("FID_INPUT_ISCD") symbol: String,
    ): KisCurrentPriceResponseDto

    @GET
    suspend fun inquireDailyItemChartPrice(
        @Url url: String,
        @HeaderMap headers: Map<String, String>,
        @Query("FID_COND_MRKT_DIV_CODE") marketDivision: String,
        @Query("FID_INPUT_ISCD") symbol: String,
        @Query("FID_INPUT_DATE_1") startDate: String,
        @Query("FID_INPUT_DATE_2") endDate: String,
        @Query("FID_PERIOD_DIV_CODE") period: String,
        @Query("FID_ORG_ADJ_PRC") adjustment: String,
    ): KisDailyChartResponseDto
}
