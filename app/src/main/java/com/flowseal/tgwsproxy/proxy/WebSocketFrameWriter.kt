package com.flowseal.tgwsproxy.proxy

import java.io.OutputStream
import java.security.SecureRandom
import kotlin.experimental.xor

/**
 * Writes an RFC 6455 masked binary frame without allocating a second full-size
 * payload. Large MTProto media frames use one bounded scratch buffer.
 */
internal object WebSocketFrameWriter {
    private const val CHUNK_BYTES = 16 * 1024

    fun writeBinary(output: OutputStream, payload: ByteArray, random: SecureRandom) {
        val length = payload.size
        val header = ByteArray(14)
        var pos = 0
        header[pos++] = 0x82.toByte() // FIN + binary opcode
        when {
            length <= 125 -> header[pos++] = (0x80 or length).toByte()
            length <= 65535 -> {
                header[pos++] = (0x80 or 126).toByte()
                header[pos++] = (length ushr 8).toByte()
                header[pos++] = length.toByte()
            }
            else -> {
                header[pos++] = (0x80 or 127).toByte()
                val size64 = length.toLong()
                for (shift in 56 downTo 0 step 8) header[pos++] = (size64 ushr shift).toByte()
            }
        }
        val mask = ByteArray(4)
        random.nextBytes(mask)
        for (byte in mask) header[pos++] = byte
        output.write(header, 0, pos)

        val scratch = ByteArray(minOf(length, CHUNK_BYTES))
        var offset = 0
        while (offset < length) {
            val count = minOf(scratch.size, length - offset)
            for (i in 0 until count) {
                scratch[i] = payload[offset + i] xor mask[(offset + i) and 3]
            }
            output.write(scratch, 0, count)
            offset += count
        }
    }
}
