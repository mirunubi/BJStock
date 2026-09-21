package com.mirunubi.bjstock.core.network.kis

import com.mirunubi.bjstock.core.kis.KisCredentials
import com.mirunubi.bjstock.core.kis.KisToken
import com.mirunubi.bjstock.core.kis.market.KisMarketApiConfig

object KisMarketHeaders {
    fun of(
        token: KisToken,
        credentials: KisCredentials,
        trId: String,
    ): Map<String, String> = mapOf(
        "authorization" to "${token.tokenType} ${token.accessToken}",
        "appkey" to credentials.appKey,
        "appsecret" to credentials.appSecret,
        "tr_id" to trId,
        "custtype" to KisMarketApiConfig.CUST_TYPE_PERSONAL,
        "content-type" to "application/json; charset=utf-8",
    )
}
