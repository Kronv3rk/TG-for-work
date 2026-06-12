package com.flowseal.tgwsproxy.proxy

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Shared in-memory journal of proxy connection events with stability/quality
 * context (route, latency, transferred bytes, failure reasons).
 *
 * A singleton on purpose: it survives proxy restarts so the user can see what
 * happened across reconnects. Pure Kotlin/JVM so unit tests can exercise it;
 * [sink] lets the Android layer mirror every line to Logcat.
 */
object ConnectionLog {
    enum class Event(val symbol: String) {
        CONNECTED("✓"),
        CONNECT_FAILED("✗"),
        NO_DATA("∅"),
        SESSION_END("■"),
        REJECTED("⚠"),
        INFO("·")
    }

    data class Entry(
        val timeMs: Long,
        val event: Event,
        val text: String
    )

    @Volatile
    var sink: ((String) -> Unit)? = null

    private const val MAX_ENTRIES = 150
    private val lock = Any()
    private val entries = ArrayDeque<Entry>()

    fun connected(method: RouteMethod, dcId: Int, isMedia: Boolean, latencyMs: Long, target: String) {
        add(
            Event.CONNECTED,
            "${method.label} ${dcLabel(dcId, isMedia)} подключён за ${formatSeconds(latencyMs)} · $target"
        )
    }

    fun connectFailed(method: RouteMethod, dcId: Int, isMedia: Boolean, reason: String) {
        add(Event.CONNECT_FAILED, "${method.label} ${dcLabel(dcId, isMedia)} не ответил${reasonSuffix(reason)}")
    }

    fun noData(method: RouteMethod, dcId: Int, isMedia: Boolean) {
        add(Event.NO_DATA, "${method.label} ${dcLabel(dcId, isMedia)} подключился, но не передал данных")
    }

    fun sessionEnd(method: RouteMethod, dcId: Int, isMedia: Boolean, durationMs: Long, bytesDown: Long) {
        add(
            Event.SESSION_END,
            "${method.label} ${dcLabel(dcId, isMedia)} сессия ${formatSeconds(durationMs)} · ↓${formatBytes(bytesDown)}"
        )
    }

    fun rejected(reason: String) {
        add(Event.REJECTED, reason)
    }

    fun info(message: String) {
        add(Event.INFO, message)
    }

    /** Latest-first formatted lines for the UI journal. */
    fun recent(limit: Int): List<String> {
        val snapshot = synchronized(lock) { entries.toList() }
        return snapshot.asReversed().take(limit).map(::format)
    }

    fun fullText(): String {
        val snapshot = synchronized(lock) { entries.toList() }
        return snapshot.asReversed().joinToString("\n", transform = ::format)
    }

    fun size(): Int = synchronized(lock) { entries.size }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    private fun add(event: Event, text: String) {
        val entry = Entry(System.currentTimeMillis(), event, text)
        synchronized(lock) {
            entries.addLast(entry)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
        sink?.invoke(format(entry))
    }

    private fun format(entry: Entry): String {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(entry.timeMs))
        return "$time ${entry.event.symbol} ${entry.text}"
    }

    private fun dcLabel(dcId: Int, isMedia: Boolean): String =
        "DC$dcId${if (isMedia) "·медиа" else ""}"

    private fun reasonSuffix(reason: String): String =
        if (reason.isBlank()) "" else ": $reason"

    private fun formatSeconds(ms: Long): String = String.format(Locale.US, "%.1fс", ms / 1000.0)

    private fun formatBytes(value: Long): String {
        var n = value.toDouble()
        for (unit in arrayOf("B", "KB", "MB", "GB")) {
            if (n < 1024.0) return String.format(Locale.US, "%.1f %s", n, unit)
            n /= 1024.0
        }
        return String.format(Locale.US, "%.1f TB", n)
    }
}
