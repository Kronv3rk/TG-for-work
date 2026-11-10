package com.flowseal.tgwsproxy.proxy

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * Reads the optional clear-text PROXY protocol v1 header from an explicitly
 * trusted front-end. Never enable this on a listener receiving direct Telegram
 * connections: the header is not authenticated and is not part of MTProto.
 */
internal object ProxyProtocolV1 {
    // HAProxy's maximum v1 line length, including CRLF.
    private const val MAX_HEADER_BYTES = 107

    internal data class ForwardedPeer(
        val sourceAddress: String,
        val sourcePort: Int
    )

    @Throws(IOException::class)
    fun read(input: InputStream): ForwardedPeer? {
        val line = ByteArrayOutputStream(MAX_HEADER_BYTES)
        while (line.size() < MAX_HEADER_BYTES) {
            val ch = input.read()
            if (ch < 0) throw EOFException("Truncated PROXY v1 header")
            line.write(ch)
            if (ch != '\n'.code) continue
            val bytes = line.toByteArray()
            if (bytes.size < 2 || bytes[bytes.size - 2] != '\r'.code.toByte()) {
                throw IOException("PROXY v1 header must end with CRLF")
            }
            val content = bytes.copyOf(bytes.size - 2)
            if (content.any { (it.toInt() and 0xFF) < 32 || (it.toInt() and 0xFF) > 126 }) {
                throw IOException("PROXY v1 header is not ASCII")
            }
            return decode(String(content, Charsets.US_ASCII))
        }
        throw IOException("PROXY v1 header exceeded 107 bytes")
    }

    private fun decode(line: String): ForwardedPeer? {
        val parts = line.split(' ')
        if (parts.size < 2 || parts[0] != "PROXY") {
            throw IOException("Missing PROXY v1 signature")
        }
        if (parts[1] == "UNKNOWN") return null
        val family = parts[1]
        if ((family != "TCP4" && family != "TCP6") || parts.size != 6) {
            throw IOException("Unsupported PROXY v1 address family")
        }
        val isV6 = family == "TCP6"
        val source = parts[2]
        val destination = parts[3]
        if ((source.contains(':') != isV6) || (destination.contains(':') != isV6) ||
            !ProxyOptions.isIpAddress(source) || !ProxyOptions.isIpAddress(destination)
        ) {
            throw IOException("Invalid PROXY v1 address")
        }
        val srcPort = parts[4].toIntOrNull()
        val dstPort = parts[5].toIntOrNull()
        if (srcPort == null || dstPort == null ||
            srcPort !in 0..65535 || dstPort !in 0..65535
        ) throw IOException("Invalid PROXY v1 port")
        return ForwardedPeer(source, srcPort)
    }
}
