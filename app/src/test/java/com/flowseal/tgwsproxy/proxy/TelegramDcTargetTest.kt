package com.flowseal.tgwsproxy.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wire-level test DC addresses and +10000 tagging match upstream tg_ws_proxy.py. */
class TelegramDcTargetTest {
    @Test
    fun detectsAndStripsTestDcMarkerWithoutChangingMediaSign() {
        assertEquals(TelegramDcTarget(2, false), TelegramDcTarget.resolve(2, false))
        assertEquals(TelegramDcTarget(2, true), TelegramDcTarget.resolve(10_002, false))
        assertEquals(TelegramDcTarget(3, true), TelegramDcTarget.resolve(10_003, false))
        assertEquals(TelegramDcTarget(2, true), TelegramDcTarget.resolve(2, true))
    }

    @Test
    fun routesTestDcToTheUpstreamTestIpTable() {
        assertEquals("149.154.175.10", TelegramDcTarget(1, true).directIp(emptyMap()))
        assertEquals("149.154.167.40", TelegramDcTarget(2, true).directIp(emptyMap()))
        assertEquals("149.154.175.117", TelegramDcTarget(3, true).directIp(emptyMap()))
        assertNull(TelegramDcTarget(4, true).directIp(emptyMap()))
        assertEquals("149.154.167.51", TelegramDcTarget(2, false).directIp(emptyMap()))
    }

    @Test
    fun testDcUsesDedicatedWebSocketPathAndNoCloudflareProxyRoute() {
        assertEquals("/apiws_test", TelegramDcTarget(2, true).webSocketPath)
        assertEquals("/apiws", TelegramDcTarget(2, false).webSocketPath)
        val planner = RoutePlanner()
        val options = ProxyOptions(secretHex = "abc", cfProxyEnabled = true, cfH2MediaEnabled = true)
        val methods = planner.methodsFor(2, true, options, isTestDc = true)
        assertFalse(methods.contains(RouteMethod.CLOUDFLARE))
        assertFalse(methods.contains(RouteMethod.HTTP2))
        assertTrue(methods.contains(RouteMethod.TCP))
        assertTrue(planner.methodsFor(2, true, options).contains(RouteMethod.CLOUDFLARE))
    }

    @Test
    fun productionOverridesDoNotRewriteTestEnvironmentDefaultIps() {
        val override = mapOf(2 to "149.154.167.220")
        assertEquals("149.154.167.220", TelegramDcTarget(2, false).directIp(override))
        assertEquals("149.154.167.40", TelegramDcTarget(2, true).directIp(override))
    }
    @Test
    fun forcedTestModeDoesNotLeakProductionOverridesOrCloudflareRoutes() {
        val options = ProxyOptions(
            secretHex = "abc",
            forceTestDc = true,
            dcIpOverrides = "2:192.0.2.2",
            cfProxyEnabled = true,
            cfH2MediaEnabled = true,
            cfWorkerDomain = "example.workers.dev"
        )
        val target = TelegramDcTarget.resolve(2, options.forceTestDc)
        assertTrue(target.isTest)
        assertEquals("/apiws_test", target.webSocketPath)
        assertEquals("149.154.167.40", target.directIp(options.dcIpMap))
        val methods = RoutePlanner().methodsFor(
            target.id, true, options, isTestDc = target.isTest
        )
        assertFalse(methods.contains(RouteMethod.CLOUDFLARE))
        assertFalse(methods.contains(RouteMethod.HTTP2))
        assertTrue(methods.contains(RouteMethod.TCP))
    }

    @Test
    fun disablingForcedTestModeRestoresProductionRoutesWithoutChangingSettings() {
        val options = ProxyOptions(secretHex = "abc", cfProxyEnabled = true, cfH2MediaEnabled = true)
        val production = TelegramDcTarget.resolve(2, options.forceTestDc)
        val test = TelegramDcTarget.resolve(2, true)
        assertFalse(production.isTest)
        assertEquals("/apiws", production.webSocketPath)
        assertEquals("149.154.167.51", production.directIp(emptyMap()))
        assertTrue(test.isTest)
        assertFalse(options.sameConnection(options.copy(forceTestDc = true)))
        val methods = RoutePlanner().methodsFor(
            production.id, true, options, isTestDc = production.isTest
        )
        assertTrue(methods.contains(RouteMethod.CLOUDFLARE))
    }

    @Test
    fun taggedTestDcRemainsTestWhenForceSettingIsOff() {
        val target = TelegramDcTarget.resolve(10_003, false)
        assertEquals(3, target.id)
        assertTrue(target.isTest)
        assertEquals("/apiws_test", target.webSocketPath)
        assertEquals("149.154.175.117", target.directIp(mapOf(3 to "192.0.2.3")))
    }

}
