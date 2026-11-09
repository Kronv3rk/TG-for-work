package com.flowseal.tgwsproxy.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ProxySessionBudgetTest {
    @Test
    fun leavesWorkerCapacityForBidirectionalStreamsAndMaintenance() {
        val budget = ProxySessionBudget(16)
        assertEquals(7, budget.maxSessions)
        val leases = (1..budget.maxSessions).map { budget.tryAcquire()!! }
        assertNull(budget.tryAcquire())
        leases.forEach { it.close() }
    }

    @Test
    fun refusesNewClientsWhenFullAndRecoversAfterDisconnect() {
        val budget = ProxySessionBudget(16)
        val leases = (1..budget.maxSessions).map { budget.tryAcquire()!! }
        assertNull(budget.tryAcquire())
        leases.first().close()
        assertNotNull(budget.tryAcquire())
        leases.drop(1).forEach { it.close() }
    }

    @Test
    fun closingTheSameSessionTwiceDoesNotCreateExtraCapacity() {
        val budget = ProxySessionBudget(4)
        val first = budget.tryAcquire()!!
        first.close()
        first.close()
        val replacement = budget.tryAcquire()!!
        assertNull(budget.tryAcquire())
        replacement.close()
    }

    @Test
    fun rejectsWorkerCountsThatCannotSupportBidirectionalTransfer() {
        assertThrows(IllegalArgumentException::class.java) { ProxySessionBudget(3) }
    }
}
