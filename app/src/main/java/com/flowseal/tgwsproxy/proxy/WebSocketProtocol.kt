package com.flowseal.tgwsproxy.proxy

import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * RFC 6455 validation for the binary MTProto transport.
 * Pure JVM code, independent of Android sockets, so malformed frames are testable.
 */
internal object WebSocketProtocol {
    fun validateUpgrade(lines: List<String>, expectedAccept: String) {
        val status = lines.firstOrNull()?.trim()?.split(Regex("\\s+"))?.getOrNull(1)
        check(status == "101") { "WebSocket upgrade rejected: invalid HTTP status" }

        val headers = lines.drop(1).mapNotNull { line ->
            val index = line.indexOf(':')
            if (index <= 0) null else line.substring(0, index).trim().lowercase() to
                line.substring(index + 1).trim()
        }.groupBy({ it.first }, { it.second })

        fun tokens(name: String): List<String> =
            headers[name].orEmpty().flatMap { header ->
                header.split(',').map(String::trim)
            }
        check(tokens("upgrade").any { it.equals("websocket", ignoreCase = true) }) {
            "Missing WebSocket Upgrade header"
        }
        check(tokens("connection").any { it.equals("upgrade", ignoreCase = true) }) {
            "Missing Connection: Upgrade header"
        }
        val accepts = headers["sec-websocket-accept"].orEmpty()
        check(accepts.size == 1 && MessageDigest.isEqual(
            accepts.single().toByteArray(Charsets.US_ASCII),
            expectedAccept.toByteArray(Charsets.US_ASCII)
        )) { "WebSocket server confirmation is invalid" }

        val subprotocols = tokens("sec-websocket-protocol")
        check(subprotocols.isEmpty() || (subprotocols.size == 1 &&
            subprotocols.single().equals("binary", ignoreCase = true))) {
            "Unsupported WebSocket subprotocol"
        }
    }

    fun validateServerFrame(first: Int, second: Int, length: Long) {
        check((first and 0x70) == 0) { "Unsupported WebSocket extension bits" }
        check((second and 0x80) == 0) { "Server sent a masked WebSocket frame" }
        check(length >= 0 && length <= MAX_FRAME_BYTES) { "WebSocket frame is too large" }
        val opcode = first and 0x0F
        check(opcode in setOf(0, 1, 2, 8, 9, 10)) { "Unknown WebSocket opcode" }
        if (opcode >= 8) {
            check((first and 0x80) != 0) { "Fragmented control frame" }
            check(length <= 125) { "Control frame is too large" }
        }
    }

    const val MAX_FRAME_BYTES = 8L * 1024 * 1024
}

internal class WebSocketMessageAssembler(private val maxBytes: Int) {
    private var fragments: ByteArrayOutputStream? = null

    /** Returns a completed binary message or null when more fragments are expected. */
    fun accept(opcode: Int, fin: Boolean, data: ByteArray): ByteArray? {
        check(opcode == 0 || opcode == 2) { "MTProto WebSocket requires binary messages" }
        if (opcode == 2) {
            check(fragments == null) { "New message before continuation completes" }
            check(data.size <= maxBytes) { "WebSocket message is too large" }
            if (fin) return data
            fragments = ByteArrayOutputStream(data.size).also { it.write(data) }
            return null
        }
        val buffer = fragments ?: error("Unexpected continuation frame")
        check(buffer.size() <= maxBytes - data.size) { "WebSocket message is too large" }
        buffer.write(data)
        return if (fin) buffer.toByteArray().also { fragments = null } else null
    }
}
