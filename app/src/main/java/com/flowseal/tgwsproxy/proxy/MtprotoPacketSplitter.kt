package com.flowseal.tgwsproxy.proxy

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Cuts the relay-bound obfuscated MTProto stream into whole transport packets so
 * every WebSocket frame carries exactly one packet, which the web relay requires.
 *
 * It decrypts a shadow copy of the stream (keyed from the relay init) only to read
 * packet lengths; the ciphertext is what gets sent. Buffers are consumed by moving
 * a read offset and compacted once per [split] call, so a chunk holding many small
 * packets costs O(N) instead of copying the remainder after every packet
 * (upstream tg-ws-proxy 49e62ca).
 */
class MtprotoPacketSplitter(relayInit: ByteArray, private val protocol: Int) {
    private val decrypt: Cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
        init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(relayInit.copyOfRange(SKIP_LEN, SKIP_LEN + KEY_LEN), "AES"),
            IvParameterSpec(relayInit.copyOfRange(SKIP_LEN + KEY_LEN, SKIP_LEN + KEY_LEN + IV_LEN))
        )
    }
    private val cipher = GrowableBuffer()
    private val plain = GrowableBuffer()
    private var disabled = false

    init {
        decrypt.update(ByteArray(HANDSHAKE_LEN))
    }

    fun split(chunk: ByteArray): List<ByteArray> {
        if (chunk.isEmpty()) return emptyList()
        if (disabled) return listOf(chunk)

        cipher.append(chunk)
        plain.append(decrypt.update(chunk))

        val parts = mutableListOf<ByteArray>()
        while (cipher.available > 0) {
            val packetLength = nextPacketLength() ?: break
            if (packetLength <= 0) {
                // Unknown framing: stop splitting and pass the stream through as is.
                parts += cipher.take(cipher.available)
                plain.clear()
                disabled = true
                break
            }
            parts += cipher.take(packetLength)
            plain.skip(packetLength)
        }
        cipher.compact()
        plain.compact()
        return parts
    }

    fun flush(): List<ByteArray> {
        if (cipher.available == 0) return emptyList()
        val tail = cipher.take(cipher.available)
        cipher.clear()
        plain.clear()
        return listOf(tail)
    }

    /** Length of the next whole packet, null when more bytes are needed, 0 when the framing is invalid. */
    private fun nextPacketLength(): Int? {
        if (plain.available == 0) return null
        return when (protocol) {
            PROTO_ABRIDGED_INT -> nextAbridgedLength()
            PROTO_INTERMEDIATE_INT, PROTO_PADDED_INTERMEDIATE_INT -> nextIntermediateLength()
            else -> 0
        }
    }

    private fun nextAbridgedLength(): Int? {
        val first = plain[0]
        val headerLength: Int
        val payloadLength: Int
        if (first == 0x7F || first == 0xFF) {
            if (plain.available < 4) return null
            payloadLength = (plain[1] or (plain[2] shl 8) or (plain[3] shl 16)) * 4
            headerLength = 4
        } else {
            payloadLength = (first and 0x7F) * 4
            headerLength = 1
        }
        if (payloadLength <= 0 || payloadLength > MAX_PACKET_SIZE) return 0
        val packetLength = headerLength + payloadLength
        return if (plain.available >= packetLength) packetLength else null
    }

    private fun nextIntermediateLength(): Int? {
        if (plain.available < 4) return null
        val payloadLength = plain[0] or (plain[1] shl 8) or (plain[2] shl 16) or ((plain[3] and 0x7F) shl 24)
        if (payloadLength <= 0 || payloadLength > MAX_PACKET_SIZE) return 0
        val packetLength = 4 + payloadLength
        return if (plain.available >= packetLength) packetLength else null
    }

    /** Byte buffer with a read offset; [compact] drops consumed bytes once per batch. */
    private class GrowableBuffer {
        private var data = ByteArray(0)
        private var start = 0
        private var end = 0

        val available: Int
            get() = end - start

        /** Unsigned byte at [index] relative to the read offset. */
        operator fun get(index: Int): Int = data[start + index].toInt() and 0xFF

        fun append(bytes: ByteArray) {
            if (end + bytes.size > data.size) {
                val live = available
                val grown = if (live + bytes.size > data.size) {
                    ByteArray(maxOf(live + bytes.size, data.size * 2))
                } else {
                    data
                }
                System.arraycopy(data, start, grown, 0, live)
                data = grown
                start = 0
                end = live
            }
            System.arraycopy(bytes, 0, data, end, bytes.size)
            end += bytes.size
        }

        fun take(length: Int): ByteArray {
            val out = data.copyOfRange(start, start + length)
            start += length
            return out
        }

        fun skip(length: Int) {
            start += length
        }

        fun compact() {
            if (start == 0) return
            if (start == end) {
                clear()
                return
            }
            // Only shift when the dead prefix dominates; append() compacts lazily too.
            if (start >= data.size / 2) {
                val live = available
                System.arraycopy(data, start, data, 0, live)
                start = 0
                end = live
            }
        }

        fun clear() {
            start = 0
            end = 0
            if (data.size > RETAIN_CAPACITY) data = ByteArray(0)
        }
    }

    companion object {
        const val PROTO_ABRIDGED_INT = -269488145
        const val PROTO_INTERMEDIATE_INT = -286331154
        const val PROTO_PADDED_INTERMEDIATE_INT = -572662307
        private const val HANDSHAKE_LEN = 64
        private const val SKIP_LEN = 8
        private const val KEY_LEN = 32
        private const val IV_LEN = 16
        private const val MAX_PACKET_SIZE = 8 * 1024 * 1024
        private const val RETAIN_CAPACITY = 1024 * 1024
    }
}
