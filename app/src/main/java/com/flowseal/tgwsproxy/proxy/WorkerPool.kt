package com.flowseal.tgwsproxy.proxy

import java.io.Closeable

/**
 * Reuses upstream's per-DC Worker WebSocket strategy with Android's bounded,
 * Wi-Fi-only idle pool. Every pooled connection is tied to one DC and its
 * destination address; network changes or address changes invalidate it.
 */
internal class WorkerPool<T : Closeable>(
    size: Int,
    connector: (dcId: Int, destination: String, domain: String) -> T,
    isOpen: (T) -> Boolean,
    isActive: () -> Boolean,
    background: (Runnable) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis
) : Closeable {
    private val cooldownLock = Any()
    private val cooldownUntil = mutableMapOf<String, Long>()
    private val quotaUntil = mutableMapOf<String, Long>()
    private var generation = 0L

    private companion object { const val DAY_MS = 86_400_000L }

    fun availableDomains(domains: List<String>): List<String> = synchronized(cooldownLock) {
        val now = clock()
        cooldownUntil.entries.removeAll { it.value <= now }
        quotaUntil.entries.removeAll { it.value <= now }
        domains.filter { it.isNotBlank() && it !in cooldownUntil && it !in quotaUntil }.distinct()
    }

    fun dial(domain: String, connect: () -> T): T {
        val attemptGeneration = synchronized(cooldownLock) {
            val now = clock()
            check((cooldownUntil[domain] ?: Long.MIN_VALUE) <= now &&
                (quotaUntil[domain] ?: Long.MIN_VALUE) <= now) {
                "Worker is temporarily unavailable"
            }
            generation
        }
        try {
            return connect()
        } catch (error: Exception) {
            synchronized(cooldownLock) {
                val now = clock()
                if (error is WebSocketHandshakeException && error.statusCode == 429) {
                    // Worker daily request quota resets at midnight UTC.
                    val until = now - Math.floorMod(now, DAY_MS) + DAY_MS
                    quotaUntil[domain] = maxOf(quotaUntil[domain] ?: 0L, until)
                } else if (generation == attemptGeneration &&
                    (error is java.net.SocketTimeoutException ||
                        error is WebSocketHandshakeException && error.statusCode in 500..599)) {
                    cooldownUntil[domain] = maxOf(cooldownUntil[domain] ?: 0L, now + 60_000L)
                }
            }
            throw error
        }
    }

    private val delegate = WsPool(
        size = size,
        connector = { target, domain ->
            val separator = target.indexOf('|')
            require(separator > 0) { "Invalid Worker destination" }
            val dcId = target.substring(0, separator).toInt()
            val destination = target.substring(separator + 1)
            dial(domain) { connector(dcId, destination, domain) }
        },
        isOpen = isOpen,
        isTargetBlocked = { false },
        isActive = isActive,
        background = background,
        clock = clock,
        maxAgeMs = 100_000L // upstream _CfWorkerPool.WS_POOL_MAX_AGE
    )

    fun get(dcId: Int, destination: String, domains: List<String>): T? {
        require(dcId > 0 && destination.isNotBlank() && '|' !in destination)
        // Upstream available_domains() removes duplicates before trying
        // candidates. Keep the canonical order stable across calls: WsPool
        // keys are sensitive to domain lists, and shuffling here would drop
        // healthy pre-opened sockets every time a Telegram session starts.
        val uniqueDomains = domains.asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
        if (uniqueDomains.isEmpty()) return null
        return delegate.get(WsPool.Key(dcId, false), "$dcId|$destination", uniqueDomains)
    }

    fun clear() {
        synchronized(cooldownLock) {
            generation++
            cooldownUntil.clear()
        }
        delegate.clear()
    }
    fun maintain() = delegate.maintain()
    override fun close() = delegate.close()
}
