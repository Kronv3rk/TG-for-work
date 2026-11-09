package com.flowseal.tgwsproxy.proxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeTlsReplayCacheTest {
    @Test
    fun acceptsAuthenticatedHelloOnceWithinReplayWindow() {
        var now = 1_000L
        val cache = FakeTlsReplayCache(windowMs = 241_000L, maxEntries = 4, clock = { now })
        val hello = ByteArray(32) { it.toByte() }
        assertTrue(cache.firstSeen(hello))
        assertFalse(cache.firstSeen(hello.copyOf()))
        now += 240_999L
        assertFalse(cache.firstSeen(hello))
        now += 1L
        assertTrue(cache.firstSeen(hello))
    }

    @Test
    fun differentClientRandomValuesCanConnectConcurrently() {
        val cache = FakeTlsReplayCache(windowMs = 1_000L, maxEntries = 4, clock = { 100L })
        assertTrue(cache.firstSeen(ByteArray(32) { 1 }))
        assertTrue(cache.firstSeen(ByteArray(32) { 2 }))
        assertFalse(cache.firstSeen(ByteArray(32) { 1 }))
    }

    @Test
    fun capacityBoundPreventsUnboundedMemoryUsage() {
        val cache = FakeTlsReplayCache(windowMs = 100_000L, maxEntries = 2, clock = { 100L })
        val first = ByteArray(32) { 1 }
        val second = ByteArray(32) { 2 }
        val third = ByteArray(32) { 3 }
        assertTrue(cache.firstSeen(first))
        assertTrue(cache.firstSeen(second))
        assertTrue(cache.firstSeen(third))
        assertFalse(cache.firstSeen(third))
        // The oldest value is evicted, but recent ones remain protected.
        assertFalse(cache.firstSeen(second))
        assertTrue(cache.firstSeen(first))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonTlsClientRandomSizes() {
        FakeTlsReplayCache().firstSeen(ByteArray(31))
    }
}
