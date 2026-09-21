package com.mirunubi.bjstock.core.kis.market

object KisReadOnlyGuard {
    fun assertAllowed(path: String) {
        val normalized = path.lowercase()
        if (normalized.contains(KisMarketApiConfig.TRADING_PATH_MARKER)) {
            throw IllegalStateException("KIS trading endpoint is prohibited in current BJStock phase")
        }
        if (normalized.contains("/uapi/") && !normalized.contains("/quotations/")) {
            throw IllegalStateException("KIS trading endpoint is prohibited in current BJStock phase")
        }
    }
}
