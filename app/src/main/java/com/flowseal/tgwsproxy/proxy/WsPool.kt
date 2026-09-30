package com.flowseal.tgwsproxy.proxy

import java.io.Closeable
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pre-opened WebSocket relay connections per (DC, media) pair, so a new Telegram
 * connection skips the TCP+TLS+upgrade round trips (port of upstream `pool.py`).
 *
 * Android-specific limits on top of upstream, to keep battery and traffic in check:
 * - a pair is only kept warm while it is in use: after [IDLE_KEY_MS] without a
 *   request its sockets are closed and it is no longer refilled;
 * - the owner switches the whole pool off on cellular ([isActive]).
 *
 * As upstream: sockets older than [MAX_AGE_MS] are rotated out, refills back off
 * exponentially after failures ([BACKOFF_INITIAL_MS]..[BACKOFF_MAX_MS]) and a pair
 * whose relay address is blocked is not refilled at all.
 */
class WsPool<T : Closeable>(
    private val size: Int,
    private val connector: (host: String, domain: String) -> T,
    private val isOpen: (T) -> Boolean,
    private val isTargetBlocked: (String) -> Boolean,
    private val isActive: () -> Boolean,
    private val background: (Runnable) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis
) : Closeable {
    data class Key(val dcId: Int, val isMedia: Boolean)

    private class Pooled<T>(val ws: T, val createdAt: Long)

    private class Slot<T>(val host: String, val domains: List<String>) {
        val idle = ArrayDeque<Pooled<T>>()
        var lastUsedAt = 0L
        var refilling = false
        var failures = 0
        var refillAfter = 0L
    }

    private val slots = ConcurrentHashMap<Key, Slot<T>>()
    private val hits = AtomicInteger(0)
    private val misses = AtomicInteger(0)

    @Volatile
    private var closed = false

    val hitCount: Int
        get() = hits.get()

    val missCount: Int
        get() = misses.get()

    /** A ready socket for [key], or null (the caller then dials itself). Always schedules a refill. */
    fun get(key: Key, host: String, domains: List<String>): T? {
        if (closed || !isActive()) return null
        val now = clock()
        val slot = slots.compute(key) { _, existing ->
            // A changed relay address or domain order invalidates what is pooled.
            if (existing != null && existing.host == host && existing.domains == domains) {
                existing
            } else {
                existing?.let(::drain)
                Slot(host, domains)
            }
        }!!
        var result: T? = null
        synchronized(slot) {
            slot.lastUsedAt = now
            while (result == null) {
                val pooled = slot.idle.pollFirst() ?: break
                if (now - pooled.createdAt < MAX_AGE_MS && isOpen(pooled.ws)) {
                    result = pooled.ws
                } else {
                    quietClose(pooled.ws)
                }
            }
        }
        if (result != null) hits.incrementAndGet() else misses.incrementAndGet()
        scheduleRefill(key, slot)
        return result
    }

    /** Periodic upkeep: rotate stale sockets, drop pairs nobody uses, top up the rest. */
    fun maintain() {
        if (closed) return
        if (!isActive()) {
            clear()
            return
        }
        val now = clock()
        for ((key, slot) in slots) {
            val idleTooLong = synchronized(slot) {
                val iterator = slot.idle.iterator()
                while (iterator.hasNext()) {
                    val pooled = iterator.next()
                    if (now - pooled.createdAt >= MAX_AGE_MS || !isOpen(pooled.ws)) {
                        iterator.remove()
                        quietClose(pooled.ws)
                    }
                }
                now - slot.lastUsedAt >= IDLE_KEY_MS
            }
            if (idleTooLong) {
                if (slots.remove(key, slot)) drain(slot)
            } else {
                scheduleRefill(key, slot)
            }
        }
    }

    /** Closes every pooled socket, e.g. when the network changes under them. */
    fun clear() {
        for (key in slots.keys.toList()) {
            slots.remove(key)?.let(::drain)
        }
    }

    fun idleCount(key: Key): Int = slots[key]?.let { synchronized(it) { it.idle.size } } ?: 0

    override fun close() {
        closed = true
        clear()
    }

    private fun scheduleRefill(key: Key, slot: Slot<T>) {
        val now = clock()
        synchronized(slot) {
            if (slot.refilling || slot.idle.size >= size || now < slot.refillAfter) return
            if (isTargetBlocked(slot.host)) return
            slot.refilling = true
        }
        try {
            background(Runnable { refill(key, slot) })
        } catch (rejected: Exception) {
            synchronized(slot) { slot.refilling = false }
        }
    }

    private fun refill(key: Key, slot: Slot<T>) {
        var connected = 0
        var attempted = 0
        try {
            while (!closed && isActive() && slots[key] === slot) {
                val needed = synchronized(slot) { size - slot.idle.size }
                if (needed <= 0) break
                attempted++
                val ws = connectOne(slot) ?: break
                val accepted = synchronized(slot) {
                    if (slots[key] === slot && !closed) {
                        slot.idle.addLast(Pooled(ws, clock()))
                        true
                    } else {
                        false
                    }
                }
                if (!accepted) {
                    quietClose(ws)
                    break
                }
                connected++
            }
        } finally {
            synchronized(slot) {
                slot.refilling = false
                if (connected > 0 || attempted == 0) {
                    slot.failures = 0
                    slot.refillAfter = 0L
                } else {
                    slot.failures++
                    val shift = (slot.failures - 1).coerceAtMost(MAX_BACKOFF_SHIFT)
                    val delay = (BACKOFF_INITIAL_MS shl shift).coerceAtMost(BACKOFF_MAX_MS)
                    slot.refillAfter = clock() + delay
                }
            }
        }
    }

    private fun connectOne(slot: Slot<T>): T? {
        for (domain in slot.domains) {
            try {
                return connector(slot.host, domain)
            } catch (redirect: WebSocketHandshakeException) {
                if (!redirect.isRedirect) return null
            } catch (ignored: Exception) {
                return null
            }
        }
        return null
    }

    private fun drain(slot: Slot<T>) {
        synchronized(slot) {
            while (true) quietClose(slot.idle.pollFirst()?.ws ?: break)
        }
    }

    private fun quietClose(ws: T) {
        runCatching { ws.close() }
    }

    companion object {
        const val DEFAULT_SIZE = 2
        const val MAX_AGE_MS = 120_000L
        const val MAINTAIN_INTERVAL_MS = 5_000L
        const val IDLE_KEY_MS = 300_000L
        const val BACKOFF_INITIAL_MS = 1_000L
        const val BACKOFF_MAX_MS = 3_600_000L
        const val CONNECT_TIMEOUT_MS = 8_000
        private const val MAX_BACKOFF_SHIFT = 12

        /** Runs [pool]'s upkeep every [MAINTAIN_INTERVAL_MS] until the returned scheduler is shut down. */
        fun startMaintenance(pool: WsPool<*>): ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "tg-ws-pool-upkeep").apply { isDaemon = true }
            }.apply {
                scheduleWithFixedDelay(
                    { runCatching { pool.maintain() } },
                    MAINTAIN_INTERVAL_MS,
                    MAINTAIN_INTERVAL_MS,
                    TimeUnit.MILLISECONDS
                )
            }
    }
}
