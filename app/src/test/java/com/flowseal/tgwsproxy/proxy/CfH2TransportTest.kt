package com.flowseal.tgwsproxy.proxy

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CfH2TransportTest {
    private data class LocalServer(
        val server: MockWebServer,
        val client: OkHttpClient
    ) : AutoCloseable {
        override fun close() {
            server.shutdown()
        }
    }

    private fun createServer(http2: Boolean): LocalServer {
        val certificate = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(certificate.certificate)
            .build()
        val server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.protocols = if (http2) listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)
            else listOf(Protocol.HTTP_1_1)
        server.start()
        val client = OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .callTimeout(6, TimeUnit.SECONDS)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        return LocalServer(server, client)
    }

    @Test(timeout = 15_000)
    fun sendsNativePacketsOverRealTlsHttp2AndReceivesReply() {
        createServer(http2 = true).use { fixture ->
            fixture.server.enqueue(MockResponse().setResponseCode(200))
            val responseBytes = ByteArray(24) { (it * 3).toByte() }
            fixture.server.enqueue(
                MockResponse().setResponseCode(200)
                    .addHeader("Content-Type", "application/octet-stream")
                    .setBody(Buffer().write(responseBytes))
            )
            val transport = CfH2Transport(fixture.client)
            try {
                val endpoint = "localhost:${fixture.server.port}"
                val channel = transport.open(listOf(endpoint))
                assertNotNull("HTTP/2 preflight should succeed", channel)
                val requestBytes = ByteArray(24) { it.toByte() }
                channel!!.send(requestBytes)
                assertArrayEquals(responseBytes, channel.receive())
                val preflight = fixture.server.takeRequest(3, TimeUnit.SECONDS)
                val request = fixture.server.takeRequest(3, TimeUnit.SECONDS)
                assertEquals("HEAD", preflight?.method)
                assertEquals("POST", request?.method)
                assertEquals("/api", request?.path)
                assertArrayEquals(requestBytes, request!!.body.readByteArray())
                channel.close()
            } finally {
                transport.close()
            }
        }
    }

    @Test(timeout = 15_000)
    fun refusesHttp11DowngradeWithoutSendingNativePackets() {
        createServer(http2 = false).use { fixture ->
            fixture.server.enqueue(MockResponse().setResponseCode(200))
            val transport = CfH2Transport(fixture.client)
            try {
                val endpoint = "localhost:${fixture.server.port}"
                assertNull(transport.open(listOf(endpoint)))
                assertEquals(1, fixture.server.requestCount)
            } finally {
                transport.close()
            }
        }
    }

    @Test(timeout = 20_000)
    fun rotatesOldestUnansweredRequestWhenChannelUploadSlotsAreFull() {
        createServer(http2 = true).use { fixture ->
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "HEAD") return MockResponse().setResponseCode(200)
                    return MockResponse().setHeadersDelay(20, TimeUnit.SECONDS)
                        .addHeader("Content-Type", "application/octet-stream")
                        .setBody(Buffer().write(ByteArray(24) { 7 }))
                }
            }
            val transport = CfH2Transport(fixture.client)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val channel = transport.open(listOf("localhost:${fixture.server.port}"))!!
                repeat(8) { index ->
                    channel.send(ByteArray(24).also {
                        it[0] = 1
                        it[8] = (index + 1).toByte()
                    })
                }
                assertEquals(9, fixture.server.requestCount)
                val ninth = executor.submit<Boolean> {
                    channel.send(ByteArray(24).also { it[0] = 1; it[8] = 9 })
                    true
                }
                assertTrue("ninth upload should proceed after retiring an overdue request",
                    ninth.get(6, TimeUnit.SECONDS))
                assertEquals(10, fixture.server.requestCount)
                channel.close()
            } finally {
                executor.shutdownNow()
                transport.close()
            }
        }
    }

    @Test(timeout = 15_000)
    fun reusesOnePreflightedOriginLaneAcrossTelegramMediaChannels() {
        createServer(http2 = true).use { fixture ->
            val firstReply = ByteArray(24) { 1 }
            val secondReply = ByteArray(24) { 2 }
            fixture.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.method == "HEAD") return MockResponse().setResponseCode(200)
                    val body = request.body.readByteArray()
                    val reply = if (body.size > 8 && body[8].toInt() == 2) secondReply else firstReply
                    return MockResponse().addHeader("Content-Type", "application/octet-stream")
                        .setBody(Buffer().write(reply))
                }
            }
            val transport = CfH2Transport(fixture.client)
            try {
                val endpoint = "localhost:${fixture.server.port}"
                val first = transport.open(listOf(endpoint))!!
                val second = transport.open(listOf(endpoint))!!
                first.send(ByteArray(24).also { it[0] = 1 })
                second.send(ByteArray(24).also { it[0] = 1; it[8] = 2 })
                assertArrayEquals(firstReply, first.receive())
                assertArrayEquals(secondReply, second.receive())
                assertEquals("one HEAD preflight and two multiplexed POSTs", 3, fixture.server.requestCount)
                first.close()
                second.close()
            } finally {
                transport.close()
            }
        }
    }

    @Test(timeout = 15_000)
    fun retriesAStalledResponseAndSuppressesTheLateDuplicate() {
        createServer(http2 = true).use { fixture ->
            fixture.server.enqueue(MockResponse().setResponseCode(200))
            val responseBytes = ByteArray(24) { (it + 9).toByte() }
            fixture.server.enqueue(
                MockResponse().setHeadersDelay(3_500, TimeUnit.MILLISECONDS)
                    .addHeader("Content-Type", "application/octet-stream")
                    .setBody(Buffer().write(responseBytes))
            )
            fixture.server.enqueue(
                MockResponse().addHeader("Content-Type", "application/octet-stream")
                    .setBody(Buffer().write(responseBytes))
            )
            val transport = CfH2Transport(fixture.client)
            try {
                val channel = transport.open(listOf("localhost:${fixture.server.port}"))!!
                channel.send(ByteArray(24).also { it[0] = 1 })
                assertArrayEquals(responseBytes, channel.receive())
                Thread.sleep(1_000L)
                assertEquals("HEAD plus original and one replay", 3, fixture.server.requestCount)
                assertEquals("late duplicate response must not be queued", 0, channel.queuedReplyCount)
                assertFalse(channel.failed)
                channel.close()
            } finally {
                transport.close()
            }
        }
    }

    @Test(timeout = 15_000)
    fun mapsCloudflareHttpErrorsToNativeMtprotoErrorPackets() {
        createServer(http2 = true).use { fixture ->
            fixture.server.enqueue(MockResponse().setResponseCode(200))
            fixture.server.enqueue(MockResponse().setResponseCode(403))
            val transport = CfH2Transport(fixture.client)
            try {
                val channel = transport.open(listOf("localhost:${fixture.server.port}"))!!
                channel.send(ByteArray(24).also { it[0] = 1 })
                val error = channel.receive()!!
                assertArrayEquals(byteArrayOf(109, 0xfe.toByte(), 0xff.toByte(), 0xff.toByte()), error)
                assertTrue(channel.failed)
            } finally {
                transport.close()
            }
        }
    }

    @Test
    fun boundsAggregateReplyMemoryAcrossChannelsOnTheSameLane() {
        val lane = CfH2Transport.Lane()
        assertTrue(lane.reserveReply(20 * 1024 * 1024))
        assertTrue(lane.reserveReply(12 * 1024 * 1024))
        assertFalse(lane.reserveReply(1))
        lane.releaseReply(20 * 1024 * 1024)
        assertTrue(lane.reserveReply(1))
        lane.releaseReply(12 * 1024 * 1024 + 1)
    }

}
