package com.flowseal.tgwsproxy.proxy

import java.io.IOException
import java.io.InputStream

/**
 * Keeps malformed MTProto handshakes from being distinguishable by an immediate
 * close, as in upstream tg_ws_proxy.py. The caller imposes a socket read timeout;
 * this helper imposes an independent byte budget for continuously sending peers.
 */
internal object BadHandshakeDrainer {
    const val MAX_BYTES = 64 * 1024

    fun drain(input: InputStream): Int {
        val buffer = ByteArray(4 * 1024)
        var consumed = 0
        while (consumed < MAX_BYTES) {
            val count = try {
                input.read(buffer, 0, minOf(buffer.size, MAX_BYTES - consumed))
            } catch (_: IOException) {
                break
            }
            if (count <= 0) break
            consumed += count
        }
        return consumed
    }
}
