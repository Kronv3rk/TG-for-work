package com.flowseal.tgwsproxy

import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyCommandQueueTest {
    @Test
    fun operationsWaitForWorkerAndResultsWaitForDelivery() {
        val work = ArrayDeque<Runnable>()
        val ui = ArrayDeque<Runnable>()
        val events = mutableListOf<String>()
        val queue = ProxyCommandQueue(Executor { work.add(it) }, { ui.add(it) })
        queue.submit({ events += "start" }, {}, { events += "started" })
        queue.submit({ events += "stop" }, {}, { events += "stopped" })
        assertTrue(events.isEmpty())
        work.removeFirst().run()
        work.removeFirst().run()
        assertEquals(listOf("start", "stop"), events)
        ui.removeFirst().run()
        ui.removeFirst().run()
        assertEquals(listOf("start", "stop", "started", "stopped"), events)
    }

    @Test
    fun failureClosesPartiallyStartedRuntimeBeforeReportingError() {
        val events = mutableListOf<String>()
        val queue = ProxyCommandQueue(Executor { it.run() }, { it.run() })
        queue.submit({
            events += "socket opened"
            error("startup failure")
        }, { events += "socket closed" }, {
            assertTrue(it.isFailure)
            events += "failure"
        })
        assertEquals(listOf("socket opened", "socket closed", "failure"), events)
    }
}
