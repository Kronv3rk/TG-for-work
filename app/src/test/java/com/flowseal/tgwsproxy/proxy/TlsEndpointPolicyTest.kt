package com.flowseal.tgwsproxy.proxy

import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TlsEndpointPolicyTest {
    @Test
    fun ordinaryRelayChecksItsCertificateAgainstTheRelayDomain() {
        val params = TlsEndpointPolicy.configure(SSLParameters(), "relay.example", null)
        assertEquals("HTTPS", params.endpointIdentificationAlgorithm)
        assertEquals("relay.example", (params.serverNames.single() as SNIHostName).asciiName)
    }

    @Test
    fun domainFrontingChecksTheCertificateAgainstItsActualSniName() {
        val params = TlsEndpointPolicy.configure(
            SSLParameters(), "relay.example", "front.example"
        )
        assertEquals("HTTPS", params.endpointIdentificationAlgorithm)
        assertEquals("front.example", (params.serverNames.single() as SNIHostName).asciiName)
    }

    @Test
    fun domainFrontingDoesNotDisablePlatformTrustChecks() {
        val params = TlsEndpointPolicy.configure(
            SSLParameters(), "relay.example", "front.example"
        )
        assertTrue(params.endpointIdentificationAlgorithm == "HTTPS")
    }
}
