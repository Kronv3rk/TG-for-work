package com.flowseal.tgwsproxy.proxy

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

enum class RouteMethod(
    val label: String,
    val title: String,
    val cooldownMs: Long
) {
    WEBSOCKET("WS", "WebSocket", 45_000L),
    CLOUDFLARE("CF", "Cloudflare", 30_000L),
    TCP("TCP", "TCP", 45_000L)
}

class RoutePlanner {
    private val cooldownUntil = ConcurrentHashMap<RouteKey, Long>()
    private val lastFailureAt = ConcurrentHashMap<RouteKey, Long>()
    private val health = ConcurrentHashMap<RouteKey, RouteHealth>()
    private val lastStatus = AtomicReference("Маршрут ещё не выбран")

    fun methodsFor(dcId: Int, isMedia: Boolean, options: ProxyOptions): List<RouteMethod> {
        val now = System.currentTimeMillis()
        clearExpiredCooldowns(now)

        val base = baseOrder(isMedia, options)
        val available = base.filterNot { method ->
            val until = cooldownUntil[RouteKey(method, dcId, isMedia)] ?: 0L
            until > now
        }

        // Rank by observed stability/quality: the most reliable and fastest route
        // is tried first, then the second best, and so on. Unproven routes keep a
        // neutral prior, so the stable sort preserves the network-policy base
        // order between them until real measurements arrive.
        val candidates = available.ifEmpty { base }
        return candidates.sortedByDescending { method ->
            health[RouteKey(method, dcId, isMedia)]?.score() ?: RouteHealth.NEUTRAL_SCORE
        }
    }

    /**
     * Adaptive connect budget. A route that has not failed recently gets the full
     * timeout; a route that failed within [PROBATION_WINDOW_MS] is put on probation
     * and only gets a short timeout, so a flaky path is abandoned quickly and the
     * next route (which often carries the stuck media) is reached without a long wait.
     *
     * This mirrors the adaptive 2s/10s timeout used by the upstream tg-ws-proxy.
     */
    fun connectTimeoutMs(method: RouteMethod, dcId: Int, isMedia: Boolean): Int {
        val failedAt = lastFailureAt[RouteKey(method, dcId, isMedia)] ?: return NORMAL_CONNECT_MS
        val sinceFailure = System.currentTimeMillis() - failedAt
        return if (sinceFailure in 0 until PROBATION_WINDOW_MS) PROBATION_CONNECT_MS else NORMAL_CONNECT_MS
    }

    fun policyLabel(options: ProxyOptions): String {
        return when {
            options.cellularNetwork && options.cfProxyEnabled -> "Авто: мобильная сеть, CF > WS > TCP"
            options.cellularNetwork -> "Авто: мобильная сеть, WS > TCP"
            options.cfProxyEnabled -> "Авто: WS, CF для медиа, TCP запасной"
            else -> "Авто: WS > TCP"
        }
    }

    fun status(): String = lastStatus.get()

    fun markTrying(method: RouteMethod, dcId: Int, isMedia: Boolean) {
        lastStatus.set("Пробуем ${method.title}: ${targetLabel(dcId, isMedia)}")
    }

    fun markConnected(method: RouteMethod, dcId: Int, isMedia: Boolean, connectMs: Long = -1L) {
        val key = RouteKey(method, dcId, isMedia)
        cooldownUntil.remove(key)
        lastFailureAt.remove(key)
        health.getOrPut(key, ::RouteHealth).recordSuccess(connectMs)
        lastStatus.set("${method.label} активен: ${targetLabel(dcId, isMedia)}")
    }

    fun markFailed(method: RouteMethod, dcId: Int, isMedia: Boolean) {
        val now = System.currentTimeMillis()
        val key = RouteKey(method, dcId, isMedia)
        cooldownUntil[key] = now + method.cooldownMs
        lastFailureAt[key] = now
        health.getOrPut(key, ::RouteHealth).recordFailure()
        lastStatus.set("${method.label} не ответил, пробуем следующий маршрут")
    }

