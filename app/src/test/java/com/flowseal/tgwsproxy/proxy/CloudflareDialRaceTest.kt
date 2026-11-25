package com.flowseal.tgwsproxy.proxy

import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class CloudflareDialRaceTest {
    private class Connection : Closeable {
        val closed = AtomicBoolean(false)
        override fun close() { closed.set(true) }
    }

    @Test fun fastCandidateWinsAndLateConnectionIsClosed() {
        val executor = Executors.newFixedThreadPool(2)
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val slow = Connection()
        val fast = Connection()
        try {
            val result = CloudflareDialRace(executor).first(listOf(
                { started.countDown(); release.await(2, TimeUnit.SECONDS); slow },
                { started.await(1, TimeUnit.SECONDS); fast }
            ), 500)
            assertSame(fast, result)
            assertFalse(fast.closed.get())
        } finally {
            release.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
        }
        assertTrue(slow.closed.get())
    }

    @Test fun lateResultAfterDeadlineIsClosed() {
        val executor = Executors.newSingleThreadExecutor()
        val release = CountDownLatch(1)
        val late = Connection()
        try {
            assertNull(CloudflareDialRace(executor).first(listOf(
                { release.await(2, TimeUnit.SECONDS); late }
            ), 100))
        } finally {
            release.countDown()
            executor.shutdown()
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
        }
        assertTrue(late.closed.get())
    }

    @Test fun allFailedReturnsNull() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            assertNull(CloudflareDialRace(executor).first<Connection>(listOf(
                { null }, { null }
            ), 1000))
        } finally { executor.shutdownNow() }
    }

    @Test fun saturatedExecutorCanUseCallerForOneAttempt() {
        val executor = Executor { throw RejectedExecutionException() }
        val connection = Connection()
        assertSame(connection, CloudflareDialRace(executor).first(listOf({ connection }), 1000))
    }
    @Test fun secondBatchIsTriedWhenFirstThreeFail() {
        val executor = Executors.newFixedThreadPool(3)
        val connection = Connection()
        try {
            val candidates: List<() -> Connection?> = List(3) { { null } } + listOf({ connection })
            assertSame(connection, CloudflareDialRace(executor).firstInBatches(candidates, 1000))
        } finally { executor.shutdownNow() }
    }

    @Test fun candidateCapPreventsUnboundedDialing() {
        val executor = Executors.newFixedThreadPool(3)
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        try {
            val candidates: List<() -> Connection?> = List(20) { { attempts.incrementAndGet(); null } }
            assertNull(CloudflareDialRace(executor).firstInBatches(candidates, 1000))
            assertEquals(6, attempts.get())
        } finally { executor.shutdownNow() }
    }
}
