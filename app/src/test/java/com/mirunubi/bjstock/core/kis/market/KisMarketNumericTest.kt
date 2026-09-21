package com.mirunubi.bjstock.core.kis.market

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KisMarketNumericTest {
    @Test
    fun parseWon_stringIntegers() {
        assertEquals(72_300L, KisMarketNumeric.parseWon("72300", "stck_prpr"))
        assertEquals(0L, KisMarketNumeric.parseWon("0", "stck_prpr"))
        assertEquals(-900L, KisMarketNumeric.parseWon("-900", "prdy_vrss"))
    }

    @Test
    fun parsePercent_scaledRatio() {
        val scaled = KisMarketNumeric.parsePercentAsScaledRatio("-1.25", "prdy_ctrt")
        assertEquals(-1_250_000L, scaled)
        assertEquals(1_250_000L, KisMarketNumeric.parsePercentAsScaledRatio("1.25", "prdy_ctrt"))
        assertEquals("-1.25%", KisMarketNumeric.formatPercentFromScaledRatio(scaled))
    }

    @Test
    fun parseWon_rejectsInvalidValues() {
        listOf("", "N/A", "ABC").forEach { raw ->
            val error = runCatching { KisMarketNumeric.parseWon(raw, "stck_prpr") }.exceptionOrNull()
            assertTrue(raw, error is KisMarketException)
            assertEquals(KisMarketErrorKind.MAPPING_FAILURE, (error as KisMarketException).kind)
        }
    }

    @Test
    fun parseOptionalWon_blankIsNullNotZero() {
        assertNull(KisMarketNumeric.parseOptionalWon("", "acml_tr_pbmn"))
        assertNull(KisMarketNumeric.parseOptionalWon(null, "acml_tr_pbmn"))
    }
}
