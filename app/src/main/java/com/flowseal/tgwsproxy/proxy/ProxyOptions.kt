package com.flowseal.tgwsproxy.proxy

data class ProxyOptions(
    val host: String = TelegramProxy.DEFAULT_HOST,
    val port: Int = TelegramProxy.DEFAULT_PORT,
    val secretHex: String,
    val cfProxyEnabled: Boolean = true,
    val cfProxyPriority: Boolean = true,
    val cfProxyUserDomain: String = "",
    val fakeTlsDomain: String = DEFAULT_FAKE_TLS_DOMAIN,
    val cellularNetwork: Boolean = false
) {
    val fakeTlsEnabled: Boolean
        get() = fakeTlsDomain.isNotBlank()

    companion object {
        const val DEFAULT_FAKE_TLS_DOMAIN = "www.google.com"
    }
}
