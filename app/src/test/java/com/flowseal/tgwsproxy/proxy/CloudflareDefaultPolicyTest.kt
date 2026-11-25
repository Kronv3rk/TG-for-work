package com.flowseal.tgwsproxy.proxy

import org.junit.Assert.*
import org.junit.Test

class CloudflareDefaultPolicyTest {
    @Test fun newInstallUsesAutoButExplicitOptOutIsPreserved() {
        assertTrue(CloudflareTransportMode.autoFallbackEnabled(null, false))
        assertFalse(CloudflareTransportMode.autoFallbackEnabled(false, false))
        assertTrue(CloudflareTransportMode.autoFallbackEnabled(true, false))
        assertFalse(CloudflareTransportMode.autoFallbackEnabled(null, true))
        assertFalse(CloudflareTransportMode.autoFallbackEnabled(true, true))
    }

    @Test fun selectedTransportIsVisibleInRouteStatus() {
        val planner = RoutePlanner()
        planner.markConnected(RouteMethod.CLOUDFLARE, 2, false, 200, "CF без TLS")
        assertTrue(planner.status().startsWith("CF без TLS"))
        planner.markConnected(RouteMethod.CLOUDFLARE, 2, false, 200, "CF TLS")
        assertTrue(planner.status().startsWith("CF TLS"))
    }
}
