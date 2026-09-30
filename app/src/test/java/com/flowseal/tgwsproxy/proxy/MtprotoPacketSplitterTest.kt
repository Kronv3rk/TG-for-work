package com.flowseal.tgwsproxy.proxy

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MtprotoPacketSplitterTest {
    private val relayInit = Random(7).nextBytes(64)

    /** Encrypts [plain] with the relay keystream, as TelegramProxy does before splitting. */
    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(relayInit.copyOfRange(8, 40), "AES"),
            IvParameterSpec(relayInit.copyOfRange(40, 56))
        )
        cipher.update(ByteArray(64))
        return cipher.update(plain)
    }

    private fun abridged(payloadWords: Int): ByteArray {
        val payload = Random(payloadWords).nextBytes(payloadWords * 4)
        val header = if (payloadWords < 0x7F) {
            byteArrayOf(payloadWords.toByte())
        } else {
            byteArrayOf(0x7F, payloadWords.toByte(), (payloadWords shr 8).toByte(), (payloadWords shr 16).toByte())
        }
        return header + payload
    }

    private fun intermediate(payloadLength: Int): ByteArray {
        val payload = Random(payloadLength).nextBytes(payloadLength)
        val header = byteArrayOf(
            payloadLength.toByte(),
            (payloadLength shr 8).toByte(),
            (payloadLength shr 16).toByte(),
            (payloadLength shr 24).toByte()
        )
        return header + payload
    }

    /** Feeds the encrypted stream in [chunkSize] pieces and checks every packet comes out whole. */
    private fun assertSplits(protocol: Int, packets: List<ByteArray>, chunkSize: Int) {
        val stream = encrypt(packets.reduce { acc, bytes -> acc + bytes })
        val splitter = MtprotoPacketSplitter(relayInit, protocol)
        val parts = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < stream.size) {
            val end = minOf(offset + chunkSize, stream.size)
            parts += splitter.split(stream.copyOfRange(offset, end))
            offset = end
        }
        parts += splitter.flush()

        assertEquals(packets.size, parts.size)
        var position = 0
        for ((index, part) in parts.withIndex()) {
            assertEquals("packet $index", packets[index].size, part.size)
            assertArrayEquals(stream.copyOfRange(position, position + part.size), part)
            position += part.size
        }
    }

    @Test
    fun abridgedPacketsAreSplitAtBoundaries() {
        val packets = listOf(abridged(1), abridged(10), abridged(0x7E), abridged(0x7F), abridged(300))
        for (chunk in listOf(1, 3, 17, 4096)) {
            assertSplits(MtprotoPacketSplitter.PROTO_ABRIDGED_INT, packets, chunk)
        }
    }

    @Test
    fun intermediatePacketsAreSplitAtBoundaries() {
        val packets = listOf(intermediate(4), intermediate(100), intermediate(5000))
        for (protocol in listOf(
            MtprotoPacketSplitter.PROTO_INTERMEDIATE_INT,
            MtprotoPacketSplitter.PROTO_PADDED_INTERMEDIATE_INT
        )) {
            for (chunk in listOf(1, 5, 64, 1 shl 16)) {
                assertSplits(protocol, packets, chunk)
            }
        }
    }

    @Test
    fun manySmallPacketsInOneChunk() {
        val packets = List(5000) { abridged(1 + it % 20) }
        assertSplits(MtprotoPacketSplitter.PROTO_ABRIDGED_INT, packets, Int.MAX_VALUE)
    }

    @Test
    fun partialPacketIsReturnedOnFlush() {
        val stream = encrypt(intermediate(100))
        val splitter = MtprotoPacketSplitter(relayInit, MtprotoPacketSplitter.PROTO_INTERMEDIATE_INT)
        assertEquals(0, splitter.split(stream.copyOfRange(0, 50)).size)
        val tail = splitter.flush()
        assertEquals(1, tail.size)
        assertArrayEquals(stream.copyOfRange(0, 50), tail[0])
        assertEquals(0, splitter.flush().size)
    }

    @Test
    fun invalidLengthDisablesSplitting() {
        // Intermediate length 0 is invalid: everything passes through untouched from then on.
        val bad = encrypt(ByteArray(8))
        val splitter = MtprotoPacketSplitter(relayInit, MtprotoPacketSplitter.PROTO_INTERMEDIATE_INT)
        val parts = splitter.split(bad)
        assertEquals(1, parts.size)
        assertArrayEquals(bad, parts[0])
        val next = byteArrayOf(1, 2, 3)
        assertArrayEquals(next, splitter.split(next).single())
    }

    @Test
    fun unknownProtocolPassesThrough() {
        val splitter = MtprotoPacketSplitter(relayInit, 12345)
        val data = byteArrayOf(9, 8, 7)
        assertArrayEquals(data, splitter.split(data).single())
    }
}
