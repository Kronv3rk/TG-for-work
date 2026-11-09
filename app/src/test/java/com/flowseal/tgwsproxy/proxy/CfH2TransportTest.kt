package com.flowseal.tgwsproxy.proxy

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test(timeout = 15_000)
    fun retriesAStalledResponseAndSuppressesTheLateDuplicate() {
        createServer(http2 = true).use { fixture ->
            fixture.server.enqueue(MockResponse().setResponseCode(200))
            val responseBytes = ByteArray(24) { (it + 9).toByte() }
            fixture.server.enqueue(
                MockResponse().setHeadersDelay(5, TimeUnit.SECONDS)
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
                Thread.sleep(2_500L)
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
