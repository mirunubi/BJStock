package com.mirunubi.bjstock.probe.intraday

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class ProbeStorageException(message: String, cause: Throwable? = null) : IOException(message, cause)

data class ProbeSessionFiles(val meta: File, val events: File, val summary: File)

/**
 * App-specific probe-only evidence files. Never Room, never a BJStock business table. Files are never deleted
 * by the probe; cleanup after Human B2 review is explicit and manual.
 */
class ProbeEvidenceStore(
    val baseDir: File,
    private val scrubber: SecretScrubber,
    private val json: Json = Json,
) {
    fun files(sessionId: String): ProbeSessionFiles {
        require(sessionId.matches(ProbeSessionId.PATTERN)) { "invalid probe session id" }
        return ProbeSessionFiles(
            meta = File(baseDir, "session_${sessionId}_meta.json"),
            events = File(baseDir, "session_${sessionId}_events.jsonl"),
            summary = File(baseDir, "session_${sessionId}_summary.json"),
        )
    }

    fun writeMeta(sessionId: String, meta: JsonObject) = writeWhole(files(sessionId).meta, meta)

    fun writeSummary(sessionId: String, summary: JsonObject) = writeWhole(files(sessionId).summary, summary)

    @Synchronized
    fun appendEvent(event: ProbeEvent) {
        val file = files(event.sessionId).events
        try {
            ensureDir()
            file.appendText(scrubber.scrub(json.encodeToString(JsonObject.serializer(), event.toJson())) + "\n")
        } catch (error: IOException) {
            throw ProbeStorageException("probe event append failed", error)
        }
    }

    @Synchronized
    private fun writeWhole(file: File, content: JsonObject) {
        try {
            ensureDir()
            val safe = JsonObject(content.mapValues { (k, v) -> redactIfForbidden(k, v) })
            file.writeText(scrubber.scrub(json.encodeToString(JsonObject.serializer(), safe)) + "\n")
        } catch (error: IOException) {
            throw ProbeStorageException("probe evidence write failed", error)
        }
    }

    private fun ensureDir() {
        if (!baseDir.isDirectory && !baseDir.mkdirs()) throw ProbeStorageException("probe evidence directory unavailable")
    }

    companion object {
        const val DIRECTORY_NAME = "intraday_probe"
    }
}

interface ProbeClock {
    fun wallMillis(): Long
    fun elapsedRealtimeNanos(): Long
}

/**
 * Captures both clock domains at the moment an event is observed, then writes asynchronously so that
 * main-thread and network callbacks never block on disk I/O. Counts cover persisted events only.
 */
class ProbeRecorder(
    val sessionId: String,
    private val store: ProbeEvidenceStore,
    private val clock: ProbeClock,
    private val onStorageFailure: (ProbeStorageException) -> Unit,
) {
    private val channel = Channel<ProbeEvent>(Channel.UNLIMITED)
    private val persistedCounts = ConcurrentHashMap<ProbeEventType, AtomicLong>()
    private var writer: Job? = null

    @Volatile
    private var closed = false

    val rejectedAfterClose = AtomicLong()
    val notPersisted = AtomicLong()

    @Volatile
    var storageFailed: Boolean = false
        private set

    fun start(scope: CoroutineScope) {
        writer = scope.launch(Dispatchers.IO) {
            for (event in channel) {
                if (storageFailed) {
                    notPersisted.incrementAndGet()
                    continue
                }
                try {
                    store.appendEvent(event)
                    persistedCounts.getOrPut(event.type) { AtomicLong() }.incrementAndGet()
                } catch (error: ProbeStorageException) {
                    storageFailed = true
                    notPersisted.incrementAndGet()
                    onStorageFailure(error)
                }
            }
        }
    }

    fun record(type: ProbeEventType, fields: JsonObject = ProbeEvent.EMPTY_FIELDS): Boolean =
        recordAt(type, clock.wallMillis(), clock.elapsedRealtimeNanos(), fields)

    /** Returns false, and counts nothing as persisted, when the recorder is already closed. */
    fun recordAt(type: ProbeEventType, wallMillis: Long, elapsedNanos: Long, fields: JsonObject = ProbeEvent.EMPTY_FIELDS): Boolean {
        val accepted = !closed && channel.trySend(ProbeEvent(sessionId, type, wallMillis, elapsedNanos, fields)).isSuccess
        if (!accepted) rejectedAfterClose.incrementAndGet()
        return accepted
    }

    fun count(type: ProbeEventType): Long = persistedCounts[type]?.get() ?: 0L

    fun countsSnapshot(): Map<ProbeEventType, Long> = persistedCounts.mapValues { it.value.get() }

    /** Rejects further events, drains everything already accepted, and waits for the writer to finish. */
    suspend fun close() {
        closed = true
        channel.close()
        writer?.join()
    }
}
