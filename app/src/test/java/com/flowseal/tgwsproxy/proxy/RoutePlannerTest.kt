package com.flowseal.tgwsproxy.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutePlannerTest {
    private fun options(
        cf: Boolean = true,
        cfPriority: Boolean = true,
        cellular: Boolean = false
    ) = ProxyOptions(
        secretHex = "00112233445566778899aabbccddeeff",
        cfProxyEnabled = cf,
        cfProxyPriority = cfPriority,
        cellularNetwork = cellular
    )

    @Test
    fun freshRouteGetsFullConnectBudget() {
        val planner = RoutePlanner()
        assertEquals(8_000, planner.connectTimeoutMs(RouteMethod.WEBSOCKET, 2, false))
    }

    @Test
    fun recentlyFailedRouteIsPutOnProbation() {
        val planner = RoutePlanner()
        planner.markFailed(RouteMethod.WEBSOCKET, 2, false)
        assertEquals(3_000, planner.connectTimeoutMs(RouteMethod.WEBSOCKET, 2, false))
    }

    @Test
    fun successClearsProbation() {
        val planner = RoutePlanner()
        planner.markFailed(RouteMethod.WEBSOCKET, 2, false)
        planner.markConnected(RouteMethod.WEBSOCKET, 2, false)
        assertEquals(8_000, planner.connectTimeoutMs(RouteMethod.WEBSOCKET, 2, false))
    }

    @Test
    fun probationIsScopedToRouteAndTarget() {
        val planner = RoutePlanner()
        planner.markFailed(RouteMethod.WEBSOCKET, 2, false)
        assertEquals(8_000, planner.connectTimeoutMs(RouteMethod.WEBSOCKET, 4, false))
        assertEquals(8_000, planner.connectTimeoutMs(RouteMethod.CLOUDFLARE, 2, false))
        assertEquals(8_000, planner.connectTimeoutMs(RouteMethod.WEBSOCKET, 2, true))
    }

    @Test
    fun failedRouteIsSkippedWhileOnCooldown() {
        val planner = RoutePlanner()
        planner.markFailed(RouteMethod.WEBSOCKET, 2, false)
        val methods = planner.methodsFor(2, false, options())
        assertFalse(methods.contains(RouteMethod.WEBSOCKET))
        assertTrue(methods.isNotEmpty())
    }

    @Test
    fun allCooledDownStillReturnsFullOrder() {
        val planner = RoutePlanner()
        for (method in RouteMethod.values()) planner.markFailed(method, 2, false)
        val methods = planner.methodsFor(2, false, options())
        assertTrue(methods.isNotEmpty())
    }

    @Test
    fun mediaPrioritisesCloudflareWhenEnabled() {
        val planner = RoutePlanner()
        val methods = planner.methodsFor(2, true, options(cf = true, cfPriority = true))
        assertEquals(RouteMethod.CLOUDFLARE, methods.first())
    }

    @Test
    fun cellularPrioritisesCloudflareWhenEnabled() {
        val planner = RoutePlanner()
        val methods = planner.methodsFor(2, false, options(cf = true, cellular = true))
        assertEquals(RouteMethod.CLOUDFLARE, methods.first())
    }

    @Test
    fun plainWifiStartsWithWebSocket() {
        val planner = RoutePlanner()
        val methods = planner.methodsFor(2, false, options(cf = false))
        assertEquals(RouteMethod.WEBSOCKET, methods.first())
    }
}
