package com.flowseal.tgwsproxy.proxy

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import android.util.Base64

/**
 * Minimal WebSocket client that opens a TLS connection to a specific IP using a specific
 * SNI host, then performs the HTTP upgrade and exposes raw binary frames.
 *
 * Mirrors `proxy/raw_websocket.py` from the upstream Python project.
 */
class RawWebSocket private constructor(
    val socket: Socket,
    private val input: InputStream,
    private val output: OutputStream,
) {
    @Volatile private var closed = false
    private val random = SecureRandom()

    val isClosed: Boolean get() = closed || socket.isClosed

    fun send(data: ByteArray) {
        if (closed) throw IOException("websocket closed")
        output.write(buildFrame(OP_BINARY, data, mask = true))
        output.flush()
    }

    fun sendBatch(parts: List<ByteArray>) {
        if (closed) throw IOException("websocket closed")
        for (p in parts) output.write(buildFrame(OP_BINARY, p, mask = true))
        output.flush()
    }

    /** Returns next binary payload, or null on close. Handles ping/pong/close transparently. */
    fun recv(): ByteArray? {
        while (!closed) {
            val (opcode, payload) = readFrame() ?: return null
            when (opcode) {
                OP_CLOSE -> {
                    closed = true
                    runCatching {
                        output.write(buildFrame(OP_CLOSE, payload.copyOf(minOf(2, payload.size)), true))
                        output.flush()
                    }
                    return null
                }
                OP_PING -> {
                    runCatching {
                        output.write(buildFrame(OP_PONG, payload, true))
                        output.flush()
                    }
                }
                OP_PONG -> { /* ignore */ }
                0x1, OP_BINARY -> return payload
                else -> { /* skip unknown opcodes */ }
            }
        }
        return null
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching {
            output.write(buildFrame(OP_CLOSE, ByteArray(0), true))
            output.flush()
        }
        runCatching { socket.close() }
    }

    private fun buildFrame(opcode: Int, data: ByteArray, mask: Boolean): ByteArray {
        val length = data.size
        val fb = (0x80 or opcode).toByte()
        val header: ByteArray
        val payloadOut: ByteArray

        if (mask) {
            val key = ByteArray(4).also { random.nextBytes(it) }
            payloadOut = ByteArray(length)
            for (i in 0 until length) payloadOut[i] = (data[i].toInt() xor key[i % 4].toInt()).toByte()

            header = when {
                length < 126 -> byteArrayOf(fb, (0x80 or length).toByte()) + key
                length < 65536 -> byteArrayOf(fb, (0x80 or 126).toByte(),
                    (length ushr 8 and 0xFF).toByte(), (length and 0xFF).toByte()) + key
                else -> {
                    val l = length.toLong()
                    byteArrayOf(
                        fb, (0x80 or 127).toByte(),
                        (l ushr 56 and 0xFF).toByte(), (l ushr 48 and 0xFF).toByte(),
                        (l ushr 40 and 0xFF).toByte(), (l ushr 32 and 0xFF).toByte(),
                        (l ushr 24 and 0xFF).toByte(), (l ushr 16 and 0xFF).toByte(),
                        (l ushr 8 and 0xFF).toByte(), (l and 0xFF).toByte(),
                    ) + key
                }
            }
        } else {
            payloadOut = data
            header = when {
                length < 126 -> byteArrayOf(fb, length.toByte())
                length < 65536 -> byteArrayOf(fb, 126.toByte(),
                    (length ushr 8 and 0xFF).toByte(), (length and 0xFF).toByte())
                else -> {
                    val l = length.toLong()
                    byteArrayOf(
                        fb, 127.toByte(),
                        (l ushr 56 and 0xFF).toByte(), (l ushr 48 and 0xFF).toByte(),
                        (l ushr 40 and 0xFF).toByte(), (l ushr 32 and 0xFF).toByte(),
                        (l ushr 24 and 0xFF).toByte(), (l ushr 16 and 0xFF).toByte(),
                        (l ushr 8 and 0xFF).toByte(), (l and 0xFF).toByte(),
                    )
                }
            }
        }
        return header + payloadOut
    }

    private fun readFrame(): Pair<Int, ByteArray>? {
        val din = DataInputStream(input)
        val b0 = try { din.readUnsignedByte() } catch (_: IOException) { return null }
        val b1 = try { din.readUnsignedByte() } catch (_: IOException) { return null }
        val opcode = b0 and 0x0F
        var length: Long = (b1 and 0x7F).toLong()
        when (length.toInt()) {
            126 -> length = din.readUnsignedShort().toLong()
            127 -> length = din.readLong()
        }
        val masked = (b1 and 0x80) != 0
        val maskKey = if (masked) ByteArray(4).also { din.readFully(it) } else null
        if (length > Int.MAX_VALUE.toLong()) throw IOException("ws frame too large")
        val payload = ByteArray(length.toInt())
        if (length > 0) din.readFully(payload)
        if (maskKey != null) {
            for (i in payload.indices) payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
        }
        return opcode to payload
    }

    companion object {
        private const val OP_BINARY = 0x2
        private const val OP_CLOSE = 0x8
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xA

        /**
         * Connect to [hostIp]:443, set SNI to [sniDomain], send WebSocket upgrade.
         *
         * @throws WsHandshakeException if the server returns a non-101 status
         * @throws IOException for I/O / TLS errors
         */
        @Throws(IOException::class)
        fun connect(
            hostIp: String,
            sniDomain: String,
            timeoutMs: Int = 10_000,
            bufferSizeBytes: Int = 256 * 1024,
        ): RawWebSocket {
            val raw = Socket()
            raw.tcpNoDelay = true
            runCatching {
                raw.sendBufferSize = bufferSizeBytes
                raw.receiveBufferSize = bufferSizeBytes
            }
            raw.connect(InetSocketAddress(hostIp, 443), timeoutMs)

            val ssl = SSL_CONTEXT.socketFactory.createSocket(raw, sniDomain, 443, true) as SSLSocket
            ssl.useClientMode = true
            ssl.soTimeout = timeoutMs
            val params = ssl.sslParameters
            params.serverNames = listOf(SNIHostName(sniDomain))
            ssl.sslParameters = params
            ssl.startHandshake()

            val out = ssl.outputStream
            val input = ssl.inputStream

            val wsKey = randomWsKey()
            val req = buildString {
                append("GET /apiws HTTP/1.1\r\n")
                append("Host: ").append(sniDomain).append("\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Key: ").append(wsKey).append("\r\n")
                append("Sec-WebSocket-Version: 13\r\n")
                append("Sec-WebSocket-Protocol: binary\r\n")
                append("\r\n")
            }
            out.write(req.toByteArray(StandardCharsets.US_ASCII))
            out.flush()

            val statusLine = readHttpLine(input)
                ?: run { runCatching { ssl.close() }; throw WsHandshakeException(0, "empty response") }
            val parts = statusLine.split(' ', limit = 3)
            val statusCode = parts.getOrNull(1)?.toIntOrNull() ?: 0

            val headers = HashMap<String, String>()
            while (true) {
                val line = readHttpLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] =
                    line.substring(idx + 1).trim()
            }

            if (statusCode != 101) {
                runCatching { ssl.close() }
                throw WsHandshakeException(statusCode, statusLine, headers, headers["location"])
            }
            return RawWebSocket(ssl, input, out)
        }

        private fun readHttpLine(input: InputStream): String? {
            val sb = StringBuilder()
            while (true) {
                val ch = input.read()
                if (ch == -1) return if (sb.isEmpty()) null else sb.toString()
                if (ch == '\n'.code) {
                    if (sb.isNotEmpty() && sb[sb.length - 1] == '\r') sb.setLength(sb.length - 1)
                    return sb.toString()
                }
                sb.append(ch.toChar())
                if (sb.length > 8192) throw IOException("HTTP line too long")
            }
        }

        private fun randomWsKey(): String {
            val b = ByteArray(16)
            SecureRandom().nextBytes(b)
            return Base64.encodeToString(b, Base64.NO_WRAP)
        }

        private val TRUST_ALL: Array<TrustManager> = arrayOf(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })

        // Trust-all to match the Python `verify_mode = CERT_NONE`. The MTProto stream inside
        // is itself end-to-end encrypted; the outer TLS only carries opaque ciphertext.
        private val SSL_CONTEXT: SSLContext = SSLContext.getInstance("TLS").apply {
            init(null, TRUST_ALL, SecureRandom())
        }
    }
}

class WsHandshakeException(
    val statusCode: Int,
    val statusLine: String,
    val headers: Map<String, String> = emptyMap(),
    val location: String? = null,
) : IOException("HTTP $statusCode: $statusLine") {
    val isRedirect: Boolean
        get() = statusCode in setOf(301, 302, 303, 307, 308)
}
