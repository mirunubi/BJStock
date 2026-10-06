package com.mirunubi.bjstock.probe.intraday

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.Response

/**
 * WS-CONNECT-02 connection-stage markers for one WebSocket attempt. Telemetry only: no hook changes DNS,
 * the request, the response, retries, or the exception that reaches the WebSocket listener.
 *
 * OkHttp 4.12 runs neither network interceptors nor an EventListener for WebSocket calls, so the TCP
 * connection itself is not observable here; the stages are DNS (via [ProbeTelemetryDns]) and the
 * upgrade call as seen by an application interceptor ([ProbeConnectStageInterceptor]).
 */
enum class ProbeConnectStage(val eventType: ProbeEventType) {
    DNS_START(ProbeEventType.WS_DNS_START),
    DNS_END(ProbeEventType.WS_DNS_END),
    STAGE_START(ProbeEventType.WS_CONNECT_STAGE_START),
    UPGRADE_RESPONSE(ProbeEventType.WS_UPGRADE_RESPONSE),
    STAGE_FAILURE(ProbeEventType.WS_CONNECT_STAGE_FAILURE),
}

/** A telemetry failure must never alter the connection path, so it is dropped. */
internal fun ProbeSocketCallbacks.reportStage(stage: ProbeConnectStage, fields: JsonObjectBuilder.() -> Unit = {}) {
    try {
        onConnectStage(stage, buildJsonObject(fields))
    } catch (ignored: Exception) {
    }
}

/**
 * Bounded description of a failure: class names of the cause chain and of the top-level suppressed
 * exceptions, plus messages that are scrubbed first and only then truncated. Never a stack trace.
 */
object ProbeThrowableTelemetry {
    const val MAX_MESSAGE_LENGTH = 160

    /** The failure itself plus its causes, the same depth as the allowlist-denial cause walk. */
    const val MAX_CHAIN_DEPTH = 8
    const val MAX_SUPPRESSED = 3

    fun fields(error: Throwable, scrub: (String) -> String): JsonObject = buildJsonObject {
        put("error_class", error.javaClass.simpleName)
        message(error, scrub)?.let { put("error_message", it) }

        val seen = mutableListOf(error)
        var cause = error.cause
        while (cause != null && seen.none { it === cause } && seen.size < MAX_CHAIN_DEPTH) {
            seen += cause
            val index = seen.size - 1
            put("cause_${index}_class", cause.javaClass.simpleName)
            message(cause, scrub)?.let { put("cause_${index}_message", it) }
            cause = cause.cause
        }
        if (cause != null && seen.none { it === cause }) put("cause_chain_truncated", true)

        val suppressed = error.suppressed
        put("suppressed_count", suppressed.size)
        suppressed.take(MAX_SUPPRESSED).forEachIndexed { i, each ->
            put("suppressed_${i + 1}_class", each.javaClass.simpleName)
            message(each, scrub)?.let { put("suppressed_${i + 1}_message", it) }
        }
    }

    /** Scrub before truncating, so a registered secret cannot survive as a cut-off prefix. */
    fun message(error: Throwable, scrub: (String) -> String): String? {
        val raw = error.message ?: return null
        val scrubbed = scrub(raw)
        if (ProbeRedaction.mentionsForbiddenKey(scrubbed)) return ProbeRedaction.REDACTED
        return if (scrubbed.length <= MAX_MESSAGE_LENGTH) scrubbed else scrubbed.take(MAX_MESSAGE_LENGTH - 1) + "…"
    }
}

/**
 * Delegates each lookup to [delegate] exactly once and returns its exact result or rethrows its exact
 * exception. Records timing and address count / family only, never a literal address. The host name is
 * recorded only when it is the fixed VIRTUAL WebSocket host.
 */
class ProbeTelemetryDns(
    private val delegate: Dns,
    private val callbacks: ProbeSocketCallbacks,
    private val nanoTime: () -> Long = System::nanoTime,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val providerHost = hostname.equals(ProbeEndpoints.KIS_VIRTUAL.wsHost, ignoreCase = true)
        callbacks.reportStage(ProbeConnectStage.DNS_START) {
            put("host_is_provider_endpoint", providerHost)
            if (providerHost) put("host", ProbeEndpoints.KIS_VIRTUAL.wsHost)
        }
        val start = nanoTime()
        val addresses = try {
            delegate.lookup(hostname)
        } catch (error: Throwable) {
            val elapsed = nanoTime() - start
            callbacks.reportStage(ProbeConnectStage.DNS_END) {
                put("result", "FAILURE")
                put("elapsed_nanos", elapsed)
                put("error_class", error.javaClass.simpleName)
            }
            throw error
        }
        val elapsed = nanoTime() - start
        callbacks.reportStage(ProbeConnectStage.DNS_END) {
            put("result", "SUCCESS")
            put("elapsed_nanos", elapsed)
            put("address_count", addresses.size)
            put("ipv4_count", addresses.count { it is Inet4Address })
            put("ipv6_count", addresses.count { it is Inet6Address })
        }
        return addresses
    }
}

/**
 * Application interceptor placed after the endpoint allowlist. It calls `proceed` exactly once with the
 * unmodified request, returns the response untouched, and rethrows the original throwable. It sees the
 * upgrade call after OkHttp's own route retries, and records only the HTTP status code.
 */
class ProbeConnectStageInterceptor(
    private val callbacks: ProbeSocketCallbacks,
    private val nanoTime: () -> Long = System::nanoTime,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        callbacks.reportStage(ProbeConnectStage.STAGE_START) { put("tcp_connection_observable", false) }
        val start = nanoTime()
        val response = try {
            chain.proceed(chain.request())
        } catch (error: Throwable) {
            val elapsed = nanoTime() - start
            callbacks.reportStage(ProbeConnectStage.STAGE_FAILURE) {
                put("elapsed_nanos", elapsed)
                put("error_class", error.javaClass.simpleName)
            }
            throw error
        }
        val elapsed = nanoTime() - start
        callbacks.reportStage(ProbeConnectStage.UPGRADE_RESPONSE) {
            put("elapsed_nanos", elapsed)
            put("response_code", response.code)
        }
        return response
    }
}
