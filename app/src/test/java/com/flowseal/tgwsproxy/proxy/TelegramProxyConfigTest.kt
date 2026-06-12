package com.flowseal.tgwsproxy.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramProxyConfigTest {
    @Test
    fun acceptsSimpleHexSecret() {
        assertTrue(TelegramProxyConfig.isValidMtProtoSecret("00112233445566778899aabbccddeeff"))
    }

    @Test
    fun acceptsTlsSecretWithEePrefix() {
        assertTrue(TelegramProxyConfig.isValidMtProtoSecret("ee00112233445566778899aabbccddeeff7777"))
    }

    @Test
    fun rejectsNonHexSecret() {
        assertFalse(TelegramProxyConfig.isValidMtProtoSecret("zzzz"))
    }

    @Test
    fun rejectsOddLengthTlsPayload() {
        assertFalse(TelegramProxyConfig.isValidMtProtoSecret("ee00112233445566778899aabbccddeefff"))
    }

    @Test
    fun parsesValidUserInput() {
        val config = TelegramProxyConfig.fromUserInput("127.0.0.1", "1443")
        assertEquals("127.0.0.1", config.host)
        assertEquals(1443, config.port)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsHostWithScheme() {
        TelegramProxyConfig.fromUserInput("https://127.0.0.1", "1443")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsHostWithPath() {
        TelegramProxyConfig.fromUserInput("127.0.0.1/proxy", "1443")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonNumericPort() {
        TelegramProxyConfig.fromUserInput("127.0.0.1", "abc")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsOutOfRangePort() {
        TelegramProxyConfig.fromUserInput("127.0.0.1", "70000")
    }
}
