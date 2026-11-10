package com.flowseal.tgwsproxy.proxy

import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters

/**
 * Fronting changes the HTTP Host independently of the TLS peer name.
 * The certificate must still be valid for the name sent in SNI.
 */
internal object TlsEndpointPolicy {
    fun configure(parameters: SSLParameters, relayDomain: String, frontingSni: String?): SSLParameters {
        val peerName = frontingSni ?: relayDomain
        parameters.serverNames = listOf(SNIHostName(peerName))
        parameters.endpointIdentificationAlgorithm = "HTTPS"
        return parameters
    }
}
