package com.flowseal.tgwsproxy.proxy

data class ProxyOptions(
    val host: String = TelegramProxy.DEFAULT_HOST,
    val port: Int = TelegramProxy.DEFAULT_PORT,
    val secretHex: String,
    val cfProxyEnabled: Boolean = true,
    val cfProxyPriority: Boolean = true,
    val cfProxyUserDomain: String = "",
    val cfWorkerDomain: String = "",
    val dcIpOverrides: String = "",
    val bufferKb: Int = DEFAULT_BUFFER_KB,
    val maxConnections: Int = DEFAULT_MAX_CONNECTIONS,
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

    /**
     * User overrides for Telegram data-center addresses, written as `dc:ip` pairs
     * ("2:149.154.167.220 4:149.154.167.220"), the same shape upstream tg-ws-proxy
     * uses for its `dc_ip` setting. An override wins over the built-in map for
     * every route, so a DC can be pointed at a working address by hand.
     */
    val dcIpMap: Map<Int, String>
        get() = dcIpOverrides
            .split(',', ' ', '\n', '\t')
            .filter { it.isNotBlank() }
            .mapNotNull(::parseDcIpPair)
            .toMap()

    /** Socket and relay buffer size, clamped to a range that stays sane on mobile. */
    val bufferBytes: Int
        get() = bufferKb.coerceIn(MIN_BUFFER_KB, MAX_BUFFER_KB) * 1024

    /** Upper bound for concurrent proxy worker threads. */
    val threadLimit: Int
        get() = maxConnections.coerceIn(MIN_MAX_CONNECTIONS, MAX_MAX_CONNECTIONS)

    companion object {
        const val DEFAULT_FAKE_TLS_DOMAIN = "www.google.com"
        const val DEFAULT_BUFFER_KB = 256
        const val DEFAULT_MAX_CONNECTIONS = 96
        const val MIN_BUFFER_KB = 16
        const val MAX_BUFFER_KB = 1024
        const val MIN_MAX_CONNECTIONS = 16
        const val MAX_MAX_CONNECTIONS = 512

        /** Parses one `dc:ip` override, or null when the token is malformed. */
        fun parseDcIpPair(token: String): Pair<Int, String>? {
            val parts = token.trim().split(':', limit = 2)
            if (parts.size != 2) return null
            val dc = parts[0].trim().toIntOrNull() ?: return null
            val ip = parts[1].trim()
            return if (dc <= 0 || !isIpAddress(ip)) null else dc to ip
        }

        /** Accepts a dotted IPv4 literal or a bracket-free IPv6 literal. */
        fun isIpAddress(value: String): Boolean {
            if (value.isBlank()) return false
            if (value.contains(':')) {
                return value.all { it.isDigit() || it == ':' || it in 'a'..'f' || it in 'A'..'F' }
            }
            val parts = value.split('.')
            if (parts.size != 4) return false
            return parts.all { part ->
                val n = part.toIntOrNull()
                part.isNotEmpty() && n != null && n in 0..255
            }
        }
    }
}
