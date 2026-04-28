package com.flowseal.tgwsproxy.proxy

import com.flowseal.tgwsproxy.proxy.Constants.PROTO_ABRIDGED_INT
import com.flowseal.tgwsproxy.proxy.Constants.PROTO_INTERMEDIATE_INT
import com.flowseal.tgwsproxy.proxy.Constants.PROTO_PADDED_INTERMEDIATE_INT

/**
 * Splits a TCP byte stream into individual MTProto transport packets so each packet
 * can be sent as its own WebSocket frame. Mirrors `MsgSplitter` in `proxy/bridge.py`.
 *
 * The splitter keeps a parallel "plaintext" view (decrypted with the relay side keys)
 * solely to find packet boundaries. Output bytes are always the unmodified ciphertext
 * we already produced for the upstream.
 */
internal class MsgSplitter(relayInit: ByteArray, private val protoInt: Int) {
    private val dec: CtrCipher = CtrCipher(
        relayInit.copyOfRange(8, 40),
        relayInit.copyOfRange(40, 56),
    ).also { it.update(Constants.ZERO_64) }

    private val cipher = ByteBuffer()
    private val plain = ByteBuffer()
    private var disabled = false

    fun split(chunk: ByteArray): List<ByteArray> {
        if (chunk.isEmpty()) return emptyList()
        if (disabled) return listOf(chunk)

        cipher.append(chunk)
        plain.append(dec.update(chunk))

        val parts = ArrayList<ByteArray>()
        while (cipher.size > 0) {
            val len = nextPacketLen() ?: break
            if (len <= 0) {
                parts.add(cipher.takeAll())
                plain.clear()
                disabled = true
                break
            }
            parts.add(cipher.takeFirst(len))
            plain.takeFirst(len)
        }
        return parts
    }

    fun flush(): List<ByteArray> {
        if (cipher.size == 0) return emptyList()
        val tail = cipher.takeAll()
        plain.clear()
        return listOf(tail)
    }

    private fun nextPacketLen(): Int? {
        if (plain.size == 0) return null
        return when (protoInt) {
            PROTO_ABRIDGED_INT -> nextAbridged()
            PROTO_INTERMEDIATE_INT, PROTO_PADDED_INTERMEDIATE_INT -> nextIntermediate()
            else -> 0
        }
    }

    private fun nextAbridged(): Int? {
        val first = plain[0].toInt() and 0xFF
        return if (first == 0x7F || first == 0xFF) {
            if (plain.size < 4) null
            else {
                val a = plain[1].toInt() and 0xFF
                val b = plain[2].toInt() and 0xFF
                val c = plain[3].toInt() and 0xFF
                val payloadLen = (a or (b shl 8) or (c shl 16)) * 4
                if (payloadLen <= 0) 0
                else {
                    val packetLen = 4 + payloadLen
                    if (plain.size < packetLen) null else packetLen
                }
            }
        } else {
            val payloadLen = (first and 0x7F) * 4
            if (payloadLen <= 0) 0
            else {
                val packetLen = 1 + payloadLen
                if (plain.size < packetLen) null else packetLen
            }
        }
    }

    private fun nextIntermediate(): Int? {
        if (plain.size < 4) return null
        val b0 = plain[0].toInt() and 0xFF
        val b1 = plain[1].toInt() and 0xFF
        val b2 = plain[2].toInt() and 0xFF
        val b3 = plain[3].toInt() and 0xFF
        val payloadLen = (b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)) and 0x7FFFFFFF
        if (payloadLen <= 0) return 0
        val packetLen = 4 + payloadLen
        return if (plain.size < packetLen) null else packetLen
    }
}

/** Simple growable byte buffer with cheap pop-from-front. */
private class ByteBuffer(initialCapacity: Int = 4096) {
    private var buf = ByteArray(initialCapacity)
    private var off = 0
    private var len = 0

    val size: Int get() = len

    operator fun get(i: Int): Byte = buf[off + i]

    fun append(src: ByteArray) {
        ensureCapacity(len + src.size)
        System.arraycopy(src, 0, buf, off + len, src.size)
        len += src.size
    }

    fun takeFirst(n: Int): ByteArray {
        val out = ByteArray(n)
        System.arraycopy(buf, off, out, 0, n)
        off += n; len -= n
        if (len == 0) off = 0
        return out
    }

    fun takeAll(): ByteArray = takeFirst(len)

    fun clear() { off = 0; len = 0 }

    private fun ensureCapacity(needed: Int) {
        if (off + needed <= buf.size) return
        if (needed <= buf.size) {
            // compact in place
            System.arraycopy(buf, off, buf, 0, len)
            off = 0
            return
        }
        var cap = buf.size
        while (cap < needed) cap *= 2
        val n = ByteArray(cap)
        System.arraycopy(buf, off, n, 0, len)
        buf = n; off = 0
    }
}
