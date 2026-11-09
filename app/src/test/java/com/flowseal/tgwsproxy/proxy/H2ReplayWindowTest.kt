package com.flowseal.tgwsproxy.proxy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class H2ReplayWindowTest {
    @Test
    fun retainsOnlyEncryptedSmallPacketsForTheSameAuthKey() {
        val window = H2ReplayWindow(clock = { 1_000L })
        assertNull(window.remember(ByteArray(24)))
        val oversized = ByteArray(65_537).also { it[0] = 1 }
        assertNull(window.remember(oversized))

        val first = packet(1)
        val entry = window.remember(first)!!
        assertSame(entry, window.remember(first.copyOf()))

        val otherKey = window.remember(packet(2))
        assertTrue(otherKey != null)
        assertNull(window.findForTest(entry))
        assertEquals(1, window.retainedPackets)
    }

    @Test
    fun replaysOnlyAfterSentRequestHasBeenWaitingThreeSeconds() {
        var now = 10_000L
        val window = H2ReplayWindow(clock = { now })
        val entry = window.remember(packet(1))!!
        val original = window.registerOriginal(entry)
        assertNull(window.nextReplayCandidate())
        window.markSent(original)
        now += 2_999L
        assertNull(window.nextReplayCandidate())
        now += 1L
        assertSame(entry, window.nextReplayCandidate())
        val replay = window.reserveReplay(entry)!!
        assertNull(window.nextReplayCandidate())
        window.markSent(replay)
        window.complete(replay)
        now += 2_000L
        assertSame(entry, window.nextReplayCandidate())
    }

    @Test
    fun doesNotReplayWhileResponseBodyIsBeingReceived() {
        var now = 1_000L
        val window = H2ReplayWindow(clock = { now })
        val entry = window.remember(packet(1))!!
        val original = window.registerOriginal(entry)
        val secondOriginal = window.registerOriginal(entry)
        window.markSent(original)
        window.markSent(secondOriginal)
        now += 3_000L
        window.markReceiving(original)
        assertNull(window.nextReplayCandidate())
        window.complete(original)
        assertSame(entry, window.nextReplayCandidate())
    }

    @Test
    fun permitsAtMostTwoConcurrentReplaysAcrossChannel() {
        var now = 1_000L
        val window = H2ReplayWindow(clock = { now })
        val entries = (1..3).map { value ->
            val entry = window.remember(packet(value, authKey = 9))!!
            val original = window.registerOriginal(entry)
            window.markSent(original)
            entry
        }
        now += 3_000L
        val first = window.reserveReplay(entries[0])!!
        val second = window.reserveReplay(entries[1])!!
        assertNull(window.reserveReplay(entries[2]))
        window.complete(first)
        val third = window.reserveReplay(entries[2])
        assertTrue(third != null)
        window.complete(second)
        window.complete(third!!)
    }

    @Test
    fun stopsAfterThreeAttemptsAndExpiresHistoryAfterThirtySeconds() {
        var now = 5_000L
        val window = H2ReplayWindow(clock = { now })
        val entry = window.remember(packet(1))!!
        val original = window.registerOriginal(entry)
        window.markSent(original)
        repeat(3) {
            now += if (it == 0) 3_000L else 2_000L
            val replay = window.reserveReplay(entry)
            assertTrue(replay != null)
            window.markSent(replay!!)
            window.complete(replay)
        }
        now += 2_000L
        assertNull(window.nextReplayCandidate())
        now = 40_001L
        assertNull(window.nextReplayCandidate())
        assertEquals(0, window.retainedPackets)
    }

    @Test
    fun suppressesDuplicateRepliesButAllowsDifferentReplyToResetRetryBudget() {
        var now = 1_000L
        val window = H2ReplayWindow(clock = { now })
        val entry = window.remember(packet(1))!!
        val original = window.registerOriginal(entry)
        window.markSent(original)
        now += 3_000L
        val replay = window.reserveReplay(entry)!!
        val firstReply = ByteArray(24) { 7 }
        assertTrue(window.acceptReply(original, firstReply))
        assertFalse(window.acceptReply(replay, firstReply.copyOf()))
        assertTrue(window.acceptReply(replay, ByteArray(24) { 8 }))
        window.complete(original)
        window.complete(replay)
        assertEquals(0, window.attemptsForTest(entry))
    }

    @Test
    fun capsHistoryAtSixteenPacketsAndSixtyFourKilobytes() {
        val window = H2ReplayWindow(clock = { 1_000L })
        repeat(20) { value -> window.remember(packet(value + 1, size = 4_096)) }
        assertTrue(window.retainedPackets <= 16)
        assertTrue(window.retainedBytes <= 65_536)
    }

    @Test
    fun cancellingUnsentReplayRestoresItsRetryBudget() {
        var now = 1_000L
        val window = H2ReplayWindow(clock = { now })
        val entry = window.remember(packet(1))!!
        val original = window.registerOriginal(entry)
        window.markSent(original)
        now += 3_000L
        val replay = window.reserveReplay(entry)!!
        assertEquals(1, window.attemptsForTest(entry))
        window.cancelReplay(replay)
        assertEquals(0, window.attemptsForTest(entry))
        assertSame(entry, window.nextReplayCandidate())
    }

    @Test
    fun authKeyRotationDoesNotForgetAlreadyReservedReplaySlots() {
        var now = 1_000L
        val window = H2ReplayWindow(clock = { now }, maxConcurrentReplays = 1)
        val oldEntry = window.remember(packet(1, authKey = 1))!!
        val oldOriginal = window.registerOriginal(oldEntry)
        window.markSent(oldOriginal)
        now += 3_000L
        val oldReplay = window.reserveReplay(oldEntry)!!

        val newEntry = window.remember(packet(2, authKey = 2))!!
        val newOriginal = window.registerOriginal(newEntry)
        window.markSent(newOriginal)
        now += 3_000L
        assertNull(window.reserveReplay(newEntry))
        window.complete(oldReplay)
        assertTrue(window.reserveReplay(newEntry) != null)
    }

    @Test
    fun retriesTheLatestPacketAfterAnEmptyLongPollResponse() {
        var now = 1_000L
        val window = H2ReplayWindow(clock = { now })
        val entry = window.remember(packet(1))!!
        val original = window.registerOriginal(entry)
        window.markSent(original)
        window.markReceiving(original)
        window.complete(original) // HTTP 200 with an empty body
        now += 999L
        assertNull(window.nextReplayCandidate())
        now += 1L
        assertSame(entry, window.nextReplayCandidate())
    }

    @Test
    fun untrackedReplyProgressDelaysIdleReplayOfThePreviousTrackedPacket() {
        var now = 1_000L
        val window = H2ReplayWindow(clock = { now })
        val entry = window.remember(packet(1))!!
        val original = window.registerOriginal(entry)
        window.markSent(original)
        window.markReceiving(original)
        window.complete(original)

        now += 900L
        assertNull(window.nextReplayCandidate())
        window.acceptReply(null, ByteArray(24) { 4 })
        now += 999L
        assertNull(window.nextReplayCandidate())
        now += 1L
        assertSame(entry, window.nextReplayCandidate())
    }

    @Test
    fun latestIdlePollWaitsWhileUntrackedRequestIsInFlight() {
        var now = 1_000L
        val window = H2ReplayWindow(clock = { now })
        val entry = window.remember(packet(1))!!
        val original = window.registerOriginal(entry)
        window.markSent(original)
        window.markReceiving(original)
        window.complete(original)
        now += 1_000L

        assertNull(window.nextReplayCandidate(allowLatestIdlePoll = false))
        assertSame(entry, window.nextReplayCandidate(allowLatestIdlePoll = true))
    }

    private fun packet(key: Int, size: Int = 32, authKey: Int = key): ByteArray =
        ByteArray(size).also {
            it[0] = authKey.toByte()
            for (index in 8 until it.size) it[index] = (index + key).toByte()
        }
}
