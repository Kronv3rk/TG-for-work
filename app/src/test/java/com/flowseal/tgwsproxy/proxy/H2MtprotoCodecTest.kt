package com.flowseal.tgwsproxy.proxy

import java.io.ByteArrayInputStream
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class H2MtprotoCodecTest {
    private val key = ByteArray(32) { it.toByte() }
    private val iv = ByteArray(16) { (15 - it).toByte() }

    private fun cipher(): Cipher = Cipher.getInstance("AES/CTR/NoPadding").also {
        it.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
    }

    @Test
    fun abridgedPacketsCanBeExtractedFromEncryptedTcpStream() {
        val body = ByteArray(24) { (it + 11).toByte() }
        val wire = byteArrayOf((body.size / 4).toByte()) + body
        val encrypted = cipher().update(wire)
        val packet = H2MtprotoCodec.readPacket(ByteArrayInputStream(encrypted), cipher(),
            MtprotoPacketSplitter.PROTO_ABRIDGED_INT)
        assertArrayEquals(body, packet.body)
        assertFalse(packet.quickAck)
    }

    @Test
    fun abridgedExtendedLengthPreservesQuickAckBit() {
        val body = ByteArray(128 * 4) { it.toByte() }
        val prefix = byteArrayOf(0xff.toByte(), 128.toByte(), 0, 0)
        val packet = H2MtprotoCodec.readPacket(
            ByteArrayInputStream(cipher().update(prefix + body)),
            cipher(), MtprotoPacketSplitter.PROTO_ABRIDGED_INT
        )
        assertTrue(packet.quickAck)
        assertArrayEquals(body, packet.body)
    }

    @Test
    fun intermediateQuickAckAndReplyEncoding() {
        val body = ByteArray(24) { (it + 1).toByte() }
        val prefix = byteArrayOf(24, 0, 0, 0x80.toByte())
        val packet = H2MtprotoCodec.readPacket(
            ByteArrayInputStream(cipher().update(prefix + body)),
            cipher(), MtprotoPacketSplitter.PROTO_INTERMEDIATE_INT
        )
        assertTrue(packet.quickAck)
        assertArrayEquals(body, packet.body)
        val reply = H2MtprotoCodec.encodeReply(body, MtprotoPacketSplitter.PROTO_INTERMEDIATE_INT)
        assertArrayEquals(byteArrayOf(24, 0, 0, 0) + body, reply)
    }

    @Test
    fun paddedIntermediateStripsTrailingPadding() {
        val body = ByteArray(24)
        body[16] = 4 // unencrypted packet length = 20 + 4
        val encrypted = cipher().update(byteArrayOf(28, 0, 0, 0) + body + byteArrayOf(1, 2, 3, 4))
        val packet = H2MtprotoCodec.readPacket(
            ByteArrayInputStream(encrypted), cipher(),
            MtprotoPacketSplitter.PROTO_PADDED_INTERMEDIATE_INT
        )
        assertArrayEquals(body, packet.body)
    }

    @Test
    fun rejectsOversizedFramesBeforeReadingTheirBody() {
        val size = 4 * 1024 * 1024 + 4
        val header = byteArrayOf(size.toByte(), (size shr 8).toByte(),
            (size shr 16).toByte(), (size shr 24).toByte())
        val encryptedHeader = cipher().update(header)
        assertThrows(IOException::class.java) {
            H2MtprotoCodec.readPacket(
                ByteArrayInputStream(encryptedHeader), cipher(),
                MtprotoPacketSplitter.PROTO_INTERMEDIATE_INT
            )
        }
    }

    @Test
    fun abridgedReplyUsesLittleEndianWordCount() {
        val body = ByteArray(512) { 1 }
        val reply = H2MtprotoCodec.encodeReply(body, MtprotoPacketSplitter.PROTO_ABRIDGED_INT)
        assertArrayEquals(byteArrayOf(0x7f, 128.toByte(), 0, 0),
            reply.copyOfRange(0, 4))
        assertArrayEquals(body, reply.copyOfRange(4, reply.size))
    }
}
