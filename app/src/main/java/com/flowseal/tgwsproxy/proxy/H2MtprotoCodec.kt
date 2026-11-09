package com.flowseal.tgwsproxy.proxy

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom
import javax.crypto.Cipher

/**
 * MTProto packet framing needed by Cloudflare's HTTP/2 POST /api transport.
 *
 * Unlike the streaming WebSocket relay, each H2 request carries one decrypted
 * native MTProto packet. The cipher must be the same continuously advancing
 * AES-CTR context that decrypted the initial obfuscated client handshake.
 *
 * This module is transport-independent: the actual H2 connection pool,
 * HTTP/2 negotiation and bounded request/replay scheduler remain separate.
 */
internal object H2MtprotoCodec {
    private const val MAX_PACKET = 4 * 1024 * 1024
    private const val MAX_PADDING = 256
    private const val MIN_PACKET = 24
    private val random = SecureRandom()

    data class Packet(val body: ByteArray, val quickAck: Boolean)

    @Throws(IOException::class)
    fun readPacket(input: InputStream, decrypt: Cipher, protocol: Int): Packet {
        fun plain(count: Int): ByteArray {
            require(count > 0)
            val buf = ByteArray(count)
            var offset = 0
            while (offset < count) {
                val read = input.read(buf, offset, count - offset)
                if (read < 0) throw EOFException("Native MTProto packet ended early")
                if (read == 0) continue
                offset += read
            }
            return decrypt.update(buf)
        }

        val quick: Boolean
        val length: Long
        when (protocol) {
            MtprotoPacketSplitter.PROTO_ABRIDGED_INT -> {
                val first = plain(1)[0].toInt() and 0xff
                quick = (first and 0x80) != 0
                val words = if ((first and 0x7f) == 0x7f) {
                    val tail = plain(3)
                    (tail[0].toLong() and 0xff) or
                        ((tail[1].toLong() and 0xff) shl 8) or
                        ((tail[2].toLong() and 0xff) shl 16)
                } else (first and 0x7f).toLong()
                length = words * 4
            }
            MtprotoPacketSplitter.PROTO_INTERMEDIATE_INT,
            MtprotoPacketSplitter.PROTO_PADDED_INTERMEDIATE_INT -> {
                val header = plain(4)
                val value = (header[0].toLong() and 0xff) or
                    ((header[1].toLong() and 0xff) shl 8) or
                    ((header[2].toLong() and 0xff) shl 16) or
                    ((header[3].toLong() and 0xff) shl 24)
                quick = value and 0x80000000L != 0L
                length = value and 0x7fffffffL
            }
            else -> throw IllegalArgumentException("Unsupported MTProto framing")
        }

        val padded = protocol == MtprotoPacketSplitter.PROTO_PADDED_INTERMEDIATE_INT
        if (length < MIN_PACKET || length > MAX_PACKET.toLong() + if (padded) MAX_PADDING else 0) {
            throw IOException("Invalid H2 MTProto packet length")
        }
        val decrypted = plain(length.toInt())
        if (!padded) {
            if (decrypted.size % 4 != 0) throw IOException("Invalid packet alignment")
            return Packet(decrypted, quick)
        }

        val packetLength = if (decrypted.take(8).all { it == 0.toByte() }) {
            val declared = (decrypted[16].toLong() and 0xff) or
                ((decrypted[17].toLong() and 0xff) shl 8) or
                ((decrypted[18].toLong() and 0xff) shl 16) or
                ((decrypted[19].toLong() and 0xff) shl 24)
            20L + declared
        } else {
            24L + ((decrypted.size - 24) / 16) * 16
        }
        val padding = decrypted.size.toLong() - packetLength
        val allowedPadding = if (decrypted.take(8).all { it == 0.toByte() }) MAX_PADDING else 15
        if (packetLength < MIN_PACKET || packetLength > decrypted.size ||
            padding < 0 || padding > allowedPadding || packetLength % 4 != 0L ||
            packetLength > MAX_PACKET
        ) {
            throw IOException("Invalid padded H2 MTProto packet")
        }
        return Packet(decrypted.copyOf(packetLength.toInt()), quick)
    }

    fun encodeReply(body: ByteArray, protocol: Int): ByteArray {
        require(body.isNotEmpty() && body.size % 4 == 0 && body.size <= MAX_PACKET) {
            "Invalid HTTP MTProto response length"
        }
        return when (protocol) {
            MtprotoPacketSplitter.PROTO_ABRIDGED_INT -> {
                val words = body.size / 4
                val prefix = if (words < 0x7f) {
                    byteArrayOf(words.toByte())
                } else {
                    byteArrayOf(
                        0x7f, words.toByte(), (words ushr 8).toByte(), (words ushr 16).toByte()
                    )
                }
                prefix + body
            }
            MtprotoPacketSplitter.PROTO_INTERMEDIATE_INT -> {
                val length = body.size
                byteArrayOf(length.toByte(), (length ushr 8).toByte(),
                    (length ushr 16).toByte(), (length ushr 24).toByte()) + body
            }
            MtprotoPacketSplitter.PROTO_PADDED_INTERMEDIATE_INT -> {
                val padding = ByteArray(random.nextInt(4)).also(random::nextBytes)
                val length = body.size + padding.size
                byteArrayOf(length.toByte(), (length ushr 8).toByte(),
                    (length ushr 16).toByte(), (length ushr 24).toByte()) + body + padding
            }
            else -> throw IllegalArgumentException("Unsupported MTProto framing")
        }
    }
}