    /**
     * Compact per-route quality summary aggregated over all targets, e.g.
     * "WS 92% 0.4с · CF 60% 1.2с · TCP —". Routes without measurements show "—".
     */
    fun healthSummary(): String {
        return RouteMethod.values().joinToString(" · ") { method ->
            val routeStats = health.entries.filter { it.key.method == method }.map { it.value }
            if (routeStats.isEmpty() || routeStats.all { it.samples == 0 }) {
                "${method.label} —"
            } else {
                val successPct = (routeStats.map { it.successEwma }.average() * 100).toInt()
                val measured = routeStats.filter { it.latencyEwmaMs > 0 }
                val latency = if (measured.isEmpty()) {
                    ""
                } else {
                    String.format(java.util.Locale.US, " %.1fс", measured.map { it.latencyEwmaMs }.average() / 1000.0)
                }
                "${method.label} $successPct%$latency"
            }
        }
    }

    private fun baseOrder(isMedia: Boolean, options: ProxyOptions): List<RouteMethod> {
        return when {
            options.cellularNetwork && options.cfProxyEnabled -> listOf(
                RouteMethod.CLOUDFLARE,
                RouteMethod.WEBSOCKET,
                RouteMethod.TCP
            )
            options.cellularNetwork -> listOf(RouteMethod.WEBSOCKET, RouteMethod.TCP)
            options.cfProxyEnabled && isMedia && options.cfProxyPriority -> listOf(
                RouteMethod.CLOUDFLARE,
                RouteMethod.WEBSOCKET,
                RouteMethod.TCP
            )
            options.cfProxyEnabled && options.cfProxyPriority -> listOf(
                RouteMethod.WEBSOCKET,
                RouteMethod.CLOUDFLARE,
                RouteMethod.TCP
            )
            options.cfProxyEnabled -> listOf(
                RouteMethod.WEBSOCKET,
                RouteMethod.TCP,
                RouteMethod.CLOUDFLARE
            )
            else -> listOf(RouteMethod.WEBSOCKET, RouteMethod.TCP)
        }
    }

    private fun clearExpiredCooldowns(now: Long) {
        for (entry in cooldownUntil.entries) {
            if (entry.value <= now) {
                cooldownUntil.remove(entry.key, entry.value)
            }
        }
        for (entry in lastFailureAt.entries) {
            if (now - entry.value > PROBATION_WINDOW_MS) {
                lastFailureAt.remove(entry.key, entry.value)
            }
        }
    }

    private fun targetLabel(dcId: Int, isMedia: Boolean): String {
        return "DC$dcId${if (isMedia) " media" else ""}"
    }

    private data class RouteKey(
        val method: RouteMethod,
        val dcId: Int,
        val isMedia: Boolean
    )

    /**
     * Exponentially weighted stability/quality measurement for one route+target.
     * Success rate dominates the score; connect latency acts as a small penalty,
     * so two equally reliable routes are ordered by speed.
     */
    private class RouteHealth {
        @Volatile
        var successEwma: Double = NEUTRAL_SCORE

        @Volatile
        var latencyEwmaMs: Double = 0.0

        @Volatile
        var samples: Int = 0

        @Synchronized
        fun recordSuccess(connectMs: Long) {
            successEwma = successEwma * (1 - ALPHA) + ALPHA
            if (connectMs >= 0) {
                latencyEwmaMs = if (latencyEwmaMs == 0.0) {
                    connectMs.toDouble()
                } else {
                    latencyEwmaMs * (1 - ALPHA) + ALPHA * connectMs
                }
            }
            samples++
        }

        @Synchronized
        fun recordFailure() {
            successEwma *= (1 - ALPHA)
            samples++
        }

        fun score(): Double = successEwma - latencyEwmaMs / LATENCY_PENALTY_DIVISOR

        companion object {
            const val NEUTRAL_SCORE = 0.5
            private const val ALPHA = 0.3
            private const val LATENCY_PENALTY_DIVISOR = 40_000.0
        }
    }

    private companion object {
        const val NORMAL_CONNECT_MS = 8_000
        const val PROBATION_CONNECT_MS = 3_000
        const val PROBATION_WINDOW_MS = 120_000L
    }
}
