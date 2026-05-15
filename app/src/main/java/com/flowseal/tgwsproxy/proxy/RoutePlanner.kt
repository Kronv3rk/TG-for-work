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
        cooldownUntil.remove(RouteKey(method, dcId, isMedia))
        lastStatus.set("${method.label} активен: ${targetLabel(dcId, isMedia)}")
    }

    fun markFailed(method: RouteMethod, dcId: Int, isMedia: Boolean) {
        cooldownUntil[RouteKey(method, dcId, isMedia)] =
            System.currentTimeMillis() + method.cooldownMs
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
    }

    private fun targetLabel(dcId: Int, isMedia: Boolean): String {
        return "DC$dcId${if (isMedia) " media" else ""}"
    }

    private data class RouteKey(
        val method: RouteMethod,
        val dcId: Int,
        val isMedia: Boolean
    )
}
