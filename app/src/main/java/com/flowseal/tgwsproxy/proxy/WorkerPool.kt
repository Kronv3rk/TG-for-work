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
    clock: () -> Long = System::currentTimeMillis
) : Closeable {
    private val delegate = WsPool(
        size = size,
        connector = { target, domain ->
            val separator = target.indexOf('|')
            require(separator > 0) { "Invalid Worker destination" }
            val dcId = target.substring(0, separator).toInt()
            val destination = target.substring(separator + 1)
            connector(dcId, destination, domain)
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
        if (domains.isEmpty()) return null
        return delegate.get(WsPool.Key(dcId, false), "$dcId|$destination", domains)
    }

    fun clear() = delegate.clear()
    fun maintain() = delegate.maintain()
    override fun close() = delegate.close()
}
