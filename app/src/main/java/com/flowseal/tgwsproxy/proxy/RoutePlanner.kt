package com.flowseal.tgwsproxy.proxy

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

enum class RouteMethod(
    val label: String,
    val title: String,
    val cooldownMs: Long
) {
    WORKER("CFW", "CF Worker", 30_000L),
    WEBSOCKET("WS", "WebSocket", 45_000L),
    // One attempt, then a long rest: a fronting SNI that is not whitelisted fails
    // every time, and retrying it would only delay the next route (upstream: 30 min).
    FRONTING("FR", "Fronting", 1_800_000L),
    CLOUDFLARE("CF", "Cloudflare", 30_000L),
    TCP("TCP", "TCP", 45_000L)
}

class RoutePlanner {
    private val cooldownUntil = ConcurrentHashMap<RouteKey, Long>()
    private val lastFailureAt = ConcurrentHashMap<RouteKey, Long>()
    private val health = ConcurrentHashMap<RouteKey, RouteHealth>()
    private val targetBlockedUntil = ConcurrentHashMap<String, Long>()
    private val lastStatus = AtomicReference("Маршрут ещё не выбран")

    /**
     * Routes to try for this DC, best first. [wsTarget] is the relay address the
     * WebSocket and fronting routes would dial; when a connect to it recently timed
     * out (see [markTargetTimedOut]) both are dropped so the client goes straight to
     * Cloudflare or the Worker instead of waiting out another timeout.
     */
    fun methodsFor(dcId: Int, isMedia: Boolean, options: ProxyOptions, wsTarget: String? = null): List<RouteMethod> {
        val now = System.currentTimeMillis()
        clearExpiredCooldowns(now)

        val base = baseOrder(isMedia, options).let { order ->
            if (wsTarget != null && isTargetBlocked(wsTarget, now) && hasRelayFallback(options)) {
                order.filterNot { it.dialsWsTarget }
            } else {
                order
            }
        }
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

    /**
     * A WebSocket connect to [target] timed out. A timeout (not a refusal) is what
     * address-level blocking looks like, and it hits every DC behind that relay, so
     * the address is skipped for [TARGET_TIMEOUT_COOLDOWN_MS] (upstream ip_fail_until).
     * The direct TCP route dials different addresses and is not affected.
     */
    fun markTargetTimedOut(target: String) {
        targetBlockedUntil[target] = System.currentTimeMillis() + TARGET_TIMEOUT_COOLDOWN_MS
    }

    fun isTargetBlocked(target: String, now: Long = System.currentTimeMillis()): Boolean =
        (targetBlockedUntil[target] ?: 0L) > now

    /** A different network may not block the same addresses, so start over. */
    fun resetTargetCooldowns() {
        targetBlockedUntil.clear()
    }

    fun policyLabel(options: ProxyOptions): String {
        val core = when {
            options.cellularNetwork && options.cfProxyEnabled -> "Авто: мобильная сеть, CF > WS > TCP"
            options.cellularNetwork -> "Авто: мобильная сеть, WS > TCP"
            options.cfProxyEnabled -> "Авто: WS, CF для медиа, TCP запасной"
            else -> "Авто: WS > TCP"
        }
        val withFronting = if (options.frontingEnabled) "$core, fronting" else core
        return if (options.cfWorkerEnabled) "Свой CF Worker → $withFronting" else withFronting
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
        val shown = RouteMethod.values().filter { method ->
            // Fronting is opt-in; only list it once it has been tried.
            method != RouteMethod.FRONTING || health.keys.any { it.method == method }
        }
        return shown.joinToString(" · ") { method ->
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
        val core = when {
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
        // The user's own Cloudflare Worker is a private, blocking-resistant relay,
        // so try it first when configured; health ranking may reorder later if it
        // proves slower or starts failing.
        // Fronting reaches the same relay as WebSocket under a different SNI, so it
        // is the natural next step when plain WebSocket is filtered by name.
        val withFronting = if (options.frontingEnabled) {
            core.flatMap { if (it == RouteMethod.WEBSOCKET) listOf(it, RouteMethod.FRONTING) else listOf(it) }
        } else {
            core
        }
        return if (options.cfWorkerEnabled) listOf(RouteMethod.WORKER) + withFronting else withFronting
    }

    private fun hasRelayFallback(options: ProxyOptions): Boolean =
        options.cfProxyEnabled || options.cfWorkerEnabled

    private val RouteMethod.dialsWsTarget: Boolean
        get() = this == RouteMethod.WEBSOCKET || this == RouteMethod.FRONTING

    private fun clearExpiredCooldowns(now: Long) {
        for (entry in cooldownUntil.entries) {
            if (entry.value <= now) {
                cooldownUntil.remove(entry.key, entry.value)
            }
        }
        for (entry in targetBlockedUntil.entries) {
            if (entry.value <= now) {
                targetBlockedUntil.remove(entry.key, entry.value)
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
        const val TARGET_TIMEOUT_COOLDOWN_MS = 3_600_000L
    }
}
