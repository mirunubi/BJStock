package com.mirunubi.bjstock.probe.intraday

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProbeEvidenceTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val sessionId = ProbeSessionId.create(1_790_000_000_000L) { "0123abcd" }

    // 11. redaction
    @Test
    fun secretsNeverAppearInEventJsonOrToString() {
        val secret = ProbeSecret(ProbeFixtures.FAKE_APPROVAL_KEY)
        assertEquals("[REDACTED]", secret.toString())
        assertFalse("$secret".contains(ProbeFixtures.FAKE_APPROVAL_KEY))

        val event = ProbeEvent(
            sessionId,
            ProbeEventType.WS_SUBSCRIBE_SENT,
            1L,
            2L,
            buildJsonObject {
                put("approval_key", ProbeFixtures.FAKE_APPROVAL_KEY)
                put("appkey", ProbeFixtures.FAKE_APP_KEY)
                put("appsecret", ProbeFixtures.FAKE_APP_SECRET)
                put("authorization", "Bearer ${ProbeFixtures.FAKE_ACCESS_TOKEN}")
                put("nested", buildJsonObject { put("secretkey", ProbeFixtures.FAKE_APP_SECRET) })
                put("list", JsonArray(listOf(buildJsonObject { put("access_token", ProbeFixtures.FAKE_ACCESS_TOKEN) })))
                put("tr_key", "005930")
            },
        )
        val text = event.toJson().toString()
        ProbeFixtures.ALL_SECRETS.forEach { assertFalse("leaked $it", text.contains(it)) }
        assertTrue(text.contains("\"tr_key\":\"005930\""))
    }

    @Test
    fun scrubberRemovesRegisteredSecretsFromAnyLine() {
        val scrubber = SecretScrubber()
        ProbeFixtures.ALL_SECRETS.forEach { scrubber.register(it) }
        val line = "error near ${ProbeFixtures.FAKE_APP_KEY} and ${ProbeFixtures.FAKE_APPROVAL_KEY}"
        val scrubbed = scrubber.scrub(line)
        ProbeFixtures.ALL_SECRETS.forEach { assertFalse(scrubbed.contains(it)) }
        assertEquals(2L, scrubber.replacements)
        scrubber.clear()
        assertEquals(line, scrubber.scrub(line))
    }

    @Test
    fun evidenceFilesNeverContainSecrets() = runBlocking {
        val scrubber = SecretScrubber().apply { ProbeFixtures.ALL_SECRETS.forEach { register(it) } }
        val store = ProbeEvidenceStore(temp.root, scrubber)
        store.writeMeta(sessionId, buildJsonObject { put("appkey", ProbeFixtures.FAKE_APP_KEY); put("note", ProbeFixtures.FAKE_APP_SECRET) })
        store.writeSummary(sessionId, buildJsonObject { put("msg", "x ${ProbeFixtures.FAKE_ACCESS_TOKEN}") })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val recorder = ProbeRecorder(sessionId, store, FakeClock()) { fail("storage failed") }
        recorder.start(scope)
        recorder.record(ProbeEventType.PROBE_ERROR, buildJsonObject { put("detail", "raw ${ProbeFixtures.FAKE_APPROVAL_KEY}") })
        recorder.close()
        scope.cancel()
        val all = ProbeFixtures.readAll(temp.root)
        ProbeFixtures.ALL_SECRETS.forEach { assertFalse("leaked $it", all.contains(it)) }
        assertTrue(all.contains(ProbeRedaction.REDACTED))
    }

    // 12. frame count parse
    @Test
    fun singleRecordFrameParses() {
        val frame = H0stcnt0FrameParser.parse(ProbeFixtures.frame(ProbeFixtures.record("005930", "093001", "70000")))
        frame as KisStreamMessage.TradeFrame
        assertNull(frame.issue)
        assertEquals(1, frame.declaredCount)
        assertEquals(47, frame.observedWidth)
        assertEquals(1, frame.records.size)
        assertEquals("005930", frame.records[0].symbol)
        assertEquals("093001", frame.records[0].tradeTimeHhmmss)
        assertEquals("70000", frame.records[0].price)
        assertEquals("20261002", frame.records[0].businessDate)
    }

    // 13. multi-record frame parse
    @Test
    fun multiRecordFrameKeepsEveryRecordInOrder() {
        val raw = ProbeFixtures.frame(
            ProbeFixtures.record("005930", "093001", "70000"),
            ProbeFixtures.record("000660", "093001", "180000"),
            ProbeFixtures.record("005930", "093002", "70100"),
        )
        val frame = H0stcnt0FrameParser.parse(raw) as KisStreamMessage.TradeFrame
        assertNull(frame.issue)
        assertEquals(3, frame.records.size)
        assertEquals(listOf(0, 1, 2), frame.records.map { it.indexInFrame })
        assertEquals(listOf("005930", "000660", "005930"), frame.records.map { it.symbol })
        assertEquals(47 * 3, frame.fieldCount)
    }

    // 14. malformed frame handling
    @Test
    fun malformedFramesProduceNoRecords() {
        val record = ProbeFixtures.record("005930", "093001", "70000")
        fun issue(raw: String) = (H0stcnt0FrameParser.parse(raw) as KisStreamMessage.TradeFrame).let { it.issue to it.records.size }

        assertEquals(FrameIssue.ENCRYPTED_UNSUPPORTED to 0, issue(ProbeFixtures.frame(record, flag = "1")))
        assertEquals(FrameIssue.UNEXPECTED_TR to 0, issue(ProbeFixtures.frame(record, trId = "H0STASP0")))
        assertEquals(FrameIssue.DECLARED_COUNT_NOT_NUMERIC to 0, issue(ProbeFixtures.frame(record, count = "x")))
        assertEquals(FrameIssue.DECLARED_COUNT_NOT_POSITIVE to 0, issue(ProbeFixtures.frame(record, count = "0")))
        assertEquals(FrameIssue.FIELD_COUNT_NOT_DIVISIBLE to 0, issue("0|H0STCNT0|2|" + record.joinToString("^") + "^a^b"))
        // 48 fields declared as 2 records splits evenly into misaligned 24-wide slices: no record may be derived.
        assertEquals(FrameIssue.WIDTH_MISMATCH to 0, issue("0|H0STCNT0|2|" + record.joinToString("^") + "^extra"))
        assertEquals(FrameIssue.WIDTH_MISMATCH to 0, issue("0|H0STCNT0|1|005930^093001^70000^1"))
        assertEquals(FrameIssue.WIDTH_MISMATCH to 0, issue(ProbeFixtures.frame(record, record, count = "1")))

        assertEquals("PIPE_PARTS", (H0stcnt0FrameParser.parse("0|H0STCNT0") as KisStreamMessage.Malformed).reason)
        assertEquals("UNKNOWN_PREFIX", (H0stcnt0FrameParser.parse("hello") as KisStreamMessage.Malformed).reason)
        assertEquals("EMPTY", (H0stcnt0FrameParser.parse("") as KisStreamMessage.Malformed).reason)
        assertEquals("CONTROL_JSON", (H0stcnt0FrameParser.parse("{not json") as KisStreamMessage.Malformed).reason)
    }

    @Test
    fun controlFramesKeepOnlySafeFields() {
        val subscribeAck = """{"header":{"tr_id":"H0STCNT0","tr_key":"005930","encrypt":"N"},""" +
            """"body":{"rt_cd":"0","msg_cd":"OPSP0000","msg1":"SUBSCRIBE SUCCESS","output":{"iv":"IVVALUE123456789","key":"KEYVALUE1234567890"}}}"""
        val control = H0stcnt0FrameParser.parse(subscribeAck) as KisStreamMessage.Control
        assertEquals("0", control.rtCd)
        assertFalse(control.isPingPong)
        assertFalse(control.toString().contains("IVVALUE123456789"))
        assertFalse(control.toString().contains("KEYVALUE1234567890"))

        val ping = H0stcnt0FrameParser.parse("""{"header":{"tr_id":"PINGPONG","datetime":"20261002093000"}}""") as KisStreamMessage.Control
        assertTrue(ping.isPingPong)
    }

    // 15. JSON serialization
    @Test
    fun eventJsonCarriesBothClockDomainsAndIdentity() {
        val json = ProbeEvent(sessionId, ProbeEventType.HEARTBEAT, 1_790_000_000_123L, 987_654_321L, buildJsonObject {
            put("session_id", "spoofed")
            put("late_by_nanos", 5L)
        }).toJson()
        assertEquals(sessionId, json["session_id"]!!.jsonPrimitive.content)
        assertEquals("HEARTBEAT", json["event_type"]!!.jsonPrimitive.content)
        assertEquals(1_790_000_000_123L, json["wall_time_epoch_ms"]!!.jsonPrimitive.long)
        assertEquals(987_654_321L, json["elapsed_realtime_nanos"]!!.jsonPrimitive.long)
        assertEquals(5L, json["late_by_nanos"]!!.jsonPrimitive.long)
    }

    @Test
    fun recorderWritesOneJsonObjectPerLineInOrder() = runBlocking {
        val store = ProbeEvidenceStore(temp.root, SecretScrubber())
        val clock = FakeClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val recorder = ProbeRecorder(sessionId, store, clock) { fail("storage failed") }
        recorder.start(scope)
        repeat(5) { i ->
            clock.wall += 1000
            clock.nanos += 1_000_000_000L
            recorder.record(ProbeEventType.HEARTBEAT, buildJsonObject { put("i", i) })
        }
        recorder.close()
        scope.cancel()
        val lines = store.files(sessionId).events.readLines()
        assertEquals(5, lines.size)
        val parsed = lines.map { Json.parseToJsonElement(it).jsonObject }
        assertEquals((0..4).map { it.toLong() }, parsed.map { it["i"]!!.jsonPrimitive.long })
        assertEquals(parsed.map { it["elapsed_realtime_nanos"]!!.jsonPrimitive.long }.sorted(), parsed.map { it["elapsed_realtime_nanos"]!!.jsonPrimitive.long })
        assertEquals(5L, recorder.count(ProbeEventType.HEARTBEAT))
    }

    // 16. session identity
    @Test
    fun sessionIdIsUtcStampedRandomAndValidated() {
        val fixed = java.time.Instant.parse("2026-09-21T14:00:00Z").toEpochMilli()
        assertEquals("probe_20260921T140000Z_0123abcd", ProbeSessionId.create(fixed) { "0123abcd" })
        val a = ProbeSessionId.create(1_790_000_000_000L)
        val b = ProbeSessionId.create(1_790_000_000_000L)
        assertTrue(a.matches(ProbeSessionId.PATTERN))
        assertNotEquals(a, b)

        val store = ProbeEvidenceStore(temp.root, SecretScrubber())
        val files = store.files(sessionId)
        assertEquals("session_${sessionId}_meta.json", files.meta.name)
        assertEquals("session_${sessionId}_events.jsonl", files.events.name)
        assertEquals("session_${sessionId}_summary.json", files.summary.name)
        listOf("../escape", "probe_x", "", "probe_20261002T000000Z_ZZZZZZZZ").forEach { bad ->
            try {
                store.files(bad)
                fail("accepted invalid id $bad")
            } catch (expected: IllegalArgumentException) {
            }
        }
    }

    // F-5: a record after close is rejected and never counted as persisted.
    @Test
    fun recordAfterCloseIsRejectedAndNotCounted() = runBlocking {
        val store = ProbeEvidenceStore(temp.root, SecretScrubber())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val recorder = ProbeRecorder(sessionId, store, FakeClock()) { fail("storage failed") }
        recorder.start(scope)
        assertTrue(recorder.record(ProbeEventType.HEARTBEAT))
        assertTrue(recorder.record(ProbeEventType.SESSION_STOP))
        recorder.close()
        assertFalse(recorder.record(ProbeEventType.WS_DISCONNECTED))
        assertFalse(recorder.record(ProbeEventType.HEARTBEAT))
        scope.cancel()

        assertEquals(1L, recorder.count(ProbeEventType.HEARTBEAT))
        assertEquals(0L, recorder.count(ProbeEventType.WS_DISCONNECTED))
        assertEquals(2L, recorder.rejectedAfterClose.get())
        assertEquals(store.files(sessionId).events.readLines().size.toLong(), recorder.countsSnapshot().values.sum())
    }

    @Test
    fun evidenceStoreExposesNoDeletion() {
        val names = ProbeEvidenceStore::class.java.declaredMethods.map { it.name.lowercase() }
        assertTrue(names.none { it.contains("delete") || it.contains("clear") || it.contains("purge") || it.contains("remove") })
        assertEquals("intraday_probe", ProbeEvidenceStore.DIRECTORY_NAME)
    }
}
