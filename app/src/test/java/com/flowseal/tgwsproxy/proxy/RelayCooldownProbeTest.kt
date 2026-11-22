package com.flowseal.tgwsproxy.proxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the proposed background relay recovery check.
 * These are intentionally RED until RoutePlanner supports an injected clock
 * and explicit probe completion. They must not be merged as passing tests.
 */
class RelayCooldownProbeTest {
    private var nowMs = 1_000L
    private val planner = RoutePlanner(clockMs = { nowMs })
    private val relay = "149.154.167.220"

    @Test
    fun expiredCooldownDoesNotMakeClientsProbeBlockedRelay() {
        planner.markTargetTimedOut(relay)
        nowMs += 3_600_001L
        assertTrue(planner.isTargetBlocked(relay))
        assertTrue(planner.isBackgroundProbeDue(relay))
    }

    @Test
    fun successfulBackgroundProbeRestoresRelay() {
        planner.markTargetTimedOut(relay)
        nowMs += 3_600_001L
        planner.completeBackgroundProbe(relay, reachable = true)
        assertFalse(planner.isTargetBlocked(relay))
    }

    @Test
    fun failedBackgroundProbeRestartsCooldown() {
        planner.markTargetTimedOut(relay)
        nowMs += 3_600_001L
        planner.completeBackgroundProbe(relay, reachable = false)
        assertTrue(planner.isTargetBlocked(relay))
        assertFalse(planner.isBackgroundProbeDue(relay))
    }

    @Test
    fun networkChangeDiscardsOldProbeState() {
        planner.markTargetTimedOut(relay)
        nowMs += 3_600_001L
        planner.resetNetworkState()
        assertFalse(planner.isTargetBlocked(relay))
        assertFalse(planner.isBackgroundProbeDue(relay))
    }
}
