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

    @Test
    fun stableRouteIsPromotedAboveBaseOrder() {
        val planner = RoutePlanner()
        // Base order on plain Wi-Fi with CF priority: WS first. Make CF measurably
        // better: repeated fast successes, while WS keeps failing.
        repeat(3) { planner.markConnected(RouteMethod.CLOUDFLARE, 2, false, 300) }
        repeat(3) {
            planner.markFailed(RouteMethod.WEBSOCKET, 2, false)
            planner.markConnected(RouteMethod.WEBSOCKET, 2, false, 5_000)
        }
        val methods = planner.methodsFor(2, false, options(cf = true, cfPriority = true))
        assertEquals(RouteMethod.CLOUDFLARE, methods.first())
    }

    @Test
    fun unprovenRoutesKeepBaseOrder() {
        val planner = RoutePlanner()
        val methods = planner.methodsFor(2, true, options(cf = true, cfPriority = true))
        assertEquals(
            listOf(RouteMethod.CLOUDFLARE, RouteMethod.WEBSOCKET, RouteMethod.TCP),
            methods
        )
    }

    @Test
    fun equallyReliableRoutesOrderedByLatency() {
        val planner = RoutePlanner()
        repeat(3) { planner.markConnected(RouteMethod.WEBSOCKET, 2, false, 6_000) }
        repeat(3) { planner.markConnected(RouteMethod.TCP, 2, false, 200) }
        val methods = planner.methodsFor(2, false, options(cf = false))
        assertEquals(RouteMethod.TCP, methods.first())
    }

    @Test
    fun healthSummaryShowsDashForUnprovenRoutes() {
        val planner = RoutePlanner()
        val summary = planner.healthSummary()
        assertTrue(summary.contains("WS —"))
        assertTrue(summary.contains("CF —"))
        assertTrue(summary.contains("TCP —"))
    }

    @Test
    fun healthSummaryShowsPercentAfterMeasurements() {
        val planner = RoutePlanner()
        repeat(5) { planner.markConnected(RouteMethod.WEBSOCKET, 2, false, 400) }
        val summary = planner.healthSummary()
        assertTrue("expected WS percentage in: $summary", Regex("WS \\d+%").containsMatchIn(summary))
        assertTrue(summary.contains("CF —"))
    }
}
