package com.flowseal.tgwsproxy.proxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultNetworkTrackerTest {
    @Test
    fun firstNetworkIsTheBaselineNotAHandover() {
        val tracker = DefaultNetworkTracker()
        assertFalse(tracker.changed("wifi-one", cellular = false, wifi = true))
        assertFalse(tracker.changed("wifi-one", cellular = false, wifi = true))
    }

    @Test
    fun transportSwitchInvalidatesRoutes() {
        val tracker = DefaultNetworkTracker()
        tracker.changed("wifi-one", cellular = false, wifi = true)
        assertTrue(tracker.changed("mobile-one", cellular = true, wifi = false))
        assertFalse(tracker.changed("mobile-one", cellular = true, wifi = false))
    }

    @Test
    fun differentWifiNetworkInvalidatesOldRelayAddresses() {
        val tracker = DefaultNetworkTracker()
        tracker.changed("wifi-one", cellular = false, wifi = true)
        assertTrue(tracker.changed("wifi-two", cellular = false, wifi = true))
    }

    @Test
    fun transportChangeOnSameNetworkInvalidatesRoutes() {
        val tracker = DefaultNetworkTracker()
        tracker.changed("vpn-one", cellular = false, wifi = false)
        assertTrue(tracker.changed("vpn-one", cellular = true, wifi = false))
    }

    @Test
    fun losingCurrentNetworkInvalidatesItOnlyOnce() {
        val tracker = DefaultNetworkTracker()
        tracker.changed("wifi-one", cellular = false, wifi = true)
        assertTrue(tracker.lost("wifi-one"))
        assertFalse(tracker.lost("wifi-one"))
    }

    @Test
    fun staleLostCallbackDoesNotResetNewDefaultNetwork() {
        val tracker = DefaultNetworkTracker()
        tracker.changed("wifi-one", cellular = false, wifi = true)
        tracker.changed("mobile-one", cellular = true, wifi = false)
        assertFalse(tracker.lost("wifi-one"))
        assertFalse(tracker.changed("mobile-one", cellular = true, wifi = false))
    }

    @Test
    fun reconnectionAfterTotalNetworkLossResetsOldRouteState() {
        val tracker = DefaultNetworkTracker()
        tracker.changed("wifi-one", cellular = false, wifi = true)
        tracker.lost("wifi-one")
        assertTrue(tracker.changed("wifi-two", cellular = false, wifi = true))
    }
}
