package com.flowseal.tgwsproxy.proxy

import java.io.IOException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class H2MediaPolicyTest {
    @Test
    fun acceptsOnlyRealHttp2PreflightResponses() {
        assertTrue(H2MediaPolicy.isPreflightValid("h2", 200))
        assertTrue(H2MediaPolicy.isPreflightValid("h2", 405))
        assertTrue(H2MediaPolicy.isPreflightValid("h2", 501))
        assertFalse(H2MediaPolicy.isPreflightValid("http/1.1", 200))
        assertFalse(H2MediaPolicy.isPreflightValid("h2", 403))
        assertFalse(H2MediaPolicy.isPreflightValid("h2", 404))
    }

    @Test
    fun rejectsUnexpectedHttpRepliesBeforeForwardingToTelegram() {
        assertThrows(IOException::class.java) {
            H2MediaPolicy.validateReply("http/1.1", 200, "application/octet-stream", ByteArray(24))
        }
        assertThrows(IOException::class.java) {
            H2MediaPolicy.validateReply("h2", 403, "application/octet-stream", ByteArray(24))
        }
        assertThrows(IOException::class.java) {
            H2MediaPolicy.validateReply("h2", 200, "text/html; charset=utf-8", ByteArray(24))
        }
        assertThrows(IOException::class.java) {
            H2MediaPolicy.validateReply("h2", 200, "application/octet-stream", ByteArray(5))
        }
    }

    @Test
    fun acceptsValidNativePacketAndFourByteMtprotoError() {
        H2MediaPolicy.validateReply("h2", 200, "application/octet-stream", ByteArray(24))
        H2MediaPolicy.validateReply("h2", 200, "application/octet-stream", ByteArray(4))
    }

    @Test
    fun limitsMemoryPerHttp2Response() {
        assertThrows(IOException::class.java) {
            H2MediaPolicy.validateReply(
                "h2", 200, "application/octet-stream", ByteArray(4 * 1024 * 1024 + 4)
            )
        }
    }
}
