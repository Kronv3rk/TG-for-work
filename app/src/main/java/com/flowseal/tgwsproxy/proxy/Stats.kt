package com.flowseal.tgwsproxy.proxy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicLong

class ProxyStats {
    private val _connectionsTotal = AtomicLong()
    private val _connectionsActive = AtomicLong()
    private val _connectionsWs = AtomicLong()
    private val _connectionsTcpFallback = AtomicLong()
    private val _connectionsBad = AtomicLong()
    private val _wsErrors = AtomicLong()
    private val _bytesUp = AtomicLong()
    private val _bytesDown = AtomicLong()
    private val _poolHits = AtomicLong()
    private val _poolMisses = AtomicLong()

    private val _snapshot = MutableStateFlow(snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot

    fun onConnect() { _connectionsTotal.incrementAndGet(); _connectionsActive.incrementAndGet(); publish() }
    fun onDisconnect() { _connectionsActive.decrementAndGet(); publish() }
    fun onBad() { _connectionsBad.incrementAndGet(); publish() }
    fun onWs() { _connectionsWs.incrementAndGet(); publish() }
    fun onTcpFallback() { _connectionsTcpFallback.incrementAndGet(); publish() }
    fun onWsError() { _wsErrors.incrementAndGet(); publish() }
    fun onBytesUp(n: Int) { _bytesUp.addAndGet(n.toLong()); publish() }
    fun onBytesDown(n: Int) { _bytesDown.addAndGet(n.toLong()); publish() }
    fun onPoolHit() { _poolHits.incrementAndGet(); publish() }
    fun onPoolMiss() { _poolMisses.incrementAndGet(); publish() }

    private fun publish() { _snapshot.value = snapshot() }

    private fun snapshot() = Snapshot(
        connectionsTotal = _connectionsTotal.get(),
        connectionsActive = _connectionsActive.get(),
        connectionsWs = _connectionsWs.get(),
        connectionsTcpFallback = _connectionsTcpFallback.get(),
        connectionsBad = _connectionsBad.get(),
        wsErrors = _wsErrors.get(),
        bytesUp = _bytesUp.get(),
        bytesDown = _bytesDown.get(),
        poolHits = _poolHits.get(),
        poolMisses = _poolMisses.get(),
    )

    fun reset() {
        _connectionsTotal.set(0); _connectionsActive.set(0)
        _connectionsWs.set(0); _connectionsTcpFallback.set(0)
        _connectionsBad.set(0); _wsErrors.set(0)
        _bytesUp.set(0); _bytesDown.set(0)
        _poolHits.set(0); _poolMisses.set(0)
        publish()
    }

    data class Snapshot(
        val connectionsTotal: Long,
        val connectionsActive: Long,
        val connectionsWs: Long,
        val connectionsTcpFallback: Long,
        val connectionsBad: Long,
        val wsErrors: Long,
        val bytesUp: Long,
        val bytesDown: Long,
        val poolHits: Long,
        val poolMisses: Long,
    ) {
        fun summary(): String {
            val poolTotal = poolHits + poolMisses
            val poolS = if (poolTotal > 0) "$poolHits/$poolTotal" else "n/a"
            return "total=$connectionsTotal active=$connectionsActive ws=$connectionsWs " +
                "tcp_fb=$connectionsTcpFallback bad=$connectionsBad err=$wsErrors " +
                "pool=$poolS up=${humanBytes(bytesUp)} down=${humanBytes(bytesDown)}"
        }
    }
}
