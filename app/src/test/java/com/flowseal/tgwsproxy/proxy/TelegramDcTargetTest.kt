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
}
