package com.flowseal.tgwsproxy.proxy

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import android.util.Base64
import kotlin.experimental.xor

class WebSocketHandshakeException(
    val statusCode: Int,
    val statusLine: String
) : Exception(statusLine) {
    val isRedirect: Boolean
        get() = statusCode in listOf(301, 302, 303, 307, 308)
}

class RawWebSocket private constructor(
    private val socket: SSLSocket,
    private val input: BufferedInputStream,
    private val output: BufferedOutputStream
) : Closeable {
    @Volatile
    private var closed = false

    fun sendBinary(data: ByteArray) {
        if (closed) throw EOFException("WebSocket closed")
        synchronized(output) {
            output.write(buildFrame(OP_BINARY, data, masked = true))
            output.flush()
        }
    }

    fun receive(): ByteArray? {
        while (!closed) {
            val first = input.read()
            if (first < 0) return null
            val second = input.read()
            if (second < 0) return null

            val opcode = first and 0x0F
            var length = second and 0x7F
            if (length == 126) {
                length = readUnsignedShort()
            } else if (length == 127) {
                val longLength = readLong()
                if (longLength > Int.MAX_VALUE) {
                    throw IllegalStateException("Frame is too large")
                }
                length = longLength.toInt()
            }

            val mask = if ((second and 0x80) != 0) readExact(4) else null
            val payload = readExact(length)
            val data = if (mask != null) xorMask(payload, mask) else payload

            when (opcode) {
                OP_CLOSE -> {
                    closed = true
                    return null
                }
                OP_PING -> {
                    synchronized(output) {
                        output.write(buildFrame(OP_PONG, data, masked = true))
                        output.flush()
                    }
                }
                OP_PONG -> Unit
                OP_TEXT, OP_BINARY -> return data
            }
        }
        return null
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching {
            synchronized(output) {
                output.write(buildFrame(OP_CLOSE, ByteArray(0), masked = true))
                output.flush()
            }
        }
        runCatching { socket.close() }
    }

    private fun readExact(length: Int): ByteArray {
        val data = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(data, offset, length - offset)
            if (read < 0) throw EOFException("Unexpected end of WebSocket stream")
            offset += read
        }
        return data
    }

    private fun readUnsignedShort(): Int {
        val data = readExact(2)
        return ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
    }

    private fun readLong(): Long {
        val data = readExact(8)
        var value = 0L
        for (b in data) {
            value = (value shl 8) or (b.toLong() and 0xFF)
        }
        return value
    }

    companion object {
        private const val OP_TEXT = 0x1
        private const val OP_BINARY = 0x2
        private const val OP_CLOSE = 0x8
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xA
        private val random = SecureRandom()

        fun connect(targetIp: String, domain: String, timeoutMs: Int = 10_000): RawWebSocket {
            val plain = Socket()
            plain.tcpNoDelay = true
            plain.connect(InetSocketAddress(targetIp, 443), timeoutMs)
            plain.soTimeout = timeoutMs

            val ssl = SSLSocketFactory.getDefault()
                .createSocket(plain, targetIp, 443, true) as SSLSocket
            ssl.sslParameters = ssl.sslParameters.apply {
                serverNames = listOf(SNIHostName(domain))
            }
            ssl.startHandshake()
            ssl.soTimeout = timeoutMs

            val input = BufferedInputStream(ssl.inputStream)
            val output = BufferedOutputStream(ssl.outputStream)
            val keyBytes = ByteArray(16).also(random::nextBytes)
            val wsKey = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
            val request = buildString {
                append("GET /apiws HTTP/1.1\r\n")
                append("Host: ").append(domain).append("\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Key: ").append(wsKey).append("\r\n")
                append("Sec-WebSocket-Version: 13\r\n")
                append("Sec-WebSocket-Protocol: binary\r\n")
                append("\r\n")
            }

            output.write(request.toByteArray(Charsets.US_ASCII))
            output.flush()

            val lines = readHttpHeaders(input)
            val firstLine = lines.firstOrNull().orEmpty()
            val status = firstLine.split(" ").getOrNull(1)?.toIntOrNull() ?: 0
            if (status != 101) {
                runCatching { ssl.close() }
                throw WebSocketHandshakeException(status, firstLine.ifBlank { "Empty WebSocket response" })
            }

            ssl.soTimeout = 0
            return RawWebSocket(ssl, input, output)
        }

        private fun readHttpHeaders(input: BufferedInputStream): List<String> {
            val result = mutableListOf<String>()
            val line = StringBuilder()
            while (true) {
                val ch = input.read()
                if (ch < 0) break
                if (ch == '\n'.code) {
                    val text = line.toString().trimEnd('\r')
                    if (text.isEmpty()) break
                    result += text
                    line.setLength(0)
                } else {
                    line.append(ch.toChar())
                }
            }
            return result
        }

        private fun buildFrame(opcode: Int, data: ByteArray, masked: Boolean): ByteArray {
            val maskBytes = if (masked) ByteArray(4).also(random::nextBytes) else ByteArray(0)
            val headerLength = when {
                data.size < 126 -> 2
                data.size <= 65_535 -> 4
                else -> 10
            } + maskBytes.size
            val frame = ByteArray(headerLength + data.size)
            var offset = 0
            frame[offset++] = (0x80 or opcode).toByte()
            val maskBit = if (masked) 0x80 else 0
            when {
                data.size < 126 -> frame[offset++] = (maskBit or data.size).toByte()
                data.size <= 65_535 -> {
                    frame[offset++] = (maskBit or 126).toByte()
                    frame[offset++] = ((data.size shr 8) and 0xFF).toByte()
                    frame[offset++] = (data.size and 0xFF).toByte()
                }
                else -> {
                    frame[offset++] = (maskBit or 127).toByte()
                    val len = data.size.toLong()
                    for (i in 7 downTo 0) {
                        frame[offset++] = ((len shr (8 * i)) and 0xFF).toByte()
                    }
                }
            }

            if (masked) {
                System.arraycopy(maskBytes, 0, frame, offset, maskBytes.size)
                offset += maskBytes.size
                val maskedPayload = xorMask(data, maskBytes)
                System.arraycopy(maskedPayload, 0, frame, offset, maskedPayload.size)
            } else {
                System.arraycopy(data, 0, frame, offset, data.size)
            }
            return frame
        }

        private fun xorMask(data: ByteArray, mask: ByteArray): ByteArray {
            val out = ByteArray(data.size)
            for (i in data.indices) {
                out[i] = data[i] xor mask[i % 4]
            }
            return out
        }
    }
}
