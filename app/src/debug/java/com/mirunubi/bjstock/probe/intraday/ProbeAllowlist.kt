package com.mirunubi.bjstock.probe.intraday

import java.io.IOException
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response

sealed interface AllowlistDecision {
    data object Allowed : AllowlistDecision
    data class Denied(val reason: String) : AllowlistDecision
}

class ProbeAllowlistDeniedException(val reason: String) : IOException("Probe allowlist denied: $reason")

/** Positive REST allowlist: exact scheme, host, port, method, path, TR ID, and query parameters. */
class ProbeRestAllowlist(
    private val endpoints: ProbeEndpoints,
    private val symbols: Set<String> = ProbeScope.SYMBOLS.toSet(),
) {
    fun check(method: String, url: HttpUrl, trId: String?): AllowlistDecision {
        val path = url.encodedPath
        if (path.lowercase().contains(ProbePaths.TRADING_MARKER)) return AllowlistDecision.Denied("TRADING_PATH_FORBIDDEN")
        if (url.scheme != endpoints.restScheme) return AllowlistDecision.Denied("SCHEME_NOT_ALLOWED")
        if (!url.host.equals(endpoints.restHost, ignoreCase = true)) return AllowlistDecision.Denied("HOST_NOT_ALLOWED")
        if (url.port != endpoints.restPort) return AllowlistDecision.Denied("PORT_NOT_ALLOWED")
        return when (path) {
            ProbePaths.TOKEN, ProbePaths.APPROVAL -> checkAuthRoute(method, url, trId)
            ProbePaths.MINUTE_BARS -> checkMinuteBars(method, url, trId)
            else -> AllowlistDecision.Denied("PATH_NOT_ALLOWED")
        }
    }

    private fun checkAuthRoute(method: String, url: HttpUrl, trId: String?): AllowlistDecision {
        if (method != "POST") return AllowlistDecision.Denied("METHOD_NOT_ALLOWED")
        if (url.querySize != 0) return AllowlistDecision.Denied("QUERY_NOT_ALLOWED")
        if (!trId.isNullOrEmpty()) return AllowlistDecision.Denied("TR_ID_NOT_ALLOWED")
        return AllowlistDecision.Allowed
    }

    private fun checkMinuteBars(method: String, url: HttpUrl, trId: String?): AllowlistDecision {
        if (method != "GET") return AllowlistDecision.Denied("METHOD_NOT_ALLOWED")
        if (trId != ProbeScope.MINUTE_BAR_TR_ID) return AllowlistDecision.Denied("TR_ID_NOT_ALLOWED")
        if (url.queryParameterNames != MINUTE_BAR_PARAMETERS) return AllowlistDecision.Denied("QUERY_NOT_ALLOWED")
        if (MINUTE_BAR_PARAMETERS.any { url.queryParameterValues(it).size != 1 }) {
            return AllowlistDecision.Denied("QUERY_NOT_ALLOWED")
        }
        if (url.queryParameter("FID_COND_MRKT_DIV_CODE") != ProbeScope.VENUE_CODE) {
            return AllowlistDecision.Denied("VENUE_NOT_ALLOWED")
        }
        if (url.queryParameter("FID_INPUT_ISCD") !in symbols) return AllowlistDecision.Denied("SYMBOL_NOT_ALLOWED")
        if (url.queryParameter("FID_INPUT_HOUR_1")?.matches(HHMMSS) != true) {
            return AllowlistDecision.Denied("QUERY_NOT_ALLOWED")
        }
        if (url.queryParameter("FID_PW_DATA_INCU_YN") !in setOf("Y", "N")) {
            return AllowlistDecision.Denied("QUERY_NOT_ALLOWED")
        }
        if (url.queryParameter("FID_ETC_CLS_CODE") != "") return AllowlistDecision.Denied("QUERY_NOT_ALLOWED")
        return AllowlistDecision.Allowed
    }

    companion object {
        val MINUTE_BAR_PARAMETERS = setOf(
            "FID_COND_MRKT_DIV_CODE",
            "FID_INPUT_ISCD",
            "FID_INPUT_HOUR_1",
            "FID_PW_DATA_INCU_YN",
            "FID_ETC_CLS_CODE",
        )
        private val HHMMSS = Regex("^\\d{6}$")
    }
}

/** Positive WebSocket allowlist: exact endpoint plus subscription TR ID, symbol, and request type. */
class ProbeWebSocketAllowlist(
    private val endpoints: ProbeEndpoints,
    private val symbols: Set<String> = ProbeScope.SYMBOLS.toSet(),
) {
    /** OkHttp rewrites `ws://` to `http://` (and `wss://` to `https://`) before interceptors run. */
    fun checkEndpoint(url: HttpUrl): AllowlistDecision {
        val expectedScheme = when (endpoints.wsScheme) {
            "ws" -> "http"
            "wss" -> "https"
            else -> return AllowlistDecision.Denied("SCHEME_NOT_ALLOWED")
        }
        if (url.encodedPath.lowercase().contains(ProbePaths.TRADING_MARKER)) {
            return AllowlistDecision.Denied("TRADING_PATH_FORBIDDEN")
        }
        if (url.scheme != expectedScheme) return AllowlistDecision.Denied("SCHEME_NOT_ALLOWED")
        if (!url.host.equals(endpoints.wsHost, ignoreCase = true)) return AllowlistDecision.Denied("HOST_NOT_ALLOWED")
        if (url.port != endpoints.wsPort) return AllowlistDecision.Denied("PORT_NOT_ALLOWED")
        if (url.encodedPath != endpoints.wsPath) return AllowlistDecision.Denied("PATH_NOT_ALLOWED")
        if (url.querySize != 0) return AllowlistDecision.Denied("QUERY_NOT_ALLOWED")
        return AllowlistDecision.Allowed
    }

    fun checkSubscription(trId: String, trKey: String, trType: String): AllowlistDecision {
        if (trId != ProbeScope.STREAM_TR_ID) return AllowlistDecision.Denied("TR_NOT_ALLOWED")
        if (trKey !in symbols) return AllowlistDecision.Denied("SYMBOL_NOT_ALLOWED")
        if (trType != ProbeScope.SUBSCRIBE_TR_TYPE) return AllowlistDecision.Denied("TR_TYPE_NOT_ALLOWED")
        return AllowlistDecision.Allowed
    }
}

class ProbeRestAllowlistInterceptor(private val allowlist: ProbeRestAllowlist) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        return when (val decision = allowlist.check(request.method, request.url, request.header("tr_id"))) {
            AllowlistDecision.Allowed -> chain.proceed(request)
            is AllowlistDecision.Denied -> throw ProbeAllowlistDeniedException(decision.reason)
        }
    }
}

class ProbeWebSocketEndpointInterceptor(private val allowlist: ProbeWebSocketAllowlist) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.header("Upgrade").equals("websocket", ignoreCase = true)) {
            throw ProbeAllowlistDeniedException("NOT_A_WEBSOCKET_UPGRADE")
        }
        return when (val decision = allowlist.checkEndpoint(request.url)) {
            AllowlistDecision.Allowed -> chain.proceed(request)
            is AllowlistDecision.Denied -> throw ProbeAllowlistDeniedException(decision.reason)
        }
    }
}
