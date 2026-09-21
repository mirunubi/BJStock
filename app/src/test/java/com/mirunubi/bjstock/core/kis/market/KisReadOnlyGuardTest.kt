package com.mirunubi.bjstock.core.kis.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class KisReadOnlyGuardTest {
    @Test
    fun quotationsInquirePrice_isAllowed() {
        KisReadOnlyGuard.assertAllowed("/uapi/domestic-stock/v1/quotations/inquire-price")
    }

    @Test
    fun quotationsDailyChart_isAllowed() {
        KisReadOnlyGuard.assertAllowed(
            "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice",
        )
    }

    @Test
    fun oauthTokenPath_isAllowed() {
        KisReadOnlyGuard.assertAllowed("/oauth2/tokenP")
    }

    @Test
    fun tradingOrderCash_isRejected() {
        val error = assertThrows(IllegalStateException::class.java) {
            KisReadOnlyGuard.assertAllowed("/uapi/domestic-stock/v1/trading/order-cash")
        }
        assertEquals(
            "KIS trading endpoint is prohibited in current BJStock phase",
            error.message,
        )
    }
}
