package com.mirunubi.bjstock.probe.intraday

import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeClockEvidenceTest {
    /** 09:30:00.400 KST on 2026-10-02. */
    private val reference = Instant.parse("2026-10-02T00:30:00.400Z")
    private val date = LocalDate.of(2026, 10, 2)

    private fun classify(hhmmss: String, inputs: StrictAfterProofInputs) =
        StrictAfterEvidenceClassifier.classify(reference, hhmmss, inputs)

    // 18. same-second is UNPROVEN
    @Test
    fun sameSecondIsAlwaysUnproven() {
        ProviderTimestampSemantics.entries.forEach { semantics ->
            listOf(null, 0L, 1L).forEach { bound ->
                assertEquals(
                    StrictAfterClassification.UNPROVEN_SAME_SECOND,
                    classify("093000", StrictAfterProofInputs(semantics, bound, date)),
                )
            }
        }
    }

    // 19. provably-later requires explicit proof
    @Test
    fun laterSecondNeedsExplicitSemanticsAndClockBound() {
        assertEquals(StrictAfterClassification.UNPROVEN_DATE, classify("093001", StrictAfterProofInputs()))
        assertEquals(
            StrictAfterClassification.UNPROVEN_TIMESTAMP_SEMANTICS,
            classify("093001", StrictAfterProofInputs(providerDateKst = date, clockOffsetBoundMillis = 0)),
        )
        assertEquals(
            StrictAfterClassification.UNPROVEN_CLOCK_OFFSET,
            classify("093001", StrictAfterProofInputs(ProviderTimestampSemantics.TRUNCATION_PROVEN, null, date)),
        )
        assertEquals(
            StrictAfterClassification.PROVABLY_LATER,
            classify("093001", StrictAfterProofInputs(ProviderTimestampSemantics.TRUNCATION_PROVEN, 100, date)),
        )
        assertEquals(
            StrictAfterClassification.UNPROVEN_WITHIN_UNCERTAINTY,
            classify("093001", StrictAfterProofInputs(ProviderTimestampSemantics.TRUNCATION_PROVEN, 700, date)),
        )
        assertEquals(
            StrictAfterClassification.UNPROVEN_WITHIN_UNCERTAINTY,
            classify("093001", StrictAfterProofInputs(ProviderTimestampSemantics.ROUNDING_PROVEN, 100, date)),
        )
    }

    @Test
    fun defaultInputsNeverProduceProvablyLater() {
        for (second in 0..59) {
            val hhmmss = "0930" + second.toString().padStart(2, '0')
            assertNotEquals(StrictAfterClassification.PROVABLY_LATER, classify(hhmmss, StrictAfterProofInputs()))
            assertNotEquals(StrictAfterClassification.PROVABLY_LATER, classify(hhmmss, StrictAfterProofInputs(providerDateKst = date)))
        }
    }

    @Test
    fun earlierAndMalformedProviderTimesAreUnproven() {
        val proven = StrictAfterProofInputs(ProviderTimestampSemantics.TRUNCATION_PROVEN, 0, date)
        assertEquals(StrictAfterClassification.UNPROVEN_NOT_LATER, classify("092959", proven))
        listOf("93001", "0930010", "250000", "096000", "09300a", "").forEach {
            assertEquals(it, StrictAfterClassification.UNPROVEN_MALFORMED, classify(it, proven))
        }
    }

    @Test
    fun heartbeatGapUsesMonotonicLateness() {
        val expected = HeartbeatEvaluator.INTERVAL.toNanos()
        assertFalse(HeartbeatEvaluator.evaluate(expected, expected + 1_000_000_000L).isGap)
        val gap = HeartbeatEvaluator.evaluate(expected, expected + 16_000_000_000L)
        assertTrue(gap.isGap)
        assertEquals(16_000_000_000L, gap.lateByNanos)
        assertTrue(HeartbeatEvaluator.INTERVAL.seconds in 15..60)
    }

    @Test
    fun reconnectIsAPossibleGapAndNeverABackfill() {
        val tracker = StreamContinuityTracker()
        assertNull(tracker.onConnected(1_000))
        tracker.onDisconnected(2_000)
        tracker.onDisconnected(2_500)
        val gap = tracker.onConnected(5_000)!!
        assertEquals("UNPROVEN_POSSIBLE_GAP", gap.classification)
        assertEquals("RECONNECT", gap.cause)
        assertEquals(3_000, gap.durationNanos)
        assertEquals(1L, tracker.possibleGapCount)
    }

    // F-7: first-after-reconnect is tracked independently per approved symbol.
    @Test
    fun firstAfterReconnectIsPerSymbol() {
        val tracker = StreamContinuityTracker()
        tracker.onConnected(1_000)
        assertFalse("no reconnect yet", tracker.consumeFirstRecordAfterReconnect("005930"))
        tracker.onDisconnected(2_000)
        tracker.onConnected(3_000)

        assertTrue(tracker.consumeFirstRecordAfterReconnect("005930"))
        assertFalse(tracker.consumeFirstRecordAfterReconnect("005930"))
        assertTrue(tracker.consumeFirstRecordAfterReconnect("000660"))
        assertFalse(tracker.consumeFirstRecordAfterReconnect("000660"))
        assertFalse("unapproved symbol is never marked", tracker.consumeFirstRecordAfterReconnect("035420"))

        tracker.onDisconnected(4_000)
        tracker.onConnected(5_000)
        assertTrue(tracker.consumeFirstRecordAfterReconnect("000660"))
        assertTrue(tracker.consumeFirstRecordAfterReconnect("005930"))
    }

    // F-2: Android 17 MemoryLimiter is REASON_OTHER plus a MemoryLimiter marker; never generic LOW_MEMORY.
    @Test
    fun memoryLimiterClassificationIsConservative() {
        assertEquals(android.app.ApplicationExitInfo.REASON_OTHER, ProcessExitEvidence.REASON_OTHER)
        assertEquals(android.app.ApplicationExitInfo.REASON_LOW_MEMORY, ProcessExitEvidence.REASON_LOW_MEMORY)
        assertTrue(ProcessExitEvidence.memoryLimiterSuspected(ProcessExitEvidence.REASON_OTHER, "MemoryLimiter:AnonSwap"))
        assertTrue(ProcessExitEvidence.memoryLimiterSuspected(ProcessExitEvidence.REASON_OTHER, "killed: MemoryLimiter"))
        assertFalse(ProcessExitEvidence.memoryLimiterSuspected(ProcessExitEvidence.REASON_OTHER, "user swiped app"))
        assertFalse(ProcessExitEvidence.memoryLimiterSuspected(ProcessExitEvidence.REASON_OTHER, null))
        assertFalse(ProcessExitEvidence.memoryLimiterSuspected(ProcessExitEvidence.REASON_LOW_MEMORY, "MemoryLimiter:AnonSwap"))
        assertFalse(ProcessExitEvidence.memoryLimiterSuspected(ProcessExitEvidence.REASON_LOW_MEMORY, "lmkd"))
    }

    // F-3: network-time reference evidence.
    @Test
    fun networkTimeAvailableSerializesOffset() {
        val reading = NetworkTimeReading(33, 1_790_000_000_250L, 10L, 1_010L, 1_790_000_000_000L, null)
        val fields = NetworkTimeEvidence.fields(reading, NetworkTimeSampleReason.PERIODIC)
        assertEquals("PERIODIC", fields["sample_reason"]!!.jsonPrimitive.content)
        assertEquals(true, fields["network_time_available"]!!.jsonPrimitive.boolean)
        assertEquals(1_790_000_000_000L, fields["network_time_epoch_ms"]!!.jsonPrimitive.long)
        assertEquals(250L, fields["wall_minus_network_ms"]!!.jsonPrimitive.long)
        assertEquals(1_000L, fields["capture_span_nanos"]!!.jsonPrimitive.long)
        assertEquals(33, fields["sdk_int"]!!.jsonPrimitive.int)
        assertTrue(fields["evidentiary_scope"]!!.jsonPrimitive.content.contains("NOT EXCHANGE TIME"))
    }

    @Test
    fun networkTimeUnavailableIsRecordedWithoutInventingZero() {
        val reading = NetworkTimeReading(28, 1_790_000_000_250L, 10L, 10L, null, "API_BELOW_33")
        val fields = NetworkTimeEvidence.fields(reading, NetworkTimeSampleReason.SESSION_START)
        assertEquals(false, fields["network_time_available"]!!.jsonPrimitive.boolean)
        assertFalse(fields.containsKey("network_time_epoch_ms"))
        assertFalse(fields.containsKey("wall_minus_network_ms"))
        assertEquals("API_BELOW_33", fields["network_time_unavailable_reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun networkTimeSampleAloneCannotProveStrictAfter() {
        val reading = NetworkTimeReading(36, reference.toEpochMilli(), 0L, 1L, reference.toEpochMilli(), null)
        val fields = NetworkTimeEvidence.fields(reading, NetworkTimeSampleReason.PERIODIC)
        assertTrue(fields.keys.none { it.contains("proven") || it.contains("bound") || it.contains("semantics") })
        // A perfect network-time agreement still leaves the classifier's default proof inputs unproven.
        for (second in 1..59) {
            val hhmmss = "0930" + second.toString().padStart(2, '0')
            val result = classify(hhmmss, StrictAfterProofInputs(providerDateKst = date))
            assertNotEquals(StrictAfterClassification.PROVABLY_LATER, result)
            assertEquals(StrictAfterClassification.UNPROVEN_TIMESTAMP_SEMANTICS, result)
        }
        assertEquals(
            StrictAfterClassification.UNPROVEN_CLOCK_OFFSET,
            classify("093001", StrictAfterProofInputs(ProviderTimestampSemantics.TRUNCATION_PROVEN, null, date)),
        )
    }

    @Test
    fun outOfOrderIsTrackedPerSymbol() {
        val tracker = StreamContinuityTracker()
        assertFalse(tracker.isOutOfOrder("005930", "093001"))
        assertFalse(tracker.isOutOfOrder("000660", "093000"))
        assertFalse(tracker.isOutOfOrder("005930", "093001"))
        assertTrue(tracker.isOutOfOrder("005930", "093000"))
    }
}
