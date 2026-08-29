package com.flowseal.tgwsproxy.proxy

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
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
    private val badConnections = AtomicInteger(0)
    private val maskedConnections = AtomicInteger(0)
    private val bytesUp = AtomicLong(0)
    private val bytesDown = AtomicLong(0)

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var lastMessage: String = "Прокси остановлен"

    val proxyLink: String
        get() = if (options.fakeTlsEnabled) fakeTlsProxyLink else plainProxyLink

    val plainProxyLink: String
        get() = "tg://proxy?server=${options.host}&port=${options.port}&secret=dd${options.secretHex}"

    private val fakeTlsProxyLink: String
        get() {
            val domainHex = options.fakeTlsDomain.toByteArray(Charsets.US_ASCII)
                .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            return "tg://proxy?server=${options.host}&port=${options.port}&secret=ee${options.secretHex}$domainHex"
        }

    val isRunning: Boolean
        get() = serverSocket?.isClosed == false

    fun hasOptions(newOptions: ProxyOptions): Boolean = options == newOptions

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
        badConnections = badConnections.get(),
        maskedConnections = maskedConnections.get(),
        bytesUp = bytesUp.get(),
        bytesDown = bytesDown.get(),
        proxyLink = proxyLink,
        plainProxyLink = plainProxyLink,
        fakeTlsEnabled = options.fakeTlsEnabled,
        cfProxyEnabled = options.cfProxyEnabled,
        routeStatus = routePlanner.status(),
        routePolicy = routePlanner.policyLabel(options),
        routeHealth = routePlanner.healthSummary(),
        recentEvents = ConnectionLog.recent(RECENT_EVENTS_LIMIT),
        networkMode = if (options.cellularNetwork) "Мобильная сеть" else "Wi-Fi или локальная сеть",
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
        val methods = routePlanner.methodsFor(dcId, isMedia, options)
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
        val targetIp = wsTargetIp(dcId) ?: return false
        val connectTimeout = routePlanner.connectTimeoutMs(RouteMethod.WEBSOCKET, dcId, isMedia)
        for (domain in wsDomains(dcId, isMedia)) {
            val connectStart = System.currentTimeMillis()
            val ws = try {
                RawWebSocket.connect(targetIp, domain, connectTimeout)
            } catch (redirect: WebSocketHandshakeException) {
                if (!redirect.isRedirect) {
                    lastMessage = "WebSocket DC$dcId недоступен: ${redirect.statusLine}"
                }
                null
            } catch (ignored: Exception) {
                null
            } ?: continue

            val connectMs = System.currentTimeMillis() - connectStart
            wsConnections.incrementAndGet()
            routePlanner.markConnected(RouteMethod.WEBSOCKET, dcId, isMedia, connectMs)
            ConnectionLog.connected(RouteMethod.WEBSOCKET, dcId, isMedia, connectMs, domain)
            lastMessage = routePlanner.status()
            ws.sendBinary(relayInit)
            val sessionStart = System.currentTimeMillis()
            val received = bridgeClientAndWebSocket(client, clientIn, clientOut, ws, crypto, splitter)
            // The relay accepted us but never sent a byte: most likely a bad worker.
            // Cool it down so the next reconnect (Telegram retries fast) avoids it.
            if (received == 0L) {
                routePlanner.markFailed(RouteMethod.WEBSOCKET, dcId, isMedia)
                ConnectionLog.noData(RouteMethod.WEBSOCKET, dcId, isMedia)
            } else {
                ConnectionLog.sessionEnd(
                    RouteMethod.WEBSOCKET,
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

    /** WebSocket relay address for a DC; a user override wins over the built-in map. */
    private fun wsTargetIp(dcId: Int): String? = options.dcIpMap[dcId] ?: dcRedirects[dcId]

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

    private inner class MtprotoPacketSplitter(relayInit: ByteArray, private val protocol: Int) {
        private val decrypt = aesCtr(
            relayInit.copyOfRange(SKIP_LEN, SKIP_LEN + KEY_LEN),
            relayInit.copyOfRange(SKIP_LEN + KEY_LEN, SKIP_LEN + KEY_LEN + IV_LEN)
        )
        private var cipherBuffer = ByteArray(0)
        private var plainBuffer = ByteArray(0)
        private var disabled = false

        init {
            decrypt.update(ZERO_64)
        }

        fun split(chunk: ByteArray): List<ByteArray> {
            if (chunk.isEmpty()) return emptyList()
            if (disabled) return listOf(chunk)

            val plain = decrypt.update(chunk)
            cipherBuffer = cipherBuffer.append(chunk)
            plainBuffer = plainBuffer.append(plain)

            val parts = mutableListOf<ByteArray>()
            while (cipherBuffer.isNotEmpty()) {
                val packetLength = nextPacketLength() ?: break
                if (packetLength <= 0 || packetLength > MAX_MTPROTO_PACKET_SIZE) {
                    parts += cipherBuffer
                    clearBuffers()
                    disabled = true
                    break
                }
                parts += cipherBuffer.copyOfRange(0, packetLength)
                consume(packetLength)
            }
            return parts
        }

        fun flush(): List<ByteArray> {
            if (cipherBuffer.isEmpty()) return emptyList()
            val tail = cipherBuffer
            clearBuffers()
            return listOf(tail)
        }

        private fun consume(length: Int) {
            cipherBuffer = cipherBuffer.copyOfRange(length, cipherBuffer.size)
            plainBuffer = plainBuffer.copyOfRange(length, plainBuffer.size)
        }

        private fun clearBuffers() {
            cipherBuffer = ByteArray(0)
            plainBuffer = ByteArray(0)
        }

        private fun nextPacketLength(): Int? {
            if (plainBuffer.isEmpty()) return null
            return when (protocol) {
                PROTO_ABRIDGED_INT -> nextAbridgedLength()
                PROTO_INTERMEDIATE_INT, PROTO_PADDED_INTERMEDIATE_INT -> nextIntermediateLength()
                else -> 0
            }
        }

        private fun nextAbridgedLength(): Int? {
            val first = plainBuffer[0].toInt() and 0xFF
            val headerLength: Int
            val payloadLength: Int
            if (first == 0x7F || first == 0xFF) {
                if (plainBuffer.size < 4) return null
                payloadLength = (
                    (plainBuffer[1].toInt() and 0xFF) or
                        ((plainBuffer[2].toInt() and 0xFF) shl 8) or
                        ((plainBuffer[3].toInt() and 0xFF) shl 16)
                    ) * 4
                headerLength = 4
            } else {
                payloadLength = (first and 0x7F) * 4
                headerLength = 1
            }
            if (payloadLength <= 0 || payloadLength > MAX_MTPROTO_PACKET_SIZE) return 0
            val packetLength = headerLength + payloadLength
            return if (plainBuffer.size >= packetLength) packetLength else null
        }

        private fun nextIntermediateLength(): Int? {
            if (plainBuffer.size < 4) return null
            val payloadLength = (
                (plainBuffer[0].toInt() and 0xFF) or
                    ((plainBuffer[1].toInt() and 0xFF) shl 8) or
                    ((plainBuffer[2].toInt() and 0xFF) shl 16) or
                    ((plainBuffer[3].toInt() and 0x7F) shl 24)
                )
            if (payloadLength <= 0 || payloadLength > MAX_MTPROTO_PACKET_SIZE) return 0
            val packetLength = 4 + payloadLength
            return if (plainBuffer.size >= packetLength) packetLength else null
        }

        private fun ByteArray.append(chunk: ByteArray): ByteArray {
            if (isEmpty()) return chunk.copyOf()
            val combined = copyOf(size + chunk.size)
            System.arraycopy(chunk, 0, combined, size, chunk.size)
            return combined
        }
    }

    companion object {
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 1443
        private const val MAX_MTPROTO_PACKET_SIZE = 8 * 1024 * 1024
        private const val HANDSHAKE_LEN = 64
        private const val SKIP_LEN = 8
        private const val PREKEY_LEN = 32
        private const val KEY_LEN = 32
        private const val IV_LEN = 16
        private const val PROTO_TAG_POS = 56
        private const val DC_IDX_POS = 60
        private const val PROTO_ABRIDGED_INT = -269488145
        private const val PROTO_INTERMEDIATE_INT = -286331154
        private const val PROTO_PADDED_INTERMEDIATE_INT = -572662307
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


        private fun newProxyExecutor(maxThreads: Int): ExecutorService {
            val threadNumber = AtomicInteger(1)
            val factory = ThreadFactory { task ->
                Thread(task, "tg-proxy-${threadNumber.getAndIncrement()}").apply {
                    isDaemon = true
                }
            }
            return ThreadPoolExecutor(
                0,
                maxThreads,
                30L,
                TimeUnit.SECONDS,
                SynchronousQueue<Runnable>(),
                factory,
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

        private fun List<Byte>.toByteArray(): ByteArray = ByteArray(size) { this[it] }
    }
}
