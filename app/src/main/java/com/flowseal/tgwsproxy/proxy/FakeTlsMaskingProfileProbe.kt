package com.flowseal.tgwsproxy.proxy

import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLEngineResult

/**
 * Measures the first TLS 1.3 encrypted record of the selected masking server.
 *
 * Uses JSSE SSLEngine to emit a ClientHello without consuming an actual TLS
 * session. A raw short-lived TCP socket allows reading the server's first
 * flight without decrypting it. Failure always means "use 64–160 bytes".
 */
internal object FakeTlsMaskingProfileProbe {
    private const val MAX_FLIGHT_BYTES = 65_536
    private const val MAX_HELLO_BYTES = 16_384
    private const val MIN_TIMEOUT_MS = 100

    /**
     * Mirrors upstream's first_appdata_record_len parser. A partial handshake
     * returns null; zero denotes an alert or other non-TLS1.3 record.
     */
    fun firstApplicationDataLength(flight: ByteArray): Int? {
        var pos = 0
        while (pos + 5 <= flight.size) {
            val type = flight[pos].toInt() and 0xff
            val recordLen = ((flight[pos + 3].toInt() and 0xff) shl 8) or
                (flight[pos + 4].toInt() and 0xff)
            if (type == 0x17) {
                return if (flight.size - pos - 5 >= recordLen) recordLen else null
            }
            if (type != 0x16 && type != 0x14) return 0
            if (flight.size - pos - 5 < recordLen) return null
            pos += 5 + recordLen
        }
        return null
    }

    fun probe(domain: String, timeoutMs: Int = 5_000, port: Int = 443): Int? {
        if (domain.isBlank() || timeoutMs < MIN_TIMEOUT_MS) return null
        val engine = try {
            val sslContext = SSLContext.getDefault()
            sslContext.createSSLEngine(domain, port).apply {
                useClientMode = true
                if ("TLSv1.3" !in supportedProtocols) return null
                enabledProtocols = arrayOf("TLSv1.3")
                sslParameters = sslParameters.apply {
                    serverNames = listOf(SNIHostName(domain))
                    // ALPN is supported by Android 10 and later. Without it the
                    // server flight is still a valid TLS 1.3 profile sample.
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        applicationProtocols = arrayOf("h2", "http/1.1")
                    }
                }
                beginHandshake()
            }
        } catch (_: Exception) {
            return null
        }

        try {
            val outbound = ByteBuffer.allocate(MAX_HELLO_BYTES)
            val empty = ByteBuffer.allocate(0)
            repeat(6) {
                val status = engine.handshakeStatus
                if (status == SSLEngineResult.HandshakeStatus.NEED_TASK) {
                    while (true) {
                        val delegated = engine.delegatedTask ?: break
                        delegated.run()
                    }
                } else if (status == SSLEngineResult.HandshakeStatus.NEED_WRAP) {
                    val result = engine.wrap(empty, outbound)
                    if (result.status == SSLEngineResult.Status.BUFFER_OVERFLOW) return null
                    if (result.status == SSLEngineResult.Status.CLOSED) return null
                } else return@repeat
                if (engine.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_UNWRAP) {
                    return@repeat
                }
            }
            if (outbound.position() !in 6..MAX_HELLO_BYTES) return null
            val hello = ByteArray(outbound.position())
            outbound.flip()
            outbound.get(hello)
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
            Socket().use { socket ->
                val connectedMs = remainingMs(deadline)
                if (connectedMs <= 0) return null
                socket.connect(InetSocketAddress(domain, port), connectedMs)
                socket.tcpNoDelay = true
                socket.getOutputStream().apply {
                    write(hello)
                    flush()
                }
                val flight = ByteArrayOutputStream()
                val chunk = ByteArray(8 * 1024)
                while (flight.size() < MAX_FLIGHT_BYTES) {
                    val leftMs = remainingMs(deadline)
                    if (leftMs <= 0) return null
                    socket.soTimeout = leftMs
                    val size = socket.getInputStream().read(
                        chunk, 0, minOf(chunk.size, MAX_FLIGHT_BYTES - flight.size())
                    )
                    if (size <= 0) return null
                    flight.write(chunk, 0, size)
                    val profile = firstApplicationDataLength(flight.toByteArray())
                    if (profile != null) return profile.takeIf { it > 0 }
                }
            }
        } catch (_: Exception) {
            // A blocked masking host, missing TLS 1.3, or short reply must never
            // prevent the local MTProto listener from starting.
        } finally {
            engine.closeOutbound()
        }
        return null
    }

    private fun remainingMs(deadlineNanos: Long): Int {
        val left = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
        return if (left <= 0) 0 else left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
