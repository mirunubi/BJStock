package com.mirunubi.bjstock.probe.intraday

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class ProviderTimestampSemantics {
    UNKNOWN,
    TRUNCATION_PROVEN,
    ROUNDING_PROVEN,
}

/** Explicit proof inputs. The defaults prove nothing, so the default classification is never PROVABLY_LATER. */
data class StrictAfterProofInputs(
    val semantics: ProviderTimestampSemantics = ProviderTimestampSemantics.UNKNOWN,
    val clockOffsetBoundMillis: Long? = null,
    val providerDateKst: LocalDate? = null,
)

enum class StrictAfterClassification {
    PROVABLY_LATER,
    UNPROVEN_SAME_SECOND,
    UNPROVEN_NOT_LATER,
    UNPROVEN_WITHIN_UNCERTAINTY,
    UNPROVEN_TIMESTAMP_SEMANTICS,
    UNPROVEN_CLOCK_OFFSET,
    UNPROVEN_DATE,
    UNPROVEN_MALFORMED,
}

/**
 * DERIVED probe analysis only (docs/161 §14.2). Anything other than PROVABLY_LATER means strict-after is
 * unproven, and an unprovable strict-after means NO FILL. This classifier never creates any order or fill.
 */
object StrictAfterEvidenceClassifier {
    val KST: ZoneId = ZoneId.of("Asia/Seoul")
    private val HHMMSS = Regex("^\\d{6}$")

    fun classify(
        referenceInstant: Instant,
        providerHhmmss: String,
        inputs: StrictAfterProofInputs,
    ): StrictAfterClassification {
        if (!providerHhmmss.matches(HHMMSS)) return StrictAfterClassification.UNPROVEN_MALFORMED
        val hour = providerHhmmss.substring(0, 2).toInt()
        val minute = providerHhmmss.substring(2, 4).toInt()
        val second = providerHhmmss.substring(4, 6).toInt()
        if (hour > 23 || minute > 59 || second > 59) return StrictAfterClassification.UNPROVEN_MALFORMED
        val date = inputs.providerDateKst ?: return StrictAfterClassification.UNPROVEN_DATE

        val providerSecondStart = date.atTime(LocalTime.of(hour, minute, second)).atZone(KST).toInstant()
        val referenceSecondStart = referenceInstant.truncatedTo(ChronoUnit.SECONDS)
        if (providerSecondStart == referenceSecondStart) return StrictAfterClassification.UNPROVEN_SAME_SECOND
        if (providerSecondStart.isBefore(referenceSecondStart)) return StrictAfterClassification.UNPROVEN_NOT_LATER

        val earliestActual = when (inputs.semantics) {
            ProviderTimestampSemantics.UNKNOWN -> return StrictAfterClassification.UNPROVEN_TIMESTAMP_SEMANTICS
            ProviderTimestampSemantics.TRUNCATION_PROVEN -> providerSecondStart
            ProviderTimestampSemantics.ROUNDING_PROVEN -> providerSecondStart.minusMillis(500)
        }
        val bound = inputs.clockOffsetBoundMillis
        if (bound == null || bound < 0) return StrictAfterClassification.UNPROVEN_CLOCK_OFFSET
        val latestReference = referenceInstant.plusMillis(bound)
        return if (earliestActual.isAfter(latestReference)) {
            StrictAfterClassification.PROVABLY_LATER
        } else {
            StrictAfterClassification.UNPROVEN_WITHIN_UNCERTAINTY
        }
    }
}

data class HeartbeatObservation(
    val expectedNanos: Long,
    val actualNanos: Long,
    val lateByNanos: Long,
    val isGap: Boolean,
)

/** Monotonic-clock heartbeat evaluation; measurement only, never drives any decision. */
object HeartbeatEvaluator {
    val INTERVAL: Duration = Duration.ofSeconds(30)
    val GAP_THRESHOLD: Duration = Duration.ofSeconds(15)

    fun evaluate(
        expectedNanos: Long,
        actualNanos: Long,
        gapThresholdNanos: Long = GAP_THRESHOLD.toNanos(),
    ): HeartbeatObservation {
        val late = actualNanos - expectedNanos
        return HeartbeatObservation(expectedNanos, actualNanos, late, late > gapThresholdNanos)
    }
}

/**
 * Observable stream-continuity evidence. H0STCNT0 has no documented sequence field, so a lost-stream window
 * is only ever classified as an unproven possible gap; missing trades are never invented or backfilled.
 */
class StreamContinuityTracker(private val symbols: Set<String> = ProbeScope.SYMBOLS.toSet()) {
    data class PossibleGap(
        val classification: String,
        val cause: String,
        val startElapsedNanos: Long,
        val endElapsedNanos: Long,
    ) {
        val durationNanos: Long get() = endElapsedNanos - startElapsedNanos
    }

