package com.flowseal.tgwsproxy.proxy

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.SocketTimeoutException
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
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Experimental Android Cloudflare /api HTTP/2 media transport.
 *
 * POST /api carries exactly one native (decrypted) MTProto packet. OkHttp shares
 * TLS/ALPN HTTP/2 connections and multiplexes calls to the same origin. All
 * replies are bounded, independently of peer-supplied Content-Length.
 *
 * H2 remains off by default until real-device replay and resilience testing is
 * complete. WS remains the default and also the fallback after H2 failures.
 */
internal class CfH2Transport : Closeable {
    private val dispatcher = Dispatcher().apply {
        maxRequests = MAX_LANE_REQUESTS
        maxRequestsPerHost = MAX_LANE_REQUESTS
    }
    private val client = OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .connectionPool(ConnectionPool(8, 3, TimeUnit.MINUTES))
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    internal class Lane {
        val requests = Semaphore(MAX_LANE_REQUESTS)
        val lock = Any()
        var pendingBytes = 0
    }

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
                client.newCall(request).execute().use { response ->
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

    /** Prevent old TCP/TLS sockets from crossing a network handover. */
    fun resetNetwork() {
        for (channel in channels.toList()) channel.close()
        failedUntil.clear()
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
        private val requests = Semaphore(MAX_CHANNEL_REQUESTS)
        private val pendingCalls = ConcurrentHashMap.newKeySet<Call>()
        private val pending = AtomicInteger(0)
        private val queuedReplies = ArrayBlockingQueue<ByteArray>(MAX_CHANNEL_REQUESTS)
        private val queuedBytes = AtomicInteger(0)
        private val pendingBytes = AtomicInteger(0)
        private val lastProgress = AtomicLong(System.currentTimeMillis())

        @Throws(IOException::class)
        fun send(body: ByteArray) {
            if (closed.get()) throw IOException("H2 channel already closed")
            if (body.isEmpty() || body.size > H2MediaPolicy.MAX_REPLY_BYTES) {
                throw IOException("Invalid H2 upload packet")
            }
            if (!requests.tryAcquire(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw SocketTimeoutException("H2 channel request budget exhausted")
            }
            if (!lane.requests.tryAcquire(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                requests.release()
                throw SocketTimeoutException("H2 lane request budget exhausted")
            }
            synchronized(lane.lock) {
                if (closed.get() || pendingBytes.get() + body.size > MAX_CHANNEL_BYTES ||
                    lane.pendingBytes + body.size > MAX_LANE_BYTES
                ) {
                    requests.release()
                    lane.requests.release()
                    throw IOException("H2 pending byte budget exceeded")
                }
                pendingBytes.addAndGet(body.size)
                lane.pendingBytes += body.size
            }
            pending.incrementAndGet()
            lastProgress.set(System.currentTimeMillis())
            val request = Request.Builder()
                .url("https://$host/api")
                .header("Accept-Encoding", "identity")
                .post(body.toRequestBody("application/octet-stream".toMediaType()))
                .build()
            val call = client.newCall(request)
            pendingCalls.add(call)
            try {
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, error: IOException) {
                        fail(error)
                        complete(call, body.size)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            response.use {
                                val reply = readBoundedReply(response) {
                                    lastProgress.set(System.currentTimeMillis())
                                }
                                if (!closed.get()) {
                                    val after = queuedBytes.addAndGet(reply.size)
                                    if (after > MAX_CHANNEL_BYTES || !queuedReplies.offer(reply)) {
                                        queuedBytes.addAndGet(-reply.size)
                                        throw IOException("H2 reply queue budget exceeded")
                                    }
                                    lastProgress.set(System.currentTimeMillis())
                                }
                            }
                        } catch (error: IOException) {
                            fail(error)
                        } catch (error: Exception) {
                            fail(IOException("Invalid H2 transport response", error))
                        } finally {
                            complete(call, body.size)
                        }
                    }
                })
            } catch (error: Exception) {
                complete(call, body.size)
                throw IOException("Cannot schedule H2 request", error)
            }
        }

        @Throws(IOException::class)
        fun receive(): ByteArray? {
            while (true) {
                failure.get()?.let { throw it }
                val reply = try {
                    queuedReplies.poll(1, TimeUnit.SECONDS)
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("H2 response read interrupted", error)
                }
                if (reply != null) {
                    queuedBytes.addAndGet(-reply.size)
                    return reply
                }
                if (closed.get()) {
                    failure.get()?.let { throw it }
                    return null
                }
                if (pending.get() > 0 &&
                    System.currentTimeMillis() - lastProgress.get() >= REQUEST_TIMEOUT_SECONDS * 1_000L
                ) {
                    val error = SocketTimeoutException("H2 media response stalled")
                    fail(error)
                    throw error
                }
            }
        }

        private fun complete(call: Call, size: Int) {
            if (!pendingCalls.remove(call)) return
            pending.decrementAndGet()
            pendingBytes.addAndGet(-size)
            synchronized(lane.lock) { lane.pendingBytes -= size }
            lane.requests.release()
            requests.release()
        }

        private fun fail(error: IOException) {
            failure.compareAndSet(null, error)
            close()
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            for (call in pendingCalls.toList()) call.cancel()
            channels.remove(this)
        }
    }

    @Throws(IOException::class)
    private fun readBoundedReply(response: Response, onProgress: () -> Unit): ByteArray {
        val protocol = if (response.protocol == Protocol.HTTP_2) "h2" else "http/1.1"
        val type = response.header("Content-Type")
        if (protocol != "h2" || response.code != 200 ||
            type?.contains("text/html", ignoreCase = true) == true
        ) {
            throw IOException("Invalid H2 media endpoint response")
        }
        val declared = response.body?.contentLength() ?: -1L
        if (declared > H2MediaPolicy.MAX_REPLY_BYTES) {
            throw IOException("H2 response exceeds size limit")
        }
        val stream = response.body?.byteStream() ?: throw IOException("Empty HTTP/2 response")
        val result = ByteArrayOutputStream(
            if (declared in 1..65_536) declared.toInt() else 8192
        )
        stream.use {
            val buf = ByteArray(16 * 1024)
            while (true) {
                val count = it.read(buf)
                if (count < 0) break
                if (count == 0) continue
                if (result.size() + count > H2MediaPolicy.MAX_REPLY_BYTES) {
                    throw IOException("H2 response exceeds size limit")
                }
                result.write(buf, 0, count)
                onProgress()
            }
        }
        val bytes = result.toByteArray()
        H2MediaPolicy.validateReply(protocol, response.code, type, bytes)
        if (declared >= 0 && bytes.size.toLong() != declared) {
            throw IOException("Truncated H2 response body")
        }
        return bytes
    }

    private companion object {
        const val MAX_CONNECT_ATTEMPTS = 3
        const val MAX_CHANNEL_REQUESTS = 8
        const val MAX_LANE_REQUESTS = 64
        const val MAX_CHANNEL_BYTES = 8 * 1024 * 1024
        const val MAX_LANE_BYTES = 32 * 1024 * 1024
        const val DOMAIN_COOLDOWN_MS = 30_000L
        const val REQUEST_TIMEOUT_SECONDS = 65L
    }
}
