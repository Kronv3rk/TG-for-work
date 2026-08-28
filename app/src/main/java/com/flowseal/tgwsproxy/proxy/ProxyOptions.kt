package com.flowseal.tgwsproxy.proxy

data class ProxyOptions(
    val host: String = TelegramProxy.DEFAULT_HOST,
    val port: Int = TelegramProxy.DEFAULT_PORT,
    val secretHex: String,
    val cfProxyEnabled: Boolean = true,
    val cfProxyPriority: Boolean = true,
    val cfProxyUserDomain: String = "",
    val cfWorkerDomain: String = "",
    val fakeTlsDomain: String = DEFAULT_FAKE_TLS_DOMAIN,
    val cellularNetwork: Boolean = false
) {
    val fakeTlsEnabled: Boolean
        get() = fakeTlsDomain.isNotBlank()

    val cfWorkerEnabled: Boolean
        get() = cfWorkerDomains.isNotEmpty()

    /**
     * User-supplied Cloudflare Worker domains, parsed from a comma/space/newline
     * separated value. The worker exposes `/apiws?dst=<dc-ip>&dc=<n>` and tunnels
     * a raw TCP stream to the DC, so it is the user's own blocking-resistant relay.
     */
    val cfWorkerDomains: List<String>
        get() = cfWorkerDomain
            .split(',', ' ', '\n', '\t')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    companion object {
        const val DEFAULT_FAKE_TLS_DOMAIN = "www.google.com"
    }
}
