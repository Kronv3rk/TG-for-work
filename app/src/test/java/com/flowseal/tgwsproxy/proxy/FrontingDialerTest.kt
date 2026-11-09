package com.flowseal.tgwsproxy.proxy

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FrontingDialerTest {
    @Test
    fun disabledFrontingNeverUsesDifferentTlsServerName() {
        val attempted = mutableListOf<String>()
        val dialer = FrontingDialer(
            normal = { _, _ -> attempted += "normal"; "normal" },
            fronted = { _, _ -> attempted += "fronted"; "fronted" },
            enabled = { false }
        )
        assertEquals("normal", dialer.connect("relay", "domain"))
        assertEquals(listOf("normal"), attempted)
    }

    @Test
    fun fallsBackToFrontingAndPrefersPreviouslySuccessfulMode() {
        val attempted = mutableListOf<String>()
        var normalWorks = false
        val dialer = FrontingDialer(
            normal = { _, _ ->
                attempted += "normal"
                if (!normalWorks) throw IOException("normal route blocked")
                "normal"
            },
            fronted = { _, _ -> attempted += "fronted"; "fronted" },
            enabled = { true }
        )
        assertEquals("fronted", dialer.connect("relay", "domain"))
        assertEquals(listOf("normal", "fronted"), attempted)
        attempted.clear()
        assertEquals("fronted", dialer.connect("relay", "domain"))
        assertEquals(listOf("fronted"), attempted)
        dialer.reset()
        normalWorks = true
        attempted.clear()
        assertEquals("normal", dialer.connect("relay", "domain"))
        assertEquals(listOf("normal"), attempted)
    }

    @Test
    fun switchesBackToNormalWhenFrontingStopsWorking() {
        val attempted = mutableListOf<String>()
        var frontingWorks = true
        var normalWorks = false
        val dialer = FrontingDialer(
            normal = { _, _ ->
                attempted += "normal"
                if (!normalWorks) throw IOException("normal blocked")
                "normal"
            },
            fronted = { _, _ ->
                attempted += "fronting"
                if (!frontingWorks) throw IOException("fronting blocked")
                "fronting"
            },
            enabled = { true }
        )
        assertEquals("fronting", dialer.connect("relay", "domain"))
        attempted.clear()
        normalWorks = true
        frontingWorks = false
        assertEquals("normal", dialer.connect("relay", "domain"))
        assertEquals(listOf("fronting", "normal"), attempted)
    }

    @Test
    fun failureInBothModesPropagatesToPoolForBackoff() {
        val dialer = FrontingDialer(
            normal = { _, _ -> throw IOException("normal unavailable") },
            fronted = { _, _ -> throw IOException("fronting unavailable") },
            enabled = { true }
        )
        assertThrows(IOException::class.java) { dialer.connect("relay", "domain") }
    }
}
