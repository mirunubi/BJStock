package com.mirunubi.bjstock.probe.intraday

import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object ProbeSubscriptionMessage {
    /** Contains the approval key: send it on the socket only, never record or log it. */
    fun build(approvalKey: ProbeSecret, trId: String, trKey: String, trType: String): String = buildJsonObject {
        put(
            "header",
            buildJsonObject {
                put("approval_key", approvalKey.reveal())
                put("custtype", ProbeScope.CUSTOMER_TYPE)
                put("tr_type", trType)
                put("content-type", "utf-8")
            },
        )
        put("body", buildJsonObject { put("input", buildJsonObject { put("tr_id", trId); put("tr_key", trKey) }) })
    }.toString()
}

/**
 * Transient transport failures reconnect with backoff. A positive-allowlist denial is a policy decision and is
 * final for the session: no retry, no reconnect, and [onTerminalError] is reported once.
 */
class ProbeStreamSession(
    private val network: ProbeNetwork,
    private val allowlist: ProbeWebSocketAllowlist,
    private val approvalKey: ProbeSecret,
    private val recorder: ProbeRecorder,
    private val clock: ProbeClock,
    private val scope: CoroutineScope,
    private val tracker: StreamContinuityTracker,
    private val onStatus: (ProbeStatus) -> Unit,
    private val onTerminalError: (ProbeErrorCode) -> Unit = {},
    private val symbols: List<String> = ProbeScope.SYMBOLS,
    private val backoffSeconds: List<Long> = listOf(1, 2, 5, 10, 30),
) {
    private val lock = Any()
    private var socket: ProbeSocket? = null
    private var callbacks: Callbacks? = null
    private var running = false
    private var terminated = false
    private var attempt = 0
    private var consecutiveFailures = 0
    private var reconnectJob: Job? = null

    val recordCount = AtomicLong()
    val frameCount = AtomicLong()
    val frameIssueCount = AtomicLong()
    val reconnectCount = AtomicLong()

    val isTerminated: Boolean get() = synchronized(lock) { terminated }

    fun start() {
        synchronized(lock) {
            if (running || terminated) return
            running = true
        }
        connect()
    }

    /** Stops producing: deactivates the current callbacks, cancels any reconnect, and closes the socket. */
    fun stop() {
        val current = synchronized(lock) {
            running = false
            reconnectJob?.cancel()
            callbacks?.active = false
            callbacks = null
            socket.also { socket = null }
        }
        current?.close(1000, "probe stop")
    }

    /** [stop] plus waiting until a reconnect coroutine that was already running has finished. */
    suspend fun stopAndJoin() {
        stop()
        val job = synchronized(lock) { reconnectJob }
        job?.join()
    }

    private fun connect() {
        val currentAttempt = synchronized(lock) {
            if (!running) return
            attempt += 1
            attempt
        }
        recorder.record(ProbeEventType.WS_CONNECTING, buildJsonObject { put("attempt", currentAttempt) })
        val newCallbacks = Callbacks()
        synchronized(lock) {
            if (!running) return
            callbacks = newCallbacks
        }
        val created = try {
            network.connect(newCallbacks)
        } catch (error: Exception) {
            onConnectionLost(newCallbacks, ProbeEventType.WS_FAILURE, error, buildJsonObject { })
            return
        }
        val keep = synchronized(lock) {
            if (running && newCallbacks.active) {
                socket = created
                newCallbacks.target = created
                true
            } else {
                false
            }
        }
        if (!keep) {
            created.close(1000, "probe stop")
            return
        }
        subscribeOnce(newCallbacks)
    }

    /** Subscribes exactly once per connection, after both the socket reference and the open callback exist. */
    private fun subscribeOnce(callbacks: Callbacks) {
        val target = synchronized(lock) {
            val target = callbacks.target
            if (!callbacks.active || !callbacks.opened || callbacks.subscribed || target == null) return
            callbacks.subscribed = true
            target
        }
        subscribeAll(callbacks, target)
    }

    /** Every subscription is checked before any is sent; one denial terminates the session without sending. */
    private fun subscribeAll(callbacks: Callbacks, target: ProbeSocket) {
        for (symbol in symbols) {
            val decision = allowlist.checkSubscription(ProbeScope.STREAM_TR_ID, symbol, ProbeScope.SUBSCRIBE_TR_TYPE)
            if (decision is AllowlistDecision.Denied) {
                terminate(callbacks, "SUBSCRIPTION", decision.reason, symbol)
                return
            }
        }
        for (symbol in symbols) {
            val sent = target.send(
                ProbeSubscriptionMessage.build(approvalKey, ProbeScope.STREAM_TR_ID, symbol, ProbeScope.SUBSCRIBE_TR_TYPE),
            )
            recorder.record(
                ProbeEventType.WS_SUBSCRIBE_SENT,
                buildJsonObject {
                    put("tr_id", ProbeScope.STREAM_TR_ID)
                    put("tr_key", symbol)
                    put("tr_type", ProbeScope.SUBSCRIBE_TR_TYPE)
                    put("sent", sent)
                },
            )
        }
    }

    private fun terminate(callbacks: Callbacks, stage: String, reason: String, trKey: String?) {
        val current = synchronized(lock) {
            if (!callbacks.active || terminated) return
            callbacks.active = false
            terminated = true
            running = false
            reconnectJob?.cancel()
            this.callbacks = null
            socket.also { socket = null }
        }
        current?.close(1000, "probe policy denial")
        recorder.record(
            ProbeEventType.PROBE_ERROR,
            buildJsonObject {
                put("error_code", ProbeErrorCode.ALLOWLIST_DENIED.name)
                put("stage", stage)
                put("reason", reason)
                trKey?.let { put("tr_key", it) }
                put("terminal", true)
            },
        )
        onTerminalError(ProbeErrorCode.ALLOWLIST_DENIED)
    }

    private fun onConnectionLost(callbacks: Callbacks, type: ProbeEventType, error: Throwable?, fields: JsonObject) {
        val denial = error?.let(::allowlistDenial)
        if (denial != null) {
            if (!callbacks.active) return
            recorder.record(
                type,
                buildJsonObject {
                    put("error_class", denial.javaClass.simpleName)
                    put("error_code", ProbeErrorCode.ALLOWLIST_DENIED.name)
                },
            )
            terminate(callbacks, "ENDPOINT", denial.reason, null)
            return
        }
        val shouldReconnect = synchronized(lock) {
            if (!callbacks.active) return
            callbacks.active = false
            if (this.callbacks === callbacks) this.callbacks = null
            socket = null
            running
        }
        val now = clock.elapsedRealtimeNanos()
        tracker.onDisconnected(now)
        recorder.record(
            type,
            buildJsonObject {
                fields.forEach { (key, value) -> put(key, value) }
                error?.let { put("error_class", it.javaClass.simpleName) }
                callbacks.connectionTiming(this, now)
            },
        )
        if (!shouldReconnect) return
        onStatus(ProbeStatus.RECONNECTING)
        val delaySeconds = synchronized(lock) {
            backoffSeconds[minOf(consecutiveFailures, backoffSeconds.lastIndex)].also { consecutiveFailures++ }
        }
        reconnectCount.incrementAndGet()
        recorder.record(ProbeEventType.WS_RECONNECT_SCHEDULED, buildJsonObject { put("delay_seconds", delaySeconds) })
        synchronized(lock) {
            if (!running) return
            reconnectJob = scope.launch {
                delay(delaySeconds * 1000)
                recorder.record(ProbeEventType.WS_RECONNECT_ATTEMPT, buildJsonObject { put("reconnect_count", reconnectCount.get()) })
                connect()
            }
        }
    }

    private fun allowlistDenial(error: Throwable): ProbeAllowlistDeniedException? =
        generateSequence(error) { it.cause }.take(MAX_CAUSE_DEPTH).filterIsInstance<ProbeAllowlistDeniedException>().firstOrNull()

    private inner class Callbacks : ProbeSocketCallbacks {
        @Volatile
        var active = true
        var target: ProbeSocket? = null
        var opened = false
        var subscribed = false
        private var openedAtNanos: Long? = null
        private var pingPongCount = 0L
        private var lastPingPongNanos: Long? = null

        override fun onOpen() {
            if (!active) return
            val nowNanos = clock.elapsedRealtimeNanos()
            val currentAttempt = synchronized(lock) {
                opened = true
                openedAtNanos = nowNanos
                consecutiveFailures = 0
                attempt
            }
            recorder.record(ProbeEventType.WS_CONNECTED, buildJsonObject { put("attempt", currentAttempt) })
            tracker.onConnected(nowNanos)?.let { gap ->
                recorder.record(
                    ProbeEventType.STREAM_POSSIBLE_GAP,
                    buildJsonObject {
                        put("classification", gap.classification)
                        put("cause", gap.cause)
                        put("start_elapsed_realtime_nanos", gap.startElapsedNanos)
                        put("end_elapsed_realtime_nanos", gap.endElapsedNanos)
                        put("duration_nanos", gap.durationNanos)
                    },
                )
            }
            onStatus(ProbeStatus.CONNECTED)
            subscribeOnce(this)
        }

        override fun onText(text: String) {
            if (!active) return
            val receiptWall = clock.wallMillis()
            val receiptNanos = clock.elapsedRealtimeNanos()
            handleText(this, text, receiptWall, receiptNanos)
        }

        override fun onClosed(code: Int) =
            onConnectionLost(this, ProbeEventType.WS_DISCONNECTED, null, buildJsonObject { put("close_code", code) })

        override fun onFailure(error: Throwable) = onConnectionLost(this, ProbeEventType.WS_FAILURE, error, buildJsonObject { })

        fun notePingPong(nowNanos: Long) = synchronized(lock) {
            pingPongCount++
            lastPingPongNanos = nowNanos
        }

        fun connectionTiming(builder: JsonObjectBuilder, nowNanos: Long) = synchronized(lock) {
            builder.put("connection_opened", openedAtNanos != null)
            openedAtNanos?.let { builder.put("connection_duration_nanos", nowNanos - it) }
            builder.put("pingpong_count_this_connection", pingPongCount)
            lastPingPongNanos?.let { builder.put("since_last_pingpong_nanos", nowNanos - it) }
        }
    }

    private fun handleText(callbacks: Callbacks, text: String, receiptWall: Long, receiptNanos: Long) {
        frameCount.incrementAndGet()
        when (val message = H0stcnt0FrameParser.parse(text)) {
            is KisStreamMessage.TradeFrame -> handleTradeFrame(message, receiptWall, receiptNanos)
            is KisStreamMessage.Control -> handleControl(callbacks, message, text, receiptWall, receiptNanos)
            is KisStreamMessage.Malformed -> {
                frameIssueCount.incrementAndGet()
                recorder.recordAt(
                    ProbeEventType.WS_FRAME,
                    receiptWall,
                    receiptNanos,
                    buildJsonObject {
                        put("frame_kind", "MALFORMED")
                        put("reason", message.reason)
                        put("length", message.length)
                        put("sha256", message.sha256)
                        put("error_code", ProbeErrorCode.WS_PARSE_FAILED.name)
                    },
                )
            }
        }
    }

    private fun handleTradeFrame(frame: KisStreamMessage.TradeFrame, receiptWall: Long, receiptNanos: Long) {
        if (frame.issue != null) frameIssueCount.incrementAndGet()
        recorder.recordAt(
            ProbeEventType.WS_FRAME,
            receiptWall,
            receiptNanos,
            buildJsonObject {
                put("frame_kind", "DATA")
                put("encrypted_flag", frame.encryptedFlag)
                put("tr_id", frame.trId)
                put("declared_count_raw", frame.declaredCountRaw)
                frame.declaredCount?.let { put("declared_count", it) }
                put("field_count", frame.fieldCount)
                frame.observedWidth?.let { put("observed_width", it) }
                put("expected_width", H0stcnt0Columns.WIDTH)
                put("parsed_records", frame.records.size)
                frame.issue?.let { put("issue", it.name) }
                put("payload_sha256", frame.payloadSha256)
                put("payload_length", frame.payloadLength)
            },
        )
        if (frame.records.isNotEmpty()) onStatus(ProbeStatus.RUNNING)
        val frameId = frameCount.get()
        for (record in frame.records) {
            recordCount.incrementAndGet()
            val firstAfterReconnect = tracker.consumeFirstRecordAfterReconnect(record.symbol)
            val outOfOrder = tracker.isOutOfOrder(record.symbol, record.tradeTimeHhmmss)
            recorder.recordAt(
                ProbeEventType.WS_RECORD,
                receiptWall,
                receiptNanos,
                buildJsonObject {
                    put("frame_seq", frameId)
                    put("tr_id", frame.trId)
                    frame.declaredCount?.let { put("frame_record_count", it) }
                    put("record_index", record.indexInFrame)
                    put("symbol", record.symbol)
                    put("symbol_allowed", record.symbol in symbols)
                    put("provider_trade_time_hhmmss", record.tradeTimeHhmmss)
                    put("provider_trade_price", record.price)
                    put("provider_trade_volume", record.tradeVolume)
                    put("provider_cumulative_volume", record.cumulativeVolume)
                    put("provider_business_date", record.businessDate)
                    put("provider_ccld_dvsn", record.conclusionType)
                    put("provider_new_mkop_cls_code", record.newMarketOperationCode)
                    put("provider_trht_yn", record.tradingHaltYn)
                    put("provider_hour_cls_code", record.hourClassCode)
                    put("provider_mrkt_trtm_cls_code", record.marketTerminationCode)
                    put("provider_vi_stnd_prc", record.viStandardPrice)
                    put("provider_market_cls_code", record.marketClassCode)
                    put("first_after_reconnect", firstAfterReconnect)
                    put("out_of_order_vs_previous", outOfOrder)
                    derivedReceiptMinusProviderSecond(receiptWall, record)?.let {
                        put("derived_receipt_wall_minus_provider_second_start_ms", it)
                    }
                },
            )
        }
    }

    private fun handleControl(
        callbacks: Callbacks,
        control: KisStreamMessage.Control,
        raw: String,
        receiptWall: Long,
        receiptNanos: Long,
    ) {
        var pingPongReplySent: Boolean? = null
        var pingPongReplySkippedReason: String? = null
        if (control.isPingPong) {
            callbacks.notePingPong(receiptNanos)
            if (raw.length <= MAX_PINGPONG_LENGTH && !raw.contains("approval_key")) {
                pingPongReplySent = synchronized(lock) { socket }?.send(raw) ?: false
            } else {
                pingPongReplySkippedReason = "UNSAFE_OR_OVERSIZED_PAYLOAD"
            }
        }
        recorder.recordAt(
            ProbeEventType.WS_FRAME,
            receiptWall,
            receiptNanos,
            buildJsonObject {
                put("frame_kind", if (control.isPingPong) "PINGPONG" else "CONTROL")
                control.trId?.let { put("tr_id", it) }
                control.trKey?.let { put("tr_key", it) }
                control.encrypt?.let { put("encrypt", it) }
                control.rtCd?.let { put("rt_cd", it) }
                control.msgCd?.let { put("msg_cd", it) }
                control.msg1?.let { put("msg1", it) }
                if (control.isPingPong) {
                    put("pingpong_reply_attempted", pingPongReplySent != null)
                    put("pingpong_reply_mode", "TEXT_ECHO_UNVERIFIED")
                }
                pingPongReplySent?.let { put("pingpong_text_echo_sent", it) }
                pingPongReplySkippedReason?.let { put("pingpong_reply_skipped_reason", it) }
            },
        )
        if (control.rtCd != null && control.rtCd != "0") {
            recorder.recordAt(
                ProbeEventType.PROBE_ERROR,
                receiptWall,
                receiptNanos,
                buildJsonObject {
                    put("error_code", ProbeErrorCode.WS_CONNECT_FAILED.name)
                    put("reason", "CONTROL_RT_CD_NON_ZERO")
                    control.msgCd?.let { put("msg_cd", it) }
                },
            )
        }
    }

    /** DERIVED: device receipt wall time minus the start of the provider's KST second; not a proof of anything. */
    private fun derivedReceiptMinusProviderSecond(receiptWall: Long, record: TradeRecord): Long? {
        val date = runCatching { LocalDate.parse(record.businessDate, BASIC_DATE) }.getOrNull() ?: return null
        val time = record.tradeTimeHhmmss
        if (!time.matches(Regex("^\\d{6}$"))) return null
        val providerSecond = runCatching {
            date.atTime(time.substring(0, 2).toInt(), time.substring(2, 4).toInt(), time.substring(4, 6).toInt())
                .atZone(StrictAfterEvidenceClassifier.KST)
                .toInstant()
        }.getOrNull() ?: return null
        return Instant.ofEpochMilli(receiptWall).toEpochMilli() - providerSecond.toEpochMilli()
    }

    private companion object {
        const val MAX_PINGPONG_LENGTH = 512
        const val MAX_CAUSE_DEPTH = 8
        val BASIC_DATE: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
    }
}
