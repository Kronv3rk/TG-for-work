package com.flowseal.tgwsproxy.proxy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class WebSocketProtocolTest {
    private val validResponse = listOf(
        "HTTP/1.1 101 Switching Protocols",
        "upgrade: WebSocket",
        "Connection: keep-alive, Upgrade",
        "Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",
        ""
    )

    @Test
    fun acceptsValidUpgradeWithCaseInsensitiveTokenHeaders() {
        WebSocketProtocol.validateUpgrade(validResponse, "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=")
    }

    @Test
    fun rejectsMissingOrForgedUpgradeProof() {
        expectProtocolFailure {
            WebSocketProtocol.validateUpgrade(
                validResponse.filterNot { it.startsWith("Sec-WebSocket-Accept") },
                "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="
            )
        }
        expectProtocolFailure {
            WebSocketProtocol.validateUpgrade(validResponse, "forged-proof")
        }
    }

    @Test
    fun rejectsUpgradeWithWrongConnectionOrUpgradeHeader() {
        expectProtocolFailure {
            WebSocketProtocol.validateUpgrade(
                validResponse.map { if (it.startsWith("Connection:")) "Connection: close" else it },
                "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="
            )
        }
        expectProtocolFailure {
            WebSocketProtocol.validateUpgrade(
                validResponse.map { if (it.startsWith("upgrade:")) "Upgrade: h2c" else it },
                "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="
            )
        }
    }

    @Test
    fun rejectsUnrequestedSubprotocol() {
        expectProtocolFailure {
            WebSocketProtocol.validateUpgrade(
                validResponse.dropLast(1) + "Sec-WebSocket-Protocol: unexpected",
                "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="
            )
        }
    }

    @Test
    fun rejectsFramesMaskedByTheServer() {
        expectProtocolFailure { WebSocketProtocol.validateServerFrame(0x82, 0x80, 0) }
    }

    @Test
    fun rejectsReservedBitsAndUnsupportedOpcodes() {
        expectProtocolFailure { WebSocketProtocol.validateServerFrame(0xC2, 0, 0) }
        expectProtocolFailure { WebSocketProtocol.validateServerFrame(0x83, 0, 0) }
    }

    @Test
    fun rejectsOversizedAndInvalidControlFrames() {
        expectProtocolFailure { WebSocketProtocol.validateServerFrame(0x89, 0, 126) }
        expectProtocolFailure { WebSocketProtocol.validateServerFrame(0x09, 0, 0) }
        expectProtocolFailure { WebSocketProtocol.validateServerFrame(0x82, 127, -1) }
        expectProtocolFailure { WebSocketProtocol.validateServerFrame(0x82, 127, 8L * 1024 * 1024 + 1) }
    }

    @Test
    fun deliversUnfragmentedBinaryMessage() {
        val assembler = WebSocketMessageAssembler(1024)
        assertArrayEquals(byteArrayOf(1, 2), assembler.accept(2, true, byteArrayOf(1, 2)))
    }

    @Test
    fun combinesFragmentedBinaryMessageWithoutLosingOrder() {
        val assembler = WebSocketMessageAssembler(1024)
        assertNull(assembler.accept(2, false, byteArrayOf(1, 2)))
        assertNull(assembler.accept(0, false, byteArrayOf(3)))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), assembler.accept(0, true, byteArrayOf(4)))
    }

    @Test
    fun rejectsOrphanContinuationsAndOverlappingMessages() {
        val assembler = WebSocketMessageAssembler(1024)
        expectProtocolFailure { assembler.accept(0, true, byteArrayOf(1)) }
        assertNull(assembler.accept(2, false, byteArrayOf(1)))
        expectProtocolFailure { assembler.accept(2, true, byteArrayOf(2)) }
    }

    @Test
    fun rejectsOversizedFragmentSequence() {
        val assembler = WebSocketMessageAssembler(4)
        assertNull(assembler.accept(2, false, byteArrayOf(1, 2, 3)))
        expectProtocolFailure { assembler.accept(0, true, byteArrayOf(4, 5)) }
    }

    @Test
    fun refusesTextFramesInBinaryMtprotoTunnel() {
        val assembler = WebSocketMessageAssembler(1024)
        expectProtocolFailure { assembler.accept(1, true, byteArrayOf(65)) }
    }

    private fun expectProtocolFailure(block: () -> Unit) {
        try {
            block()
            fail("Malformed WebSocket data must be rejected")
        } catch (_: IllegalStateException) {
            // Expected protocol violation.
        }
    }
}
