package com.flowseal.tgwsproxy.proxy

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "tg-mtproto-proxy"

/**
 * Local MTProto proxy server. Mirrors the responsibilities of `proxy/tg_ws_proxy.py`:
 *   - listen on host:port
 *   - read 64-byte client init, validate against [config.secret]
 *   - bridge to the matching DC over WSS using a Telegram CDN IP + Telegram WS domain
 *   - fall back to direct TCP if WSS is unavailable
 */
class ProxyServer(
    private val config: ProxyConfig,
    val stats: ProxyStats = ProxyStats(),
) {
    sealed class State {
        data object Stopped : State()
        data class Running(val host: String, val port: Int) : State()
        data class Failed(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Stopped)
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val openClients = ConcurrentHashMap.newKeySet<Socket>()
    private val openWebSockets = ConcurrentHashMap.newKeySet<RawWebSocket>()

    private val secretBytes: ByteArray = config.secret.hexToBytes()

    // Per-DC cooldown / blacklist mirrors the upstream's WS_FAIL_TIMEOUT logic.
    private val dcFailUntilMs = ConcurrentHashMap<String, Long>()
    private val wsBlacklist = ConcurrentHashMap.newKeySet<String>()

    fun start() {
        if (_state.value is State.Running) return
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(java.net.InetSocketAddress(InetAddress.getByName(config.host), config.port))
            serverSocket = ss
            _state.value = State.Running(config.host, config.port)
            Log.i(TAG, "Listening on ${config.host}:${config.port} secret=${config.secret}")
            acceptJob = scope.launch { acceptLoop(ss) }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to bind", e)
            _state.value = State.Failed(e.message ?: "bind failed")
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        // Force-close any in-flight client sockets / upstream WS connections so that
        // the blocking reads on Dispatchers.IO threads return promptly.
        openClients.forEach { runCatching { it.close() } }
        openClients.clear()
        openWebSockets.forEach { runCatching { it.close() } }
        openWebSockets.clear()
        scope.coroutineContext[Job]?.children?.forEach { it.cancel() }
        acceptJob = null
        dcFailUntilMs.clear()
        wsBlacklist.clear()
        _state.value = State.Stopped
        Log.i(TAG, "stopped. ${stats.snapshot.value.summary()}")
    }

    fun shutdownAndFree() {
        stop()
        scope.cancel()
    }

    private suspend fun acceptLoop(ss: ServerSocket) {
        while (true) {
            val client = try {
                withContext(Dispatchers.IO) { ss.accept() }
            } catch (e: IOException) {
                Log.d(TAG, "accept loop end: ${e.message}")
                break
            } catch (_: CancellationException) {
                break
            }
            client.tcpNoDelay = true
            runCatching {
                client.sendBufferSize = config.bufferSizeBytes
                client.receiveBufferSize = config.bufferSizeBytes
            }
            scope.launchBridge { handleClient(client) }
        }
    }

    private suspend fun handleClient(client: Socket) {
        val peer = "${client.inetAddress.hostAddress}:${client.port}"
        stats.onConnect()
        openClients += client
        try {
            val input = client.getInputStream()

            val handshake = ByteArray(Constants.HANDSHAKE_LEN)
            try {
                var read = 0
                while (read < handshake.size) {
                    val r = input.read(handshake, read, handshake.size - read)
                    if (r <= 0) return
                    read += r
                }
            } catch (_: IOException) { return }

            val parsed = tryParseHandshake(handshake, secretBytes)
            if (parsed == null) {
                stats.onBad()
                Log.w(TAG, "[$peer] bad handshake (wrong secret or proto)")
                runCatching { while (input.read(handshake) > 0) {} }
                return
            }
            val (dc, isMedia, protoTag, clientDecPrekeyIv) = parsed
            val protoInt = when {
                protoTag.contentEquals(Constants.PROTO_TAG_ABRIDGED) -> Constants.PROTO_ABRIDGED_INT
                protoTag.contentEquals(Constants.PROTO_TAG_INTERMEDIATE) -> Constants.PROTO_INTERMEDIATE_INT
                else -> Constants.PROTO_PADDED_INTERMEDIATE_INT
            }
            val dcIdx = if (isMedia) -dc else dc
            val mediaTag = if (isMedia) " media" else ""
            Log.d(TAG, "[$peer] handshake ok DC$dc$mediaTag proto=0x${"%08X".format(protoInt)}")

            val relayInit = generateRelayInit(protoTag, dcIdx)
            val ctx = buildCryptoCtx(clientDecPrekeyIv, secretBytes, relayInit)

            val dcKey = "$dc${if (isMedia) "m" else ""}"
            val now = System.currentTimeMillis()

            val targetIp = config.dcRedirects[dc]
            if (targetIp == null || dcKey in wsBlacklist) {
                Log.i(TAG, "[$peer] DC$dc$mediaTag fallback (no redirect / blacklisted)")
                tryFallback(client, peer, dc, isMedia, mediaTag, ctx, relayInit)
                return
            }

            val cooldown = dcFailUntilMs[dcKey] ?: 0L
            val wsTimeout = if (now < cooldown) WS_FAIL_TIMEOUT_MS else 10_000

            var ws: RawWebSocket? = null
            var ws302 = false
            var allRedirects = true
            for (domain in wsDomains(dc, isMedia)) {
                Log.i(TAG, "[$peer] DC$dc$mediaTag -> wss://$domain/apiws via $targetIp")
                try {
                    ws = withContext(Dispatchers.IO) {
                        RawWebSocket.connect(targetIp, domain, wsTimeout, config.bufferSizeBytes)
                    }
                    allRedirects = false
                    break
                } catch (e: WsHandshakeException) {
                    stats.onWsError()
                    if (e.isRedirect) {
                        ws302 = true
                        Log.w(TAG, "[$peer] DC$dc$mediaTag got ${e.statusCode} from $domain -> ${e.location}")
                        continue
                    } else {
                        allRedirects = false
                        Log.w(TAG, "[$peer] DC$dc$mediaTag WS handshake: ${e.statusLine}")
                    }
                } catch (e: IOException) {
                    stats.onWsError(); allRedirects = false
                    Log.w(TAG, "[$peer] DC$dc$mediaTag WS connect failed: ${e.message}")
                }
            }

            if (ws == null) {
                if (ws302 && allRedirects) {
                    wsBlacklist += dcKey
                    Log.w(TAG, "[$peer] DC$dc$mediaTag blacklisted (all 302)")
                } else {
                    dcFailUntilMs[dcKey] = now + DC_FAIL_COOLDOWN_MS
                }
                tryFallback(client, peer, dc, isMedia, mediaTag, ctx, relayInit)
                return
            }

            dcFailUntilMs.remove(dcKey)
            stats.onWs()

            val splitter = runCatching { MsgSplitter(relayInit, protoInt) }.getOrNull()
            openWebSockets += ws
            try {
                ws.send(relayInit)
                bridgeWsReencrypt(client, ws, peer, ctx, dc, isMedia, splitter, stats)
            } finally {
                openWebSockets -= ws
                runCatching { ws.close() }
            }
        } catch (_: CancellationException) {
        } catch (e: Throwable) {
            Log.e(TAG, "[$peer] unexpected", e)
        } finally {
            openClients -= client
            runCatching { client.close() }
            stats.onDisconnect()
        }
    }

    private suspend fun tryFallback(
        clientSocket: Socket,
        peer: String,
        dc: Int,
        isMedia: Boolean,
        mediaTag: String,
        ctx: CryptoCtx,
        relayInit: ByteArray,
    ) {
        val ip = Constants.DC_DEFAULT_IPS[dc] ?: return
        val sock = openTcpFallback(ip, 443) ?: run {
            Log.w(TAG, "[$peer] DC$dc$mediaTag TCP fallback to $ip:443 failed")
            return
        }
        stats.onTcpFallback()
        Log.i(TAG, "[$peer] DC$dc$mediaTag -> TCP fallback to $ip:443")
        try {
            sock.getOutputStream().write(relayInit)
            sock.getOutputStream().flush()
            bridgeTcpReencrypt(clientSocket, sock, peer, ctx, stats)
        } finally {
            runCatching { sock.close() }
        }
    }

    /** WS upstream domains for a given DC; the order favours the media variant when applicable. */
    private fun wsDomains(dc: Int, isMedia: Boolean): List<String> {
        val effectiveDc = if (dc == 203) 2 else dc
        return if (isMedia)
            listOf("kws$effectiveDc-1.web.telegram.org", "kws$effectiveDc.web.telegram.org")
        else
            listOf("kws$effectiveDc.web.telegram.org", "kws$effectiveDc-1.web.telegram.org")
    }

    companion object {
        private const val DC_FAIL_COOLDOWN_MS = 30_000L
        private const val WS_FAIL_TIMEOUT_MS = 2_000
    }
}
