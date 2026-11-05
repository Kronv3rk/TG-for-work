package com.flowseal.tgwsproxy.proxy

/**
 * A tg://proxy link is a representation of saved settings, not a reason to
 * start a socket listener. Keep its encoding identical across the UI and proxy.
 */
internal object ProxyLinkFormatter {
    fun link(options: ProxyOptions): String =
        if (options.fakeTlsEnabled) fakeTls(options) else plain(options)

    fun plain(options: ProxyOptions): String =
        "tg://proxy?server=${options.host}&port=${options.port}&secret=dd${options.secretHex}"

    private fun fakeTls(options: ProxyOptions): String {
        val domainHex = options.fakeTlsDomain.toByteArray(Charsets.US_ASCII)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        return "tg://proxy?server=${options.host}&port=${options.port}&secret=ee${options.secretHex}${domainHex}"
    }
}