    private var disconnectedAtNanos: Long? = null
    private var everConnected = false
    private val awaitingFirstRecordAfterReconnect = mutableSetOf<String>()
    private val lastTradeTimeBySymbol = mutableMapOf<String, String>()

    var possibleGapCount: Long = 0
        private set

    @Synchronized
    fun onConnected(nowNanos: Long): PossibleGap? {
        val start = disconnectedAtNanos
        disconnectedAtNanos = null
        val gap = if (everConnected && start != null) {
            awaitingFirstRecordAfterReconnect.clear()
            awaitingFirstRecordAfterReconnect.addAll(symbols)
            possibleGapCount++
            PossibleGap(POSSIBLE_GAP, "RECONNECT", start, nowNanos)
        } else {
            null
        }
        everConnected = true
        return gap
    }

    @Synchronized
    fun onDisconnected(nowNanos: Long) {
        if (disconnectedAtNanos == null) disconnectedAtNanos = nowNanos
    }

    @Synchronized
    fun onHeartbeatGap(startNanos: Long, endNanos: Long): PossibleGap {
        possibleGapCount++
        return PossibleGap(POSSIBLE_GAP, "HEARTBEAT_GAP", startNanos, endNanos)
    }

    /** True for the first record received for [symbol] after a reconnect; tracked independently per symbol. */
    @Synchronized
    fun consumeFirstRecordAfterReconnect(symbol: String): Boolean = awaitingFirstRecordAfterReconnect.remove(symbol)

    /** True when this record's HHMMSS is earlier than the previous record seen for the same symbol. */
    @Synchronized
    fun isOutOfOrder(symbol: String, tradeTimeHhmmss: String): Boolean {
        val previous = lastTradeTimeBySymbol[symbol]
        lastTradeTimeBySymbol[symbol] = tradeTimeHhmmss
        return previous != null && tradeTimeHhmmss < previous
    }

    companion object {
        const val POSSIBLE_GAP = "UNPROVEN_POSSIBLE_GAP"
    }
}

enum class NetworkTimeSampleReason {
    SESSION_START,
    PERIODIC,
    TIME_CHANGED,
    TIMEZONE_CHANGED,
    SESSION_STOP,
}

/**
 * One Android network-time reference read (M-19 / M-20). [networkEpochMillis] is null when the platform has no
 * network time; it is never replaced by zero or by any other time source.
 */
data class NetworkTimeReading(
    val sdkInt: Int,
    val wallMillis: Long,
    val elapsedNanosBefore: Long,
    val elapsedNanosAfter: Long,
    val networkEpochMillis: Long?,
    val unavailableReason: String?,
)

/**
 * Raw reference observation only: not exchange time, not security-grade time, and never an input that proves
 * timestamp semantics or a clock-offset bound for [StrictAfterEvidenceClassifier].
 */
object NetworkTimeEvidence {
    const val REFERENCE_KIND = "ANDROID_SYSTEMCLOCK_CURRENT_NETWORK_TIME_CLOCK"
    const val SCOPE = "REFERENCE OBSERVATION ONLY; NOT EXCHANGE TIME; NOT A STRICT-AFTER PROOF"

    fun fields(reading: NetworkTimeReading, reason: NetworkTimeSampleReason): JsonObject = buildJsonObject {
        put("sample_reason", reason.name)
        put("sdk_int", reading.sdkInt)
        put("network_time_available", reading.networkEpochMillis != null)
        reading.networkEpochMillis?.let {
            put("network_time_epoch_ms", it)
            put("wall_minus_network_ms", reading.wallMillis - it)
        }
        reading.unavailableReason?.let { put("network_time_unavailable_reason", it) }
        put("capture_span_nanos", reading.elapsedNanosAfter - reading.elapsedNanosBefore)
        put("reference_kind", REFERENCE_KIND)
        put("evidentiary_scope", SCOPE)
    }
}

/** Process-exit classification (M-11). Derived flags are conservative; raw reason and description are kept. */
object ProcessExitEvidence {
    /** Constant values of `ApplicationExitInfo.REASON_LOW_MEMORY` and `REASON_OTHER`. */
    const val REASON_LOW_MEMORY = 3
    const val REASON_OTHER = 13
    private val MEMORY_LIMITER_MARKERS = listOf("MemoryLimiter:AnonSwap", "MemoryLimiter")

    /** Android 17 memory limiter: `REASON_OTHER` with a MemoryLimiter marker in the description, nothing else. */
    fun memoryLimiterSuspected(reason: Int, description: String?): Boolean =
        reason == REASON_OTHER && description != null && MEMORY_LIMITER_MARKERS.any { description.contains(it) }
}
