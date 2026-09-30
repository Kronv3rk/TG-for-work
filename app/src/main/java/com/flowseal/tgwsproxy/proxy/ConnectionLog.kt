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

    /**
     * The whole journal, latest first. [censored] masks domains for text that leaves
     * the device (copy/share): the on-screen journal keeps them readable, since
     * knowing which Worker or Cloudflare domain failed is what makes it useful.
     */
    fun fullText(censored: Boolean = false): String {
        val snapshot = synchronized(lock) { entries.toList() }
        val text = snapshot.asReversed().joinToString("\n", transform = ::format)
        return if (censored) censorDomains(text) else text
    }

    /**
     * Masks every domain except Telegram's own, the way upstream tg-ws-proxy's
     * DomainCensorFilter does: each label but the last keeps its first half and the
     * rest becomes `*` ("my-worker.example.com" -> "my-w*****.exa****.com").
     * IP addresses are left alone because the pattern needs a letter-only TLD.
     */
    fun censorDomains(text: String): String = DOMAIN_REGEX.replace(text) { match ->
        val domain = match.value
        val normalized = domain.lowercase(Locale.US).trimEnd('.')
        if (normalized == "telegram.org" || normalized.endsWith(".telegram.org")) {
            return@replace domain
        }
        val labels = domain.split('.')
        labels.mapIndexed { index, label ->
            if (index == labels.lastIndex) label else label.take(label.length / 2) + "*".repeat(label.length - label.length / 2)
        }.joinToString(".")
    }

    private val DOMAIN_REGEX = Regex(
        "(?<![\\w-])(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,}(?![\\w-])"
    )

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
