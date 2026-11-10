package com.flowseal.tgwsproxy.proxy

import java.io.File
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Read-only external acceptance probe using the same OkHttp/TLS/ALPN stack as
 * the Android H2 transport. Never sends MTProto packets or a user secret.
 *
 * Run through the manual Network Acceptance workflow with an explicitly
 * provided Cloudflare H2 host. Without that input, this test is skipped.
 */
class CloudflareH2PreflightTest {
    @Test
    fun rejectsNonPublicOrAmbiguousInputBeforeAnyNetworkRequest() {
        for (host in listOf(
            "https://kws2.proxy.domain.org", "kws2.proxy.domain.org/api",
            "user:password@kws2.proxy.domain.org", "127.0.0.1",
            "localhost", "kws2.internal", "kws2.local", "kws2..proxy.org",
            "kws2.proxy.org:8443", "-bad.proxy.org"
        )) {
            assertFalse("Invalid external H2 host: $host", validPublicHost(host))
        }
        assertTrue(validPublicHost("kws2.proxy.domain.org"))
    }

    @Test(timeout = 25_000)
    fun checksRealTlsAlpnAndHeadWithoutSendingAnMtprotoPacket() {
        val raw = System.getenv("TG_H2_PROBE_HOST").orEmpty()
        assumeTrue("External probe requires TG_H2_PROBE_HOST", raw.isNotBlank())
        assertTrue("Host must be a public DNS name without URL scheme/port/path",
            validPublicHost(raw))
        val host = raw.lowercase()
        val client = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
        try {
            val request = Request.Builder().url("https://$host/api")
                .header("Accept-Encoding", "identity").head().build()
            val started = System.nanoTime()
            client.newCall(request).execute().use { response ->
                val elapsed = (System.nanoTime() - started) / 1_000_000L
                val protocol = if (response.protocol == Protocol.HTTP_2) "h2" else "http/1.1"
                val accepted = H2MediaPolicy.isPreflightValid(protocol, response.code)
                val json = """{"probe":"android-okhttp-stack","host":"$host","http_version":"${response.protocol}","http_status":${response.code},"elapsed_ms":$elapsed,"accepted":$accepted,"scope":"tls_alpn_head_only"}"""
                System.getenv("TG_H2_PROBE_JSON")?.takeIf { it.isNotBlank() }?.let { output ->
                    File(output).apply {
                        parentFile?.mkdirs()
                        writeText(json + "\n")
                    }
                }
                println("H2 external preflight: $json")
                assertEquals("Cloudflare H2 requires TLS ALPN h2", Protocol.HTTP_2, response.protocol)
                assertTrue("HEAD /api responded with unsupported status ${response.code}", accepted)
            }
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun validPublicHost(value: String): Boolean {
        if (value != value.trim() || value.isBlank() || value.length > 253) return false
        if (value.contains(Regex("[\\s/@:#?\\\\]"))) return false
        val host = value.lowercase()
        val parts = host.split('.')
        if (parts.size < 2 || parts.last() in setOf(
                "localhost", "local", "internal", "invalid", "example", "test"
            )
        ) return false
        val label = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
        return parts.all { it.length <= 63 && label.matches(it) } &&
            // Labels with only digits can still be DNS names, but bare IPs
            // must not be accepted as a shortcut around certificate checks.
            !Regex("\\d+\\.\\d+\\.\\d+\\.\\d+").matches(host)
    }
}
