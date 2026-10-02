package com.mirunubi.bjstock.probe.intraday

import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class ProbeEventType {
    SESSION_START,
    SESSION_STOP,
    FGS_START,
    FGS_STOP,
    SCREEN_ON,
    SCREEN_OFF,
    IDLE_MODE_CHANGED,
    NETWORK_AVAILABLE,
    NETWORK_LOST,
    NETWORK_CAPABILITIES_CHANGED,
    BATTERY_SAMPLE,
    THERMAL_SAMPLE,
    TRAFFIC_SAMPLE,
    NETWORK_TIME_SAMPLE,
    WALL_CLOCK_CHANGED,
    TIMEZONE_CHANGED,
    WS_CONNECTING,
    WS_CONNECTED,
    WS_DISCONNECTED,
    WS_FAILURE,
    WS_RECONNECT_SCHEDULED,
    WS_RECONNECT_ATTEMPT,
    WS_SUBSCRIBE_SENT,
    WS_FRAME,
    WS_RECORD,
    STREAM_POSSIBLE_GAP,
    REST_REQUEST_START,
    REST_REQUEST_END,
    REST_FAILURE,
    HEARTBEAT,
    HEARTBEAT_GAP,
    PREVIOUS_PROCESS_EXIT,
    PROBE_ERROR,
    NETWORK_POLICY_QUIESCED,
}

class ProbeEvent(
    val sessionId: String,
    val type: ProbeEventType,
    val wallTimeEpochMs: Long,
    val elapsedRealtimeNanos: Long,
    val fields: JsonObject = EMPTY_FIELDS,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("session_id", sessionId)
        put("event_type", type.name)
        put("wall_time_epoch_ms", wallTimeEpochMs)
        put("elapsed_realtime_nanos", elapsedRealtimeNanos)
        for ((key, value) in fields) {
            if (key in RESERVED_KEYS) continue
            put(key, redactIfForbidden(key, value))
        }
    }

    companion object {
        val EMPTY_FIELDS = JsonObject(emptyMap())
        private val RESERVED_KEYS = setOf("session_id", "event_type", "wall_time_epoch_ms", "elapsed_realtime_nanos")
    }
}

internal fun redactIfForbidden(key: String, value: JsonElement): JsonElement = when {
    ProbeRedaction.isForbiddenKey(key) -> JsonPrimitive(ProbeRedaction.REDACTED)
    value is JsonObject -> JsonObject(value.mapValues { (k, v) -> redactIfForbidden(k, v) })
    value is JsonArray -> JsonArray(value.map { redactIfForbidden("", it) })
    else -> value
}

object ProbeSessionId {
    private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    val PATTERN = Regex("^probe_\\d{8}T\\d{6}Z_[0-9a-f]{8}$")

    fun create(
        nowEpochMs: Long,
        randomHex: () -> String = { UUID.randomUUID().toString().replace("-", "").take(8) },
    ): String {
        val suffix = randomHex().lowercase()
        require(suffix.matches(Regex("^[0-9a-f]{8}$"))) { "session suffix must be 8 hex characters" }
        return "probe_${TIMESTAMP.format(java.time.Instant.ofEpochMilli(nowEpochMs))}_$suffix"
    }
}
