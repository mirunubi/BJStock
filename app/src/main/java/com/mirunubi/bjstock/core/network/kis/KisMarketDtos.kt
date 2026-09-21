package com.mirunubi.bjstock.core.network.kis

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class KisCurrentPriceResponseDto(
    @SerialName("rt_cd") val rtCd: String? = null,
    @SerialName("msg_cd") val msgCd: String? = null,
    @SerialName("msg1") val msg1: String? = null,
    @SerialName("output") val output: KisCurrentPriceOutputDto? = null,
)

@Serializable
data class KisCurrentPriceOutputDto(
    @SerialName("stck_prpr") val currentPrice: String? = null,
    @SerialName("prdy_vrss") val previousCloseDifference: String? = null,
    @SerialName("prdy_ctrt") val changeRate: String? = null,
    @SerialName("stck_oprc") val openPrice: String? = null,
    @SerialName("stck_hgpr") val highPrice: String? = null,
    @SerialName("stck_lwpr") val lowPrice: String? = null,
    @SerialName("acml_vol") val volume: String? = null,
    @SerialName("acml_tr_pbmn") val tradingValue: String? = null,
    @SerialName("stck_bsop_date") val businessDate: String? = null,
)

@Serializable
data class KisDailyChartResponseDto(
    @SerialName("rt_cd") val rtCd: String? = null,
    @SerialName("msg_cd") val msgCd: String? = null,
    @SerialName("msg1") val msg1: String? = null,
    @SerialName("output2") val output2: List<KisDailyBarOutputDto>? = null,
)

@Serializable
data class KisDailyBarOutputDto(
    @SerialName("stck_bsop_date") val tradeDate: String? = null,
    @SerialName("stck_oprc") val openPrice: String? = null,
    @SerialName("stck_hgpr") val highPrice: String? = null,
    @SerialName("stck_lwpr") val lowPrice: String? = null,
    @SerialName("stck_clpr") val closePrice: String? = null,
    @SerialName("acml_vol") val volume: String? = null,
    @SerialName("acml_tr_pbmn") val tradingValue: String? = null,
)
