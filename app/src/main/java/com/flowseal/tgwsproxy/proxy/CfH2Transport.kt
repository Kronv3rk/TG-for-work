package com.flowseal.tgwsproxy.proxy

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink

/**
 * Cloudflare /api HTTP/2 transport for individual native MTProto packets.
 *
 * OkHttp negotiates TLS/ALPN and multiplexes requests to a shared origin
 * connection. The channel applies per-channel and per-origin limits, retains
 * a small replay window for unanswered encrypted packets, and suppresses
 * duplicate replies if both the original and replay complete.
 *
 * This remains an opt-in transport. WebSocket and TCP are the normal fallback.
 */
internal class CfH2Transport(
    private val client: OkHttpClient = defaultClient()
) : Closeable {
    private val dispatcher = client.dispatcher

    internal class Lane {
        val requests = Semaphore(MAX_LANE_REQUESTS)
        val lock = Any()
        var pendingBytes = 0
            private set
        private var replyBytes = 0

        fun reservePending(bytes: Int): Boolean = synchronized(lock) {
            if (pendingBytes + bytes > MAX_LANE_BYTES) return@synchronized false
            pendingBytes += bytes
            true
        }

        fun releasePending(bytes: Int) = synchronized(lock) {
            pendingBytes = (pendingBytes - bytes).coerceAtLeast(0)
        }

        fun reserveReply(bytes: Int): Boolean = synchronized(lock) {
            if (replyBytes + bytes > MAX_LANE_REPLY_BYTES) return@synchronized false
            replyBytes += bytes
            true
        }

        fun releaseReply(bytes: Int) = synchronized(lock) {
            replyBytes = (replyBytes - bytes).coerceAtLeast(0)
        }

        internal val queuedReplyBytes: Int get() = synchronized(lock) { replyBytes }
    }

    private data class PendingCall(
        val bytes: Int,
        val ticket: H2ReplayWindow.Ticket?
    )

    private data class QueuedReply(
        val body: ByteArray,
        val reservedBytes: Int
    )

    private data class ReadReply(
        val body: ByteArray,
        val reservedBytes: Int
    )

    private class H2TransportError(
        val nativeCode: Int,
        message: String
    ) : IOException(message)

    private val lanes = ConcurrentHashMap<String, Lane>()
    private val failedUntil = ConcurrentHashMap<String, Long>()
    private val channels = ConcurrentHashMap.newKeySet<Channel>()
    private val closed = AtomicBoolean(false)

    fun open(hosts: List<String>): Channel? {
        if (closed.get()) return null
        for (host in hosts.take(MAX_CONNECT_ATTEMPTS)) {
            if (System.currentTimeMillis() < (failedUntil[host] ?: 0L)) continue
            val preflight = runCatching {
                val request = Request.Builder().url("https://$host/api").head().build()
                client.newCall(request).also {
                    it.timeout().timeout(SETUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }.execute().use { response ->
                    H2MediaPolicy.isPreflightValid(
                        if (response.protocol == Protocol.HTTP_2) "h2" else "http/1.1",
                        response.code
                    )
                }
            }.getOrDefault(false)
            if (!preflight) {
                failedUntil[host] = System.currentTimeMillis() + DOMAIN_COOLDOWN_MS
                continue
            }
            val lane = lanes.computeIfAbsent(host) { Lane() }
            return Channel(host, lane).also { channels.add(it) }
        }
        return null
    }

    /** Prevent old TLS sockets and pending responses crossing a network handover. */
    fun resetNetwork() {
        for (channel in channels.toList()) channel.close()
        failedUntil.clear()
        lanes.clear()
        client.connectionPool.evictAll()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        resetNetwork()
        dispatcher.cancelAll()
        dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    inner class Channel internal constructor(
        private val host: String,
        private val lane: Lane
    ) : Closeable {
        private val closed = AtomicBoolean(false)
        private val failure = AtomicReference<IOException?>(null)
        private val transportError = AtomicInteger(0)
        private val transportErrorDelivered = AtomicBoolean(false)
        private val requests = Semaphore(MAX_CHANNEL_REQUESTS)
        private val pendingCalls = ConcurrentHashMap<Call, PendingCall>()
        private val pending = AtomicInteger(0)
        private val queuedReplies = ArrayBlockingQueue<QueuedReply>(MAX_CHANNEL_REQUESTS)
        private val replyLock = Any()
        private val queuedBytes = AtomicInteger(0)
        private val pendingBytes = AtomicInteger(0)
        private val lastProgress = AtomicLong(System.currentTimeMillis())
        private val replayWindow = H2ReplayWindow()

        val failed: Boolean
            get() = failure.get() != null || transportError.get() != 0

        internal val queuedReplyCount: Int
            get() = queuedReplies.size

        @Throws(IOException::class)
        fun send(body: ByteArray) {
            if (closed.get()) throw IOException("H2 channel already closed")
            if (body.isEmpty() || body.size > H2MediaPolicy.MAX_REPLY_BYTES) {
                throw IOException("Invalid H2 upload packet")
            }
            val entry = replayWindow.remember(body)
            val ticket = entry?.let(replayWindow::registerOriginal)
            enqueue(body, ticket, replay = false, waitForCapacity = true)
        }

        /**
         * Schedules one packet. Replays never wait for capacity: recovery must
         * not block native uploads or occupy a channel while the lane is full.
         */
        private fun enqueue(
            body: ByteArray,
            ticket: H2ReplayWindow.Ticket?,
            replay: Boolean,
            waitForCapacity: Boolean
        ): Boolean {
            if (closed.get()) {
                releaseTicket(ticket, replay)
                if (waitForCapacity) throw IOException("H2 channel already closed")
                return false
            }
            if (body.isEmpty() || body.size > H2MediaPolicy.MAX_REPLY_BYTES) {
                releaseTicket(ticket, replay)
                throw IOException("Invalid H2 upload packet")
            }

            val channelAcquired = if (waitForCapacity) {
                requests.tryAcquire(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } else requests.tryAcquire()
            if (!channelAcquired) {
                releaseTicket(ticket, replay)
                if (waitForCapacity) throw SocketTimeoutException("H2 channel request budget exhausted")
                return false
            }

            val laneAcquired = if (waitForCapacity) {
                lane.requests.tryAcquire(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } else lane.requests.tryAcquire()
            if (!laneAcquired) {
                requests.release()
                releaseTicket(ticket, replay)
                if (waitForCapacity) throw SocketTimeoutException("H2 lane request budget exhausted")
                return false
            }

            val reserved = lane.reservePending(body.size)
            val channelReserved = reserved && reserveChannelPending(body.size)
            if (closed.get() || !reserved || !channelReserved) {
                if (channelReserved) pendingBytes.addAndGet(-body.size)
                if (reserved) lane.releasePending(body.size)
                requests.release()
                lane.requests.release()
                releaseTicket(ticket, replay)
                if (waitForCapacity) throw IOException("H2 pending byte budget exceeded")
                return false
            }
            pending.incrementAndGet()
            lastProgress.set(System.currentTimeMillis())
            var call: Call? = null
            try {
                val requestBody = object : RequestBody() {
                    override fun contentType() = "application/octet-stream".toMediaType()
                    override fun contentLength(): Long = body.size.toLong()
                    override fun writeTo(sink: BufferedSink) {
                        sink.write(body)
                        if (ticket != null) replayWindow.markSent(ticket)
                    }
                }
                val request = Request.Builder()
                    .url("https://$host/api")
                    .header("Accept-Encoding", "identity")
                    .post(requestBody)
                    .build()
                call = client.newCall(request)
                pendingCalls[call] = PendingCall(body.size, ticket)
                if (closed.get()) {
                    call.cancel()
                    complete(call)
                    if (waitForCapacity) throw IOException("H2 channel closed while scheduling request")
                    return false
                }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, error: IOException) {
                        fail(error)
                        complete(call)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (ticket != null) replayWindow.markReceiving(ticket)
                        try {
                            response.use {
                                val result = readBoundedReply(response, lane) {
                                    lastProgress.set(System.currentTimeMillis())
                                    if (ticket != null) replayWindow.markProgress(ticket)
                                }
                                var queued = false
                                if (result.body.isNotEmpty() && replayWindow.acceptReply(ticket, result.body)) {
                                    synchronized(replyLock) {
                                        if (!closed.get()) {
                                            val after = queuedBytes.addAndGet(result.body.size)
                                            if (after <= MAX_CHANNEL_BYTES &&
                                                queuedReplies.offer(QueuedReply(result.body, result.reservedBytes))
                                            ) {
                                                queued = true
                                                lastProgress.set(System.currentTimeMillis())
                                            } else {
                                                queuedBytes.addAndGet(-result.body.size)
                                            }
                                        }
                                    }
                                }
                                if (!queued && result.reservedBytes > 0) {
                                    lane.releaseReply(result.reservedBytes)
                                }
                            }
                        } catch (error: H2TransportError) {
                            fail(error)
                        } catch (error: IOException) {
                            fail(error)
                        } catch (error: Exception) {
                            fail(IOException("Invalid H2 transport response", error))
                        } finally {
                            complete(call)
                        }
                    }
                })
            } catch (error: Exception) {
                if (call == null) {
                    releaseUntracked(body.size, ticket, replay)
                } else {
                    complete(call)
                }
                throw IOException("Cannot schedule H2 request", error)
            }
            return true
        }

        @Throws(IOException::class)
        fun receive(): ByteArray? {
            while (true) {
                val nativeError = transportError.get()
                if (nativeError != 0 && transportErrorDelivered.compareAndSet(false, true)) {
                    return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(nativeError).array()
                }
                failure.get()?.let { throw it }

                val reply = try {
                    queuedReplies.poll(REPLAY_CHECK_MS, TimeUnit.MILLISECONDS)
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("H2 response read interrupted", error)
                }
                if (reply != null) {
                    queuedBytes.addAndGet(-reply.body.size)
                    lane.releaseReply(reply.reservedBytes)
                    return reply.body
                }
                if (closed.get()) {
                    if (transportError.get() != 0 && !transportErrorDelivered.get()) continue
                    failure.get()?.let { throw it }
                    return null
                }
                if (pending.get() > 0 &&
                    System.currentTimeMillis() - lastProgress.get() >= REQUEST_TIMEOUT_SECONDS * 1_000L
                ) {
                    val error = SocketTimeoutException("H2 media response stalled")
                    fail(error)
                    failure.get()?.let { throw it }
                }
                dispatchReplayIfDue()
            }
        }

        private fun dispatchReplayIfDue() {
            if (closed.get()) return
            val entry = replayWindow.nextReplayCandidate() ?: return
            val ticket = replayWindow.reserveReplay(entry) ?: return
            runCatching {
                enqueue(replayWindow.body(entry), ticket, replay = true, waitForCapacity = false)
            }.onFailure { error ->
                replayWindow.cancelReplay(ticket)
                fail(if (error is IOException) error else IOException("H2 replay scheduling failed", error))
            }
        }

        private fun reserveChannelPending(bytes: Int): Boolean {
            while (true) {
                val current = pendingBytes.get()
                if (current + bytes > MAX_CHANNEL_BYTES) return false
                if (pendingBytes.compareAndSet(current, current + bytes)) return true
            }
        }

        private fun complete(call: Call) {
            val record = pendingCalls.remove(call) ?: return
            record.ticket?.let(replayWindow::complete)
            releaseBudget(record.bytes)
        }

        private fun releaseUntracked(
            bytes: Int,
            ticket: H2ReplayWindow.Ticket?,
            replay: Boolean
        ) {
            releaseTicket(ticket, replay)
            releaseBudget(bytes)
        }

        private fun releaseTicket(ticket: H2ReplayWindow.Ticket?, replay: Boolean) {
            if (ticket == null) return
            if (replay) replayWindow.cancelReplay(ticket) else replayWindow.complete(ticket)
        }

        private fun releaseBudget(bytes: Int) {
            pending.decrementAndGet()
            pendingBytes.addAndGet(-bytes)
            lane.releasePending(bytes)
            lane.requests.release()
            requests.release()
        }

        private fun fail(error: IOException) {
            if (closed.get()) return
            if (error is H2TransportError) {
                transportError.compareAndSet(0, error.nativeCode)
            } else {
                failure.compareAndSet(null, error)
                failedUntil[host] = System.currentTimeMillis() + DOMAIN_COOLDOWN_MS
            }
            close()
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            for ((call, _) in pendingCalls.toMap()) {
                call.cancel()
                complete(call)
            }
            synchronized(replyLock) {
                while (true) {
                    val reply = queuedReplies.poll() ?: break
                    queuedBytes.addAndGet(-reply.body.size)
                    lane.releaseReply(reply.reservedBytes)
                }
            }
            replayWindow.clear()
            channels.remove(this)
        }
    }

    private fun readBoundedReply(
        response: Response,
        lane: Lane,
        onProgress: () -> Unit
    ): ReadReply {
        val protocol = if (response.protocol == Protocol.HTTP_2) "h2" else "http/1.1"
        if (protocol != "h2") throw IOException("Cloudflare HTTP/2 negotiation failed")
        if (response.code in NATIVE_HTTP_ERROR_CODES) {
            throw H2TransportError(-response.code, "Cloudflare /api HTTP ${response.code}")
        }
        if (response.code != 200) throw IOException("Cloudflare /api returned HTTP ${response.code}")
        val type = response.header("Content-Type")
        if (type?.contains("text/html", ignoreCase = true) == true) {
            throw IOException("Cloudflare /api returned HTML instead of MTProto")
        }
        val declared = response.body?.contentLength() ?: -1L
        if (declared > H2MediaPolicy.MAX_REPLY_BYTES) {
            throw IOException("H2 response exceeds size limit")
        }
        val stream = response.body?.byteStream() ?: return ReadReply(ByteArray(0), 0)
        val result = ByteArrayOutputStream(
            if (declared in 1..65_536) declared.toInt() else 8192
        )
        var reserved = 0
        try {
            stream.use {
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = it.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    if (result.size() + count > H2MediaPolicy.MAX_REPLY_BYTES) {
                        throw IOException("H2 response exceeds size limit")
                    }
                    if (!lane.reserveReply(count)) {
                        throw IOException("H2 lane reply memory budget exceeded")
                    }
                    reserved += count
                    result.write(buffer, 0, count)
                    onProgress()
                }
            }
            val bytes = result.toByteArray()
            if (declared >= 0 && bytes.size.toLong() != declared) {
                throw IOException("Truncated H2 response body")
            }
            if (bytes.isEmpty()) return ReadReply(bytes, reserved)
            H2MediaPolicy.validateReply(protocol, response.code, type, bytes)
            if (bytes.size == 4) {
                val code = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int
                if (code < 0) throw H2TransportError(code, "Cloudflare /api MTProto error")
            }
            return ReadReply(bytes, reserved)
        } catch (error: Exception) {
            if (reserved > 0) lane.releaseReply(reserved)
            throw error
        }
    }

    private companion object {
        fun defaultClient(): OkHttpClient {
            val dispatcher = Dispatcher().apply {
                maxRequests = MAX_LANE_REQUESTS
                maxRequestsPerHost = MAX_LANE_REQUESTS
            }
            return OkHttpClient.Builder()
                .dispatcher(dispatcher)
                .connectionPool(ConnectionPool(8, 3, TimeUnit.MINUTES))
                .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .callTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                // Never forward native MTProto POST bodies to redirect targets.
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
        }

        const val MAX_CONNECT_ATTEMPTS = 3
        const val MAX_CHANNEL_REQUESTS = 8
        const val MAX_LANE_REQUESTS = 64
        const val MAX_CHANNEL_BYTES = 8 * 1024 * 1024
        const val MAX_LANE_BYTES = 32 * 1024 * 1024
        const val MAX_LANE_REPLY_BYTES = 32 * 1024 * 1024
        const val DOMAIN_COOLDOWN_MS = 30_000L
        const val REQUEST_TIMEOUT_SECONDS = 65L
        const val SETUP_TIMEOUT_SECONDS = 8L
        const val REPLAY_CHECK_MS = 250L
        val NATIVE_HTTP_ERROR_CODES = setOf(403, 404, 429, 444)
    }
}
