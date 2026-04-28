package com.flowseal.tgwsproxy.proxy

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

private const val TAG = "tg-mtproto-proxy"

/**
 * Bidirectional pump: client TCP <-> upstream WebSocket, with re-encryption.
 *
 * client ciphertext  -> decrypt(clt_key) -> encrypt(tg_key)  -> WS frames
 * WS frame           -> decrypt(tg_key)  -> encrypt(clt_key) -> client TCP
 *
 * When either direction's read ends, we close the *other* endpoint so its blocking
 * `read()` unblocks, otherwise the IO thread would stay stuck.
 */
internal suspend fun bridgeWsReencrypt(
    clientSocket: Socket,
    ws: RawWebSocket,
    label: String,
    ctx: CryptoCtx,
    dc: Int?,
    isMedia: Boolean,
    splitter: MsgSplitter?,
    stats: ProxyStats,
) {
    val dcTag = if (dc != null) "DC$dc${if (isMedia) "m" else ""}" else "DC?"
    var upBytes = 0L; var downBytes = 0L
    var upPackets = 0L; var downPackets = 0L
    val started = System.nanoTime()

    val clientIn = clientSocket.getInputStream()
    val clientOut = clientSocket.getOutputStream()

    coroutineScope {
        val tcpToWs = launch(Dispatchers.IO) {
            val buf = ByteArray(65536)
            try {
                while (isActive) {
                    val n = try { clientIn.read(buf) } catch (e: IOException) { -1 }
                    if (n <= 0) {
                        splitter?.flush()?.firstOrNull()?.let { runCatching { ws.send(it) } }
                        break
                    }
                    stats.onBytesUp(n); upBytes += n; upPackets++
                    val plain = ctx.cltDec.update(buf, 0, n)
                    val cipher = ctx.tgEnc.update(plain)
                    if (splitter != null) {
                        val parts = splitter.split(cipher)
                        if (parts.isEmpty()) continue
                        if (parts.size > 1) ws.sendBatch(parts) else ws.send(parts[0])
                    } else {
                        ws.send(cipher)
                    }
                }
            } catch (_: CancellationException) {
            } catch (e: Throwable) {
                Log.d(TAG, "[$label] tcp->ws ended: ${e.message}")
            } finally {
                runCatching { ws.close() }
            }
        }

        val wsToTcp = launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val data = ws.recv() ?: break
                    val n = data.size
                    stats.onBytesDown(n); downBytes += n; downPackets++
                    val plain = ctx.tgDec.update(data)
                    val out = ctx.cltEnc.update(plain)
                    clientOut.write(out)
                    clientOut.flush()
                }
            } catch (_: CancellationException) {
            } catch (e: Throwable) {
                Log.d(TAG, "[$label] ws->tcp ended: ${e.message}")
            } finally {
                runCatching { clientSocket.close() }
            }
        }

        select<Unit> { tcpToWs.onJoin {}; wsToTcp.onJoin {} }
        // Each finally above closes the peer endpoint; just nudge cancellation along.
        tcpToWs.cancel(); wsToTcp.cancel()
    }

    val elapsedMs = (System.nanoTime() - started) / 1_000_000
    Log.i(
        TAG,
        "[$label] $dcTag WS session closed: ^${humanBytes(upBytes)} ($upPackets pkts) " +
            "v${humanBytes(downBytes)} ($downPackets pkts) in ${elapsedMs}ms",
    )
    runCatching { ws.close() }
}

/**
 * Bidirectional pump: client TCP <-> upstream TCP (fallback), with re-encryption.
 */
internal suspend fun bridgeTcpReencrypt(
    clientSocket: Socket,
    upstream: Socket,
    label: String,
    ctx: CryptoCtx,
    stats: ProxyStats,
) {
    val clientIn = clientSocket.getInputStream()
    val clientOut = clientSocket.getOutputStream()
    val upIn = upstream.getInputStream()
    val upOut = upstream.getOutputStream()

    coroutineScope {
        val upJob = launch(Dispatchers.IO) {
            val buf = ByteArray(65536)
            try {
                while (isActive) {
                    val n = try { clientIn.read(buf) } catch (e: IOException) { -1 }
                    if (n <= 0) break
                    stats.onBytesUp(n)
                    val plain = ctx.cltDec.update(buf, 0, n)
                    val cipher = ctx.tgEnc.update(plain)
                    upOut.write(cipher); upOut.flush()
                }
            } catch (_: CancellationException) {
            } catch (e: Throwable) {
                Log.d(TAG, "[$label] tcp->tcp(up) ended: ${e.message}")
            } finally {
                runCatching { upstream.close() }
            }
        }
        val downJob = launch(Dispatchers.IO) {
            val buf = ByteArray(65536)
            try {
                while (isActive) {
                    val n = try { upIn.read(buf) } catch (e: IOException) { -1 }
                    if (n <= 0) break
                    stats.onBytesDown(n)
                    val plain = ctx.tgDec.update(buf, 0, n)
                    val out = ctx.cltEnc.update(plain)
                    clientOut.write(out); clientOut.flush()
                }
            } catch (_: CancellationException) {
            } catch (e: Throwable) {
                Log.d(TAG, "[$label] tcp->tcp(down) ended: ${e.message}")
            } finally {
                runCatching { clientSocket.close() }
            }
        }
        select<Unit> { upJob.onJoin {}; downJob.onJoin {} }
        upJob.cancel(); downJob.cancel()
    }
    runCatching { upstream.close() }
}

internal suspend fun openTcpFallback(ip: String, port: Int, timeoutMs: Int = 10_000): Socket? =
    withContext(Dispatchers.IO) {
        try {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(ip, port), timeoutMs)
            s
        } catch (e: IOException) {
            null
        }
    }

/** Convenience: fire-and-forget bridge launcher used by the server. */
internal fun CoroutineScope.launchBridge(block: suspend () -> Unit): Job =
    launch(Dispatchers.IO) {
        try { block() } catch (_: CancellationException) {} catch (e: Throwable) {
            Log.d(TAG, "bridge error: ${e.message}")
        }
    }
// Kotlin file content updated here

// ... rest of the file content

while (this@coroutineScope.isActive) { 
    // some implementation
}

while (this@coroutineScope.isActive) { 
    // some implementation
}
