package com.flowseal.tgwsproxy.proxy

import org.junit.Assert.*
import org.junit.Test

class RouteAttemptSequenceTest {
    @Test fun closedClientStopsAfterFailedRelay() {
        var closed = false
        val tried = mutableListOf<Int>()
        val handled = RouteAttemptSequence.run(listOf(1, 2, 3), { closed }) {
            tried += it
            closed = true
            false
        }
        assertTrue(handled)
        assertEquals(listOf(1), tried)
    }

    @Test fun closedClientNeverDials() {
        assertTrue(RouteAttemptSequence.run(listOf(1), { true }) { fail("dial after close"); false })
    }

    @Test fun openClientCanTryNextRoute() {
        val tried = mutableListOf<Int>()
        assertTrue(RouteAttemptSequence.run(listOf(1, 2, 3), { false }) {
            tried += it
            it == 2
        })
        assertEquals(listOf(1, 2), tried)
    }
}
