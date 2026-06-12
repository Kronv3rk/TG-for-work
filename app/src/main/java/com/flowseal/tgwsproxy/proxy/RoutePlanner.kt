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
    private val lastStatus = AtomicReference("Маршрут ещё не выбран")

    fun methodsFor(dcId: Int, isMedia: Boolean, options: ProxyOptions): List<RouteMethod> {
        val now = System.currentTimeMillis()
        clearExpiredCooldowns(now)

        val base = baseOrder(isMedia, options)
        val available = base.filterNot { method ->
            val until = cooldownUntil[RouteKey(method, dcId, isMedia)] ?: 0L
            until > now
        }

        return available.ifEmpty { base }
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

    fun markConnected(method: RouteMethod, dcId: Int, isMedia: Boolean) {
        val key = RouteKey(method, dcId, isMedia)
        cooldownUntil.remove(key)
        lastFailureAt.remove(key)
        lastStatus.set("${method.label} активен: ${targetLabel(dcId, isMedia)}")
    }

    fun markFailed(method: RouteMethod, dcId: Int, isMedia: Boolean) {
        val now = System.currentTimeMillis()
        val key = RouteKey(method, dcId, isMedia)
        cooldownUntil[key] = now + method.cooldownMs
        lastFailureAt[key] = now
        lastStatus.set("${method.label} не ответил, пробуем следующий маршрут")
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

    private companion object {
        const val NORMAL_CONNECT_MS = 8_000
        const val PROBATION_CONNECT_MS = 3_000
        const val PROBATION_WINDOW_MS = 120_000L
    }
}
