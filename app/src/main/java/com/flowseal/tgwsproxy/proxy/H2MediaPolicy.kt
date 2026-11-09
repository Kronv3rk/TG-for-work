package com.flowseal.tgwsproxy.proxy

import java.io.IOException

/** Strict checks for Cloudflare's HTTP/2 MTProto POST /api protocol. */
internal object H2MediaPolicy {
    const val MAX_REPLY_BYTES = 4 * 1024 * 1024

    fun isPreflightValid(protocol: String, status: Int): Boolean =
        protocol == "h2" && (status in 200..299 || status == 405 || status == 501)

    @Throws(IOException::class)
    fun validateReply(protocol: String, status: Int, contentType: String?, body: ByteArray) {
        if (protocol != "h2") throw IOException("Cloudflare HTTP/2 negotiation failed")
        if (status != 200) throw IOException("Cloudflare /api returned HTTP $status")
        if (contentType?.contains("text/html", ignoreCase = true) == true) {
            throw IOException("Cloudflare /api returned HTML instead of MTProto")
        }
        if (body.isEmpty() || body.size > MAX_REPLY_BYTES || body.size % 4 != 0) {
            throw IOException("Invalid or oversized MTProto HTTP/2 reply")
        }
    }
}
