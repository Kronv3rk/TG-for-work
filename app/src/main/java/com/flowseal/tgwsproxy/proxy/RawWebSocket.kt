package com.flowseal.tgwsproxy.proxy

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.security.MessageDigest
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
    private val socket: Socket,
    private val input: BufferedInputStream,
    private val output: BufferedOutputStream,
    /** The TCP connection used fronting SNI rather than the relay hostname. */
    val isFronted: Boolean
) : Closeable {
    @Volatile
    private var closed = false
    private val messageAssembler = WebSocketMessageAssembler(WebSocketProtocol.MAX_FRAME_BYTES.toInt())

    /** True while the socket still looks usable; checked before a pooled socket is handed out. */
    val isOpen: Boolean
        get() = !closed && !socket.isClosed && !socket.isInputShutdown && !socket.isOutputShutdown

    fun sendBinary(data: ByteArray) {
        if (closed) throw EOFException("WebSocket closed")
        synchronized(output) {
            WebSocketFrameWriter.writeBinary(output, data, random)
            output.flush()
        }
    }

    /** One flush per decrypted MTProto read, rather than per frame. */
    fun sendBinaryBatch(packets: List<ByteArray>) {
        if (closed) throw EOFException("WebSocket closed")
        synchronized(output) {
            WebSocketFrameWriter.writeBinaryBatch(output, packets, random)
            output.flush()
        }
    }

    fun receive(): ByteArray? {
        while (!closed) {
            // Wait for a new frame at a frame boundary with an idle budget. If the
            // relay stays silent we send keepalive pings; if it never answers we
            // treat the connection as dead so the client can reconnect instead of
            // hanging forever on a half-open socket (common on cellular).
            val first = readFrameStartByte()
            if (first < 0) return null

            // Inside a frame now: bytes must keep trickling in. A long silence here
            // means a genuine stall, but a slow-but-progressing media frame stays
            // alive because every read that returns at least one byte resets the clock.
            socket.soTimeout = STALL_TIMEOUT_MS
            val second = input.read()
            if (second < 0) return null

            val opcode = first and 0x0F
            val shortLength = second and 0x7F
            val length = when (shortLength) {
                126 -> readUnsignedShort().toLong()
                127 -> readLong()
                else -> shortLength.toLong()
            }
            // Validate before allocating memory for an untrusted frame.
            WebSocketProtocol.validateServerFrame(first, second, length)
            val data = readExact(length.toInt())
            val fin = (first and 0x80) != 0

            when (opcode) {
                OP_CLOSE -> {
                    close()
                    return null
                }
                OP_PING -> {
                    synchronized(output) {
                        output.write(buildFrame(OP_PONG, data, masked = true))
                        output.flush()
                    }
                }
                OP_PONG -> Unit
                OP_TEXT, OP_BINARY, 0 -> {
                    val message = messageAssembler.accept(opcode, fin, data)
                    if (message != null) return message
                }
            }
        }
        return null
    }

    /**
     * Reads the first byte of the next frame, sending keepalive pings while the
     * relay is idle. Returns the byte, or a negative value if the stream ended or
     * the relay failed to answer [MAX_IDLE_PINGS] consecutive pings.
     */
    private fun readFrameStartByte(): Int {
        var idlePings = 0
        socket.soTimeout = IDLE_TIMEOUT_MS
        while (!closed) {
            try {
                return input.read()
            } catch (timeout: SocketTimeoutException) {
                idlePings++
                if (idlePings > MAX_IDLE_PINGS) return -1
                val pinged = runCatching {
                    synchronized(output) {
                        output.write(buildFrame(OP_PING, EMPTY_PAYLOAD, masked = true))
                        output.flush()
                    }
                }.isSuccess
                if (!pinged) return -1
            }
        }
        return -1
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
        private const val WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val OP_TEXT = 0x1
        private const val OP_BINARY = 0x2
        private const val OP_CLOSE = 0x8
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xA
        private const val BUFFER_SIZE = 256 * 1024
        private const val MAX_FRAME_SIZE = 8 * 1024 * 1024
        private const val MAX_HEADER_BYTES = 32 * 1024

        // Keepalive tuning. While idle (waiting for a new frame) we ping every
        // IDLE_TIMEOUT_MS; after MAX_IDLE_PINGS unanswered pings the relay is
        // considered dead (~60s). STALL_TIMEOUT_MS guards the middle of a frame:
        // a slow media frame survives as long as some bytes keep arriving.
        private const val IDLE_TIMEOUT_MS = 20_000
        private const val STALL_TIMEOUT_MS = 30_000
        private const val MAX_IDLE_PINGS = 3
        private val EMPTY_PAYLOAD = ByteArray(0)
        private val random = SecureRandom()

        /**
         * Opens a WebSocket to [domain] (sent as HTTP Host) over [targetIp].
         * Only opt-in Cloudflare Proxy/Worker uses cleartext port 80; ordinary
         * Telegram WebSockets and Domain Fronting always require TLS.
         *
         * [sni] overrides the TLS server name for domain fronting: the censor sees an
         * innocuous name while the relay still routes on the Host header. The
         * certificate chain and SNI hostname are verified by the platform trust store.
         * The HTTP Host remains the relay domain for fronting.
         */
        fun connect(
            targetIp: String,
            domain: String,
            timeoutMs: Int = 10_000,
            path: String = "/apiws",
            sni: String? = null,
            secure: Boolean = true
        ): RawWebSocket {
            require(CloudflareTransportMode.isValid(secure, sni)) {
                "Domain fronting requires a TLS connection"
            }
            val plain = Socket()
            var ssl: SSLSocket? = null
            try {
                plain.tcpNoDelay = true
                plain.keepAlive = true
                plain.receiveBufferSize = BUFFER_SIZE
                plain.sendBufferSize = BUFFER_SIZE
                val port = CloudflareTransportMode.port(secure)
                plain.connect(InetSocketAddress(targetIp, port), timeoutMs)
                plain.soTimeout = timeoutMs

                val transportSocket: Socket = if (secure) {
                    val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
                    val serverName = sni ?: domain
                    val connectedSsl = sslFactory.createSocket(plain, serverName, port, true) as SSLSocket
                    ssl = connectedSsl
                    connectedSsl.sslParameters = TlsEndpointPolicy.configure(
                        connectedSsl.sslParameters, domain, sni
                    )
                    connectedSsl.startHandshake()
                    connectedSsl.soTimeout = timeoutMs
                    connectedSsl
                } else plain

                val input = BufferedInputStream(transportSocket.inputStream)
                val output = BufferedOutputStream(transportSocket.outputStream)
                val keyBytes = ByteArray(16).also(random::nextBytes)
                val wsKey = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
                val request = buildString {
                    append("GET ").append(path).append(" HTTP/1.1\r\n")
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
                    throw WebSocketHandshakeException(status, firstLine.ifBlank { "Empty WebSocket response" })
                }
                val expectedAccept = Base64.encodeToString(
                    MessageDigest.getInstance("SHA-1").digest(
                        (wsKey + WEBSOCKET_GUID).toByteArray(Charsets.US_ASCII)
                    ),
                    Base64.NO_WRAP
                )
                WebSocketProtocol.validateUpgrade(lines, expectedAccept)

                // Keep an idle read budget instead of blocking forever: receive()
                // uses it to drive keepalive pings and to detect dead relays.
                transportSocket.soTimeout = IDLE_TIMEOUT_MS
                return RawWebSocket(transportSocket, input, output, isFronted = sni != null)
            } catch (error: Exception) {
                runCatching { ssl?.close() }
                runCatching { plain.close() }
                throw error
            }
        }

        private fun readHttpHeaders(input: BufferedInputStream): List<String> {
            val result = mutableListOf<String>()
            val line = StringBuilder()
            var bytesRead = 0
            while (true) {
                val ch = input.read()
                if (ch < 0) break
                bytesRead++
                if (bytesRead > MAX_HEADER_BYTES) {
                    throw WebSocketHandshakeException(0, "WebSocket response headers are too large")
                }
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
