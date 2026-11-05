package com.flowseal.tgwsproxy.proxy

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.xor
import kotlin.math.abs

class TelegramProxy(
    private val options: ProxyOptions
) : Closeable {
    private val random = SecureRandom()

    /** Socket/relay buffer and worker-thread ceiling, both user-tunable. */
    private val bufferSize = options.bufferBytes
    private val executor = newProxyExecutor(options.threadLimit)
    private val cfBalancer = CfProxyBalancer()
    private val routePlanner = RoutePlanner()
    private val clients = CopyOnWriteArraySet<Socket>()
    private val activeConnections = AtomicInteger(0)
    private val totalConnections = AtomicInteger(0)
    private val wsConnections = AtomicInteger(0)
    private val tcpFallbackConnections = AtomicInteger(0)
    private val cfProxyConnections = AtomicInteger(0)
    private val workerConnections = AtomicInteger(0)
    private val frontingConnections = AtomicInteger(0)
    private val badConnections = AtomicInteger(0)
    private val maskedConnections = AtomicInteger(0)
    private val bytesUp = AtomicLong(0)
    private val bytesDown = AtomicLong(0)

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var lastMessage: String = "Прокси остановлен"

    /** Same endpoint as [options], but carrying the current network type. */
    @Volatile
    private var networkOptions: ProxyOptions = options

    /** Pool refills dial on their own threads so they never take a client's slot in [executor]. */
    private val poolExecutor = Executors.newCachedThreadPool(namedDaemonThreads("tg-ws-pool"))
    private val wsPool = WsPool(
        size = WsPool.DEFAULT_SIZE,
        connector = { host, domain -> RawWebSocket.connect(host, domain, WsPool.CONNECT_TIMEOUT_MS) },
        isOpen = RawWebSocket::isOpen,
        isTargetBlocked = { routePlanner.isTargetBlocked(it) },
        isActive = { networkOptions.wsPoolActive },
        background = { poolExecutor.execute(it) }
    )

    @Volatile
    private var poolMaintenance: ScheduledExecutorService? = null

    val proxyLink: String
        get() = ProxyLinkFormatter.link(options)

    val plainProxyLink: String
        get() = ProxyLinkFormatter.plain(options)

    val isRunning: Boolean
        get() = serverSocket?.isClosed == false

    fun hasOptions(newOptions: ProxyOptions): Boolean = options.sameConnection(newOptions)

    /**
     * The network type changed but the endpoint did not. Update it in place so route
     * ordering follows the new network without restarting the proxy.
     */
    fun updateNetwork(newOptions: ProxyOptions) {
        val changed = newOptions.cellularNetwork != networkOptions.cellularNetwork
        networkOptions = newOptions
        if (changed) {
            // Blocks and pooled sockets belong to the old network.
            routePlanner.resetTargetCooldowns()
            wsPool.clear()
        }
    }

    fun start() {
        if (isRunning) return
        if (options.cfProxyEnabled) {
            if (options.cfProxyUserDomain.isBlank()) {
                cfBalancer.refreshAsync(executor)
            } else {
                cfBalancer.setUserDomain(options.cfProxyUserDomain)
            }
        }
        val address = InetAddress.getByName(options.host)
        val server = ServerSocket(options.port, 50, address)
        server.reuseAddress = true
        serverSocket = server
        if (options.wsPoolEnabled && poolMaintenance == null) {
            poolMaintenance = WsPool.startMaintenance(wsPool)
        }
        lastMessage = "Прокси слушает ${options.host}:${options.port}"
        // Run accept on a dedicated thread so client handlers and their download
        // workers (which share the bounded executor) can never starve the listener.
        Thread({
            while (!server.isClosed) {
                try {
                    val socket = server.accept()
                    socket.tcpNoDelay = true
                    socket.keepAlive = true
                    socket.receiveBufferSize = bufferSize
                    socket.sendBufferSize = bufferSize
                    clients += socket
                    totalConnections.incrementAndGet()
                    activeConnections.incrementAndGet()
                    try {
                        executor.execute { handleClient(socket) }
                    } catch (rejected: RejectedExecutionException) {
                        activeConnections.decrementAndGet()
                        badConnections.incrementAndGet()
                        clients -= socket
                        lastMessage = "Слишком много соединений: новое соединение отклонено"
                        runCatching { socket.close() }
                    }
                } catch (ignored: Exception) {
                    if (!server.isClosed) {
                        lastMessage = "Ошибка локального сервера: ${ignored.message ?: "unknown"}"
                    }
                }
            }
        }, "tg-proxy-accept").apply { isDaemon = true }.start()
    }

    fun snapshot(): ProxyStats = ProxyStats(
        running = isRunning,
        activeConnections = activeConnections.get(),
        totalConnections = totalConnections.get(),
        wsConnections = wsConnections.get(),
        tcpFallbackConnections = tcpFallbackConnections.get(),
        cfProxyConnections = cfProxyConnections.get(),
        workerConnections = workerConnections.get(),
        frontingConnections = frontingConnections.get(),
        poolHits = wsPool.hitCount,
        poolMisses = wsPool.missCount,
        badConnections = badConnections.get(),
        maskedConnections = maskedConnections.get(),
        bytesUp = bytesUp.get(),
        bytesDown = bytesDown.get(),
        proxyLink = proxyLink,
        plainProxyLink = plainProxyLink,
        fakeTlsEnabled = options.fakeTlsEnabled,
        cfProxyEnabled = options.cfProxyEnabled,
        routeStatus = routePlanner.status(),
        routePolicy = routePlanner.policyLabel(networkOptions),
        routeHealth = routePlanner.healthSummary(),
        recentEvents = ConnectionLog.recent(RECENT_EVENTS_LIMIT),
        networkMode = if (networkOptions.cellularNetwork) "Мобильная сеть" else "Wi-Fi или локальная сеть",
        lastMessage = lastMessage
    )

    override fun close() {
        lastMessage = "Прокси остановлен"
        runCatching { serverSocket?.close() }
        serverSocket = null
        for (client in clients) {
            runCatching { client.close() }
        }
        clients.clear()
        poolMaintenance?.shutdownNow()
        poolMaintenance = null
        wsPool.close()
        poolExecutor.shutdownNow()
        executor.shutdownNow()
    }

    private fun handleClient(client: Socket) {
        try {
            BufferedInputStream(client.getInputStream()).use { rawIn ->
                BufferedOutputStream(client.getOutputStream()).use { rawOut ->
                    val fakeTls = FakeTls(secretBytes, options.fakeTlsDomain)
                    val streams = fakeTls.readClientStreams(rawIn, rawOut) ?: run {
                        maskedConnections.incrementAndGet()
                        return
                    }
                    val handshake = streams.handshake
                    val parsed = tryHandshake(handshake)
                    if (parsed == null) {
                        badConnections.incrementAndGet()
                        lastMessage = "Telegram отклонил handshake: проверьте secret"
                        ConnectionLog.rejected("Handshake отклонён: проверьте secret в ссылке")
                        return
                    }

                    val dcId = parsed.dcId
                    val dcIndex = if (parsed.isMedia) -dcId else dcId
                    val relayInit = generateRelayInit(parsed.protocolTag, dcIndex)
                    val crypto = buildCryptoContext(parsed.clientDecPrekeyIv, relayInit)
                    val splitter = runCatching {
                        MtprotoPacketSplitter(relayInit, parsed.protocolInt)
                    }.getOrNull()

                    bridgeRoutes(client, dcId, parsed.isMedia, streams.input, streams.output, relayInit, crypto, splitter)
                }
            }
        } catch (ignored: Exception) {
            lastMessage = "Соединение закрыто: ${ignored.message ?: "network error"}"
        } finally {
            activeConnections.decrementAndGet()
            clients -= client
            runCatching { client.close() }
        }
    }

    private fun bridgeRoutes(
        client: Socket,
        dcId: Int,
        isMedia: Boolean,
        clientIn: InputStream,
        clientOut: OutputStream,
        relayInit: ByteArray,
        crypto: CryptoContext,
        splitter: MtprotoPacketSplitter?
    ) {
        val wsTarget = wsConnectHost(dcId, options.dcIpMap)
        val methods = routePlanner.methodsFor(dcId, isMedia, networkOptions, wsTarget)
        for (method in methods) {
            routePlanner.markTrying(method, dcId, isMedia)
            lastMessage = routePlanner.status()

            val handled = runCatching {
                when (method) {
                    RouteMethod.WORKER -> bridgeCfWorker(
                        client,
                        dcId,
                        isMedia,
                        clientIn,
                        clientOut,
                        relayInit,
                        crypto
                    )
                    RouteMethod.WEBSOCKET -> bridgeWebSocket(
                        client,
                        dcId,
                        isMedia,
                        clientIn,
                        clientOut,
                        relayInit,
                        crypto,
                        splitter
                    )
                    RouteMethod.FRONTING -> bridgeFronting(
                        client,
                        dcId,
                        isMedia,
                        clientIn,
                        clientOut,
                        relayInit,
                        crypto,
                        splitter
                    )
                    RouteMethod.CLOUDFLARE -> bridgeCfProxyFallback(
                        client,
                        dcId,
                        isMedia,
                        clientIn,
                        clientOut,
                        relayInit,
                        crypto,
                        splitter
                    )
                    RouteMethod.TCP -> bridgeTcpFallback(client, dcId, isMedia, clientIn, clientOut, relayInit, crypto)
                }
            }.getOrElse {
                false
            }

            if (handled) return

            routePlanner.markFailed(method, dcId, isMedia)
            ConnectionLog.connectFailed(method, dcId, isMedia, "")
            lastMessage = routePlanner.status()
        }

        lastMessage = "Telegram DC$dcId недоступен: все маршруты не ответили"
        ConnectionLog.rejected("DC$dcId недоступен: все маршруты не ответили")
    }

    private fun bridgeWebSocket(
        client: Socket,
        dcId: Int,
        isMedia: Boolean,
        clientIn: InputStream,
        clientOut: OutputStream,
        relayInit: ByteArray,
        crypto: CryptoContext,
        splitter: MtprotoPacketSplitter?
    ): Boolean {
        val host = wsConnectHost(dcId, options.dcIpMap) ?: return false
        val domains = wsDomains(dcId, isMedia)

        if (networkOptions.wsPoolActive) {
            val pooled = wsPool.get(WsPool.Key(dcId, isMedia), host, domains)
            if (pooled != null) {
                wsConnections.incrementAndGet()
                // No dial happened, so there is no latency sample to feed the route health.
                routePlanner.markConnected(RouteMethod.WEBSOCKET, dcId, isMedia)
                ConnectionLog.connected(RouteMethod.WEBSOCKET, dcId, isMedia, 0L, "пул · via $host")
                relayOverWebSocket(RouteMethod.WEBSOCKET, dcId, isMedia, client, clientIn, clientOut, pooled, relayInit, crypto, splitter, fromPool = true)
                return true
            }
        }

        val connectTimeout = routePlanner.connectTimeoutMs(RouteMethod.WEBSOCKET, dcId, isMedia)
        for (domain in domains) {
            val connectStart = System.currentTimeMillis()
            val ws = try {
                RawWebSocket.connect(host, domain, connectTimeout)
            } catch (redirect: WebSocketHandshakeException) {
                if (!redirect.isRedirect) {
                    lastMessage = "WebSocket DC$dcId недоступен: ${redirect.statusLine}"
                }
                null
            } catch (timeout: SocketTimeoutException) {
                // Silence rather than a refusal is what address blocking looks like;
                // the other domain goes to the same address, so do not wait twice.
                routePlanner.markTargetTimedOut(host)
                ConnectionLog.info("WS адрес $host не ответил вовремя, пропускаем его на час")
                return false
            } catch (ignored: Exception) {
                null
            } ?: continue

            val connectMs = System.currentTimeMillis() - connectStart
            wsConnections.incrementAndGet()
            routePlanner.markConnected(RouteMethod.WEBSOCKET, dcId, isMedia, connectMs)
            ConnectionLog.connected(RouteMethod.WEBSOCKET, dcId, isMedia, connectMs, "$domain via $host")
            relayOverWebSocket(RouteMethod.WEBSOCKET, dcId, isMedia, client, clientIn, clientOut, ws, relayInit, crypto, splitter)
            return true
        }
        return false
    }

    /**
     * WebSocket to the same relay, but the TLS handshake presents the user's
     * fronting SNI while the Host header still names the Telegram relay (upstream
     * tg-ws-proxy's fronting fallback). One attempt; a failure rests the route.
     */
    private fun bridgeFronting(
        client: Socket,
        dcId: Int,
        isMedia: Boolean,
        clientIn: InputStream,
        clientOut: OutputStream,
        relayInit: ByteArray,
        crypto: CryptoContext,
        splitter: MtprotoPacketSplitter?
    ): Boolean {
        val host = wsConnectHost(dcId, options.dcIpMap) ?: return false
        val sni = options.frontingSni.trim().ifEmpty { return false }
        val domain = wsDomains(dcId, isMedia).first()
        val connectTimeout = routePlanner.connectTimeoutMs(RouteMethod.FRONTING, dcId, isMedia)
        val connectStart = System.currentTimeMillis()
        val ws = try {
            RawWebSocket.connect(host, domain, connectTimeout, sni = sni)
        } catch (ignored: Exception) {
            return false
        }
        val connectMs = System.currentTimeMillis() - connectStart
        frontingConnections.incrementAndGet()
        routePlanner.markConnected(RouteMethod.FRONTING, dcId, isMedia, connectMs)
        ConnectionLog.connected(RouteMethod.FRONTING, dcId, isMedia, connectMs, "$domain via $host, SNI $sni")
        relayOverWebSocket(RouteMethod.FRONTING, dcId, isMedia, client, clientIn, clientOut, ws, relayInit, crypto, splitter)
        return true
    }

    /** Sends the relay init, bridges the session and records how it went. */
    private fun relayOverWebSocket(
        method: RouteMethod,
        dcId: Int,
        isMedia: Boolean,
        client: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        ws: RawWebSocket,
        relayInit: ByteArray,
        crypto: CryptoContext,
        splitter: MtprotoPacketSplitter?,
        fromPool: Boolean = false
    ) {
        lastMessage = routePlanner.status()
        ws.sendBinary(relayInit)
        val sessionStart = System.currentTimeMillis()
        val received = bridgeClientAndWebSocket(client, clientIn, clientOut, ws, crypto, splitter)
        if (received == 0L) {
            if (fromPool) {
                // A pooled socket can go stale while it waits; that says nothing
                // about the route itself, so do not cool it down.
                ConnectionLog.info("${method.label} DC$dcId: соединение из пула устарело")
            } else {
                // The relay accepted us but never sent a byte: most likely a bad relay.
                // Cool it down so the next reconnect (Telegram retries fast) avoids it.
                routePlanner.markFailed(method, dcId, isMedia)
                ConnectionLog.noData(method, dcId, isMedia)
            }
        } else {
            ConnectionLog.sessionEnd(method, dcId, isMedia, System.currentTimeMillis() - sessionStart, received)
        }
    }

    private fun bridgeCfProxyFallback(
        client: Socket,
        dcId: Int,
        isMedia: Boolean,
        clientIn: InputStream,
        clientOut: OutputStream,
        relayInit: ByteArray,
        crypto: CryptoContext,
        splitter: MtprotoPacketSplitter?
    ): Boolean {
        val connectTimeout = routePlanner.connectTimeoutMs(RouteMethod.CLOUDFLARE, dcId, isMedia)
        // Cap the number of domains tried per connection. Without a cap a fully
        // blocked Cloudflare pool could burn through every domain before falling
        // back, which is exactly when media stalls for tens of seconds.
        for (baseDomain in cfBalancer.getDomainsForDc(dcId).take(MAX_CF_ATTEMPTS)) {
            val domain = "kws$dcId.$baseDomain"
            val connectStart = System.currentTimeMillis()
            val ws = try {
                RawWebSocket.connect(domain, domain, connectTimeout)
            } catch (ignored: Exception) {
                null
            } ?: continue

            val connectMs = System.currentTimeMillis() - connectStart
            cfBalancer.markActiveDomain(dcId, baseDomain)
            cfProxyConnections.incrementAndGet()
            routePlanner.markConnected(RouteMethod.CLOUDFLARE, dcId, isMedia, connectMs)
            ConnectionLog.connected(RouteMethod.CLOUDFLARE, dcId, isMedia, connectMs, domain)
            lastMessage = routePlanner.status()
            ws.sendBinary(relayInit)
            val sessionStart = System.currentTimeMillis()
            val received = bridgeClientAndWebSocket(client, clientIn, clientOut, ws, crypto, splitter)
            if (received == 0L) {
                routePlanner.markFailed(RouteMethod.CLOUDFLARE, dcId, isMedia)
                ConnectionLog.noData(RouteMethod.CLOUDFLARE, dcId, isMedia)
            } else {
                ConnectionLog.sessionEnd(
                    RouteMethod.CLOUDFLARE,
                    dcId,
                    isMedia,
                    System.currentTimeMillis() - sessionStart,
                    received
                )
            }
            return true
        }
        return false
    }

    /**
     * Relays through the user's own Cloudflare Worker. The worker is a raw
     * TCP-over-WebSocket tunnel to `dst:443` (`/apiws?dst=<dc-ip>&dc=<n>`), so the
     * obfuscated MTProto stream is sent continuously, exactly like the direct-TCP
     * fallback (no packet splitter) — but fronted by Cloudflare, which is far
     * harder to block. This mirrors upstream tg-ws-proxy's `_cfproxy_worker_fallback`.
     */
    private fun bridgeCfWorker(
        client: Socket,
        dcId: Int,
        isMedia: Boolean,
        clientIn: InputStream,
        clientOut: OutputStream,
        relayInit: ByteArray,
        crypto: CryptoContext
    ): Boolean {
        val dst = directIp(dcId) ?: return false
        val connectTimeout = routePlanner.connectTimeoutMs(RouteMethod.WORKER, dcId, isMedia)
        for (workerDomain in options.cfWorkerDomains.take(MAX_CF_ATTEMPTS)) {
            val path = "/apiws?dst=$dst&dc=$dcId"
            val connectStart = System.currentTimeMillis()
            val ws = try {
                RawWebSocket.connect(workerDomain, workerDomain, connectTimeout, path)
            } catch (ignored: Exception) {
                null
            } ?: continue

            val connectMs = System.currentTimeMillis() - connectStart
            workerConnections.incrementAndGet()
            routePlanner.markConnected(RouteMethod.WORKER, dcId, isMedia, connectMs)
            ConnectionLog.connected(RouteMethod.WORKER, dcId, isMedia, connectMs, workerDomain)
            lastMessage = routePlanner.status()
            ws.sendBinary(relayInit)
            val sessionStart = System.currentTimeMillis()
            val received = bridgeClientAndWebSocket(client, clientIn, clientOut, ws, crypto, splitter = null)
            if (received == 0L) {
                routePlanner.markFailed(RouteMethod.WORKER, dcId, isMedia)
                ConnectionLog.noData(RouteMethod.WORKER, dcId, isMedia)
            } else {
                ConnectionLog.sessionEnd(
                    RouteMethod.WORKER,
                    dcId,
                    isMedia,
                    System.currentTimeMillis() - sessionStart,
                    received
                )
            }
            return true
        }
        return false
    }

    private fun bridgeTcpFallback(
        client: Socket,
        dcId: Int,
        isMedia: Boolean,
        clientIn: InputStream,
        clientOut: OutputStream,
        relayInit: ByteArray,
        crypto: CryptoContext
    ): Boolean {
        val targetIp = directIp(dcId) ?: run {
            lastMessage = "DC$dcId не найден в карте Telegram"
            return false
        }
        Socket().use { remote ->
            remote.tcpNoDelay = true
            remote.keepAlive = true
            remote.receiveBufferSize = bufferSize
            remote.sendBufferSize = bufferSize
            val connectTimeout = routePlanner.connectTimeoutMs(RouteMethod.TCP, dcId, isMedia)
            val connectStart = System.currentTimeMillis()
            remote.connect(java.net.InetSocketAddress(targetIp, 443), connectTimeout)
            val connectMs = System.currentTimeMillis() - connectStart
            val remoteIn = BufferedInputStream(remote.getInputStream())
            val remoteOut = BufferedOutputStream(remote.getOutputStream())
            remoteOut.write(relayInit)
            remoteOut.flush()
            tcpFallbackConnections.incrementAndGet()
            routePlanner.markConnected(RouteMethod.TCP, dcId, isMedia, connectMs)
            ConnectionLog.connected(RouteMethod.TCP, dcId, isMedia, connectMs, targetIp)
            lastMessage = routePlanner.status()
            val sessionStart = System.currentTimeMillis()
            val received = bridgeClientAndTcp(client, remote, clientIn, clientOut, remoteIn, remoteOut, crypto)
            if (received == 0L) {
                routePlanner.markFailed(RouteMethod.TCP, dcId, isMedia)
                ConnectionLog.noData(RouteMethod.TCP, dcId, isMedia)
            } else {
                ConnectionLog.sessionEnd(
                    RouteMethod.TCP,
                    dcId,
                    isMedia,
                    System.currentTimeMillis() - sessionStart,
                    received
                )
            }
        }
        return true
    }

    /** Bridges the client and a WebSocket relay; returns the bytes received from the relay. */
    private fun bridgeClientAndWebSocket(
        client: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        ws: RawWebSocket,
        crypto: CryptoContext,
        splitter: MtprotoPacketSplitter?
    ): Long {
        val done = AtomicInteger(0)
        val sessionDown = AtomicLong(0)
        val down = try {
            executor.submit {
                try {
                    while (done.get() == 0) {
                        val data = ws.receive() ?: break
                        sessionDown.addAndGet(data.size.toLong())
                        bytesDown.addAndGet(data.size.toLong())
                        val plain = crypto.telegramDecrypt.update(data)
                        val encrypted = crypto.clientEncrypt.update(plain)
                        clientOut.write(encrypted)
                        clientOut.flush()
                    }
                } finally {
                    done.set(1)
                    runCatching { ws.close() }
                    runCatching { client.close() }
                }
            }
        } catch (rejected: RejectedExecutionException) {
            lastMessage = "Слишком много соединений: канал загрузки отклонён"
            runCatching { ws.close() }
            runCatching { client.close() }
            throw rejected
        }

        try {
            val buffer = ByteArray(bufferSize)
            try {
                while (done.get() == 0) {
                    val read = clientIn.read(buffer)
                    if (read < 0) break
                    bytesUp.addAndGet(read.toLong())
                    val plain = crypto.clientDecrypt.update(buffer, 0, read)
                    val encrypted = crypto.telegramEncrypt.update(plain)
                    val parts = splitter?.split(encrypted) ?: listOf(encrypted)
                    for (part in parts) {
                        if (part.isNotEmpty()) ws.sendBinary(part)
                    }
                }
                splitter?.flush()?.forEach { if (it.isNotEmpty()) ws.sendBinary(it) }
            } finally {
                done.set(1)
                runCatching { ws.close() }
                runCatching { client.close() }
            }
        } finally {
            runCatching { down.cancel(true) }
        }
        return sessionDown.get()
    }

    /** Bridges the client and a direct TCP relay; returns the bytes received from the relay. */
    private fun bridgeClientAndTcp(
        client: Socket,
        remote: Socket,
        clientIn: InputStream,
        clientOut: OutputStream,
        remoteIn: InputStream,
        remoteOut: OutputStream,
        crypto: CryptoContext
    ): Long {
        val done = AtomicInteger(0)
        val sessionDown = AtomicLong(0)
        val down = try {
            executor.submit {
                val buffer = ByteArray(bufferSize)
                try {
                    while (done.get() == 0) {
                        val read = remoteIn.read(buffer)
                        if (read < 0) break
                        sessionDown.addAndGet(read.toLong())
                        bytesDown.addAndGet(read.toLong())
                        val plain = crypto.telegramDecrypt.update(buffer, 0, read)
                        clientOut.write(crypto.clientEncrypt.update(plain))
                        clientOut.flush()
                    }
                } finally {
                    done.set(1)
                    runCatching { client.close() }
                    runCatching { remote.close() }
                }
            }
        } catch (rejected: RejectedExecutionException) {
            lastMessage = "Слишком много соединений: канал загрузки отклонён"
            runCatching { client.close() }
            runCatching { remote.close() }
            throw rejected
        }

        try {
            val buffer = ByteArray(bufferSize)
            try {
                while (done.get() == 0) {
                    val read = clientIn.read(buffer)
                    if (read < 0) break
                    bytesUp.addAndGet(read.toLong())
                    val plain = crypto.clientDecrypt.update(buffer, 0, read)
                    remoteOut.write(crypto.telegramEncrypt.update(plain))
                    remoteOut.flush()
                }
            } finally {
                done.set(1)
                runCatching { client.close() }
                runCatching { remote.close() }
            }
        } finally {
            runCatching { down.cancel(true) }
        }
        return sessionDown.get()
    }

    /** Direct address for a DC, used by the TCP fallback and as the worker's `dst`. */
    private fun directIp(dcId: Int): String? = options.dcIpMap[dcId] ?: defaultDcIps[dcId]

    private fun tryHandshake(handshake: ByteArray): ParsedHandshake? {
        val clientDecPrekeyIv = handshake.copyOfRange(SKIP_LEN, SKIP_LEN + PREKEY_LEN + IV_LEN)
        val clientDecPrekey = clientDecPrekeyIv.copyOfRange(0, PREKEY_LEN)
        val clientDecIv = clientDecPrekeyIv.copyOfRange(PREKEY_LEN, PREKEY_LEN + IV_LEN)
        val decrypt = aesCtr(sha256(clientDecPrekey + secretBytes), clientDecIv)
        val decrypted = decrypt.update(handshake)
        val protocolTag = decrypted.copyOfRange(PROTO_TAG_POS, PROTO_TAG_POS + 4)
        val protocolInt = littleEndianInt(protocolTag)
        if (protocolInt !in supportedProtocolTags) return null
        val dcRaw = littleEndianShort(decrypted, DC_IDX_POS)
        return ParsedHandshake(
            dcId = abs(dcRaw.toInt()),
            isMedia = dcRaw < 0,
            protocolTag = protocolTag,
            protocolInt = protocolInt,
            clientDecPrekeyIv = clientDecPrekeyIv
        )
    }

    private fun generateRelayInit(protocolTag: ByteArray, dcIndex: Int): ByteArray {
        val relay = ByteArray(HANDSHAKE_LEN)
        while (true) {
            random.nextBytes(relay)
            if ((relay[0].toInt() and 0xFF) in reservedFirstBytes) continue
            val start = relay.copyOfRange(0, 4)
            if (reservedStarts.any { it.contentEquals(start) }) continue
            if (relay.copyOfRange(4, 8).contentEquals(byteArrayOf(0, 0, 0, 0))) continue
            break
        }

        val encrypt = aesCtr(
            relay.copyOfRange(SKIP_LEN, SKIP_LEN + KEY_LEN),
            relay.copyOfRange(SKIP_LEN + KEY_LEN, SKIP_LEN + KEY_LEN + IV_LEN)
        )
        val encryptedFull = encrypt.update(relay)
        val tailPlain = protocolTag + shortToLittleEndian(dcIndex.toShort()) + ByteArray(2).also(random::nextBytes)
        for (i in 0 until 8) {
            val sourceIndex = PROTO_TAG_POS + i
            val keystreamByte = encryptedFull[sourceIndex] xor relay[sourceIndex]
            relay[sourceIndex] = tailPlain[i] xor keystreamByte
        }
        return relay
    }

    private fun buildCryptoContext(clientDecPrekeyIv: ByteArray, relayInit: ByteArray): CryptoContext {
        val clientDecPrekey = clientDecPrekeyIv.copyOfRange(0, PREKEY_LEN)
        val clientDecIv = clientDecPrekeyIv.copyOfRange(PREKEY_LEN, PREKEY_LEN + IV_LEN)
        val clientDecrypt = aesCtr(sha256(clientDecPrekey + secretBytes), clientDecIv)

        val clientEncPrekeyIv = clientDecPrekeyIv.reversedArray()
        val clientEncrypt = aesCtr(
            sha256(clientEncPrekeyIv.copyOfRange(0, PREKEY_LEN) + secretBytes),
            clientEncPrekeyIv.copyOfRange(PREKEY_LEN, PREKEY_LEN + IV_LEN)
        )

        val telegramEncrypt = aesCtr(
            relayInit.copyOfRange(SKIP_LEN, SKIP_LEN + KEY_LEN),
            relayInit.copyOfRange(SKIP_LEN + KEY_LEN, SKIP_LEN + KEY_LEN + IV_LEN)
        )

        val relayDecPrekeyIv = relayInit.copyOfRange(SKIP_LEN, SKIP_LEN + KEY_LEN + IV_LEN).reversedArray()
        val telegramDecrypt = aesCtr(
            relayDecPrekeyIv.copyOfRange(0, KEY_LEN),
            relayDecPrekeyIv.copyOfRange(KEY_LEN, KEY_LEN + IV_LEN)
        )

        clientDecrypt.update(ZERO_64)
        telegramEncrypt.update(ZERO_64)
        return CryptoContext(clientDecrypt, clientEncrypt, telegramEncrypt, telegramDecrypt)
    }

    private fun aesCtr(key: ByteArray, iv: ByteArray): Cipher {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher
    }

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    private val secretBytes: ByteArray by lazy { options.secretHex.hexToBytes() }

    private data class ParsedHandshake(
        val dcId: Int,
        val isMedia: Boolean,
        val protocolTag: ByteArray,
        val protocolInt: Int,
        val clientDecPrekeyIv: ByteArray
    )

    private data class CryptoContext(
        val clientDecrypt: Cipher,
        val clientEncrypt: Cipher,
        val telegramEncrypt: Cipher,
        val telegramDecrypt: Cipher
    )

    companion object {
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 1443
        private const val HANDSHAKE_LEN = 64
        private const val SKIP_LEN = 8
        private const val PREKEY_LEN = 32
        private const val KEY_LEN = 32
        private const val IV_LEN = 16
        private const val PROTO_TAG_POS = 56
        private const val DC_IDX_POS = 60
        private const val PROTO_ABRIDGED_INT = MtprotoPacketSplitter.PROTO_ABRIDGED_INT
        private const val PROTO_INTERMEDIATE_INT = MtprotoPacketSplitter.PROTO_INTERMEDIATE_INT
        private const val PROTO_PADDED_INTERMEDIATE_INT = MtprotoPacketSplitter.PROTO_PADDED_INTERMEDIATE_INT
        private val ZERO_64 = ByteArray(64)
        private val reservedFirstBytes = setOf(0xEF)
        private val reservedStarts = listOf(
            "HEAD".toByteArray(Charsets.US_ASCII),
            "POST".toByteArray(Charsets.US_ASCII),
            "GET ".toByteArray(Charsets.US_ASCII),
            byteArrayOf(0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte(), 0xEE.toByte()),
            byteArrayOf(0xDD.toByte(), 0xDD.toByte(), 0xDD.toByte(), 0xDD.toByte()),
            byteArrayOf(0x16, 0x03, 0x01, 0x02)
        )
        private val supportedProtocolTags = setOf(
            PROTO_ABRIDGED_INT,
            PROTO_INTERMEDIATE_INT,
            PROTO_PADDED_INTERMEDIATE_INT
        )
        private val dcRedirects = mapOf(
            2 to "149.154.167.220",
            4 to "149.154.167.220"
        )
        private const val MAX_CF_ATTEMPTS = 3
        private const val RECENT_EVENTS_LIMIT = 8
        private val defaultDcIps = mapOf(
            1 to "149.154.175.50",
            2 to "149.154.167.51",
            3 to "149.154.175.100",
            4 to "149.154.167.91",
            5 to "149.154.171.5",
            203 to "91.105.192.100"
        )


        private fun namedDaemonThreads(prefix: String): ThreadFactory {
            val threadNumber = AtomicInteger(1)
            return ThreadFactory { task ->
                Thread(task, "$prefix-${threadNumber.getAndIncrement()}").apply {
                    isDaemon = true
                }
            }
        }

        private fun newProxyExecutor(maxThreads: Int): ExecutorService {
            return ThreadPoolExecutor(
                0,
                maxThreads,
                30L,
                TimeUnit.SECONDS,
                SynchronousQueue<Runnable>(),
                namedDaemonThreads("tg-proxy"),
                ThreadPoolExecutor.AbortPolicy()
            ).apply {
                allowCoreThreadTimeOut(true)
            }
        }

        fun generateSecret(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }

        /**
         * Host to open the WebSocket relay against, or null when this data centre
         * has no WebSocket relay. A user override wins, then the built-in redirect
         * map.
         *
         * There is deliberately no fallback to the relay hostname: only the DCs in
         * the redirect map are served by Telegram's web WebSocket relay (kws2 and
         * kws4 resolve to the same endpoint, which is why upstream maps just those
         * two). Dialling kwsN by name for other DCs reached something that completed
         * the WebSocket handshake but never relayed a usable MTProto stream, and the
         * client then reported the proxy as misconfigured. Resolving by name also
         * routes through DNS, which is exactly what a censoring network controls.
         */
        fun wsConnectHost(dcId: Int, overrides: Map<Int, String>): String? =
            overrides[dcId] ?: dcRedirects[dcId]

        private fun wsDomains(dcId: Int, media: Boolean): List<String> {
            val dc = webDcId(dcId)
            return if (media) {
                listOf("kws$dc-1.web.telegram.org", "kws$dc.web.telegram.org")
            } else {
                listOf("kws$dc.web.telegram.org", "kws$dc-1.web.telegram.org")
            }
        }

        private fun webDcId(dcId: Int): Int = if (dcId == 203) 2 else dcId

        private fun littleEndianShort(data: ByteArray, offset: Int): Short {
            return (((data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8))).toShort()
        }

        private fun littleEndianInt(data: ByteArray): Int {
            return (data[0].toInt() and 0xFF) or
                ((data[1].toInt() and 0xFF) shl 8) or
                ((data[2].toInt() and 0xFF) shl 16) or
                ((data[3].toInt() and 0xFF) shl 24)
        }

        private fun shortToLittleEndian(value: Short): ByteArray {
            val intValue = value.toInt()
            return byteArrayOf((intValue and 0xFF).toByte(), ((intValue shr 8) and 0xFF).toByte())
        }

        private fun String.hexToBytes(): ByteArray {
            require(length % 2 == 0) { "Hex string must have even length" }
            return ByteArray(length / 2) { index ->
                substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }

    }
}
