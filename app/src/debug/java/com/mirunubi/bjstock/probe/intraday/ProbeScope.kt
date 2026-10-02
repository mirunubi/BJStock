package com.mirunubi.bjstock.probe.intraday

import com.mirunubi.bjstock.core.kis.KisEnvironment

/** Human-approved 12-B2 probe scope (docs/161 §21.1, A-3 / A-4 / A-6 / A-7 / A-8). */
object ProbeScope {
    val ENVIRONMENT: KisEnvironment = KisEnvironment.VIRTUAL
    const val VENUE_CODE = "J"
    const val STREAM_TR_ID = "H0STCNT0"
    const val MINUTE_BAR_TR_ID = "FHKST03010200"
    const val SUBSCRIBE_TR_TYPE = "1"
    const val CUSTOMER_TYPE = "P"
    val SYMBOLS: List<String> = listOf("005930", "000660")
    const val CANDIDATE_FGS_TYPE = "specialUse"
}

/**
 * Exact KIS VIRTUAL endpoints from the official `koreainvestment/open-trading-api` repository:
 * `kis_devlp.yaml` (`vps`, `vops`) and `chk_ccnl_krx.py` (`api_url="/tryitout"`).
 */
data class ProbeEndpoints(
    val restScheme: String,
    val restHost: String,
    val restPort: Int,
    val wsScheme: String,
    val wsHost: String,
    val wsPort: Int,
    val wsPath: String,
) {
    val restBaseUrl: String get() = "$restScheme://$restHost:$restPort"
    val wsUrl: String get() = "$wsScheme://$wsHost:$wsPort$wsPath"

    companion object {
        val KIS_VIRTUAL = ProbeEndpoints(
            restScheme = "https",
            restHost = "openapivts.koreainvestment.com",
            restPort = 29443,
            wsScheme = "ws",
            wsHost = "ops.koreainvestment.com",
            wsPort = 31000,
            wsPath = "/tryitout",
        )
    }
}

object ProbePaths {
    const val TOKEN = "/oauth2/tokenP"
    const val APPROVAL = "/oauth2/Approval"
    const val MINUTE_BARS = "/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice"
    const val TRADING_MARKER = "/trading/"
}

enum class ProbeErrorCode(val safeMessage: String) {
    VIRTUAL_CREDENTIAL_MISSING("VIRTUAL quotation credentials are not configured."),
    NON_VIRTUAL_ENVIRONMENT("Probe refused: environment is not KIS VIRTUAL."),
    ALLOWLIST_DENIED("Probe refused a request that is not on the positive allowlist."),
    VIRTUAL_TOKEN_FAILED("VIRTUAL token request failed."),
    WS_APPROVAL_FAILED("WebSocket approval-key request failed."),
    WS_CONNECT_FAILED("WebSocket connection failed."),
    WS_DISCONNECTED("WebSocket disconnected."),
    WS_PARSE_FAILED("WebSocket frame could not be parsed."),
    REST_FAILED("Quotation REST request failed."),
    NETWORK_UNAVAILABLE("Network unavailable."),
    NOTIFICATION_PERMISSION_MISSING("Notification permission is not granted."),
    PROBE_STORAGE_FAILED("Probe evidence storage failed."),
    FGS_TIMEOUT("Foreground service timeout callback received."),
    UNEXPECTED("Unexpected probe error."),
}

enum class ProbeStatus {
    STOPPED,
    STARTING,
    CONNECTED,
    RECONNECTING,
    RUNNING,
    ERROR,
    STOPPING,
}
