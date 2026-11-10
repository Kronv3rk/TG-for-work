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
    val wsPoolEnabled: Boolean = true,
    val frontingSni: String = "",
    val cellularNetwork: Boolean = false,
    /** Experimental Cloudflare HTTP/2 media; disabled until device validation. */
    val cfH2MediaEnabled: Boolean = false,
    /** Force all DC 1–3 to Telegram's test environment for clients without +10000 tags. */
    val forceTestDc: Boolean = false,
    /** Accept an unauthenticated PROXY v1 header only behind a trusted reverse proxy. */
    val proxyProtocolEnabled: Boolean = false,
    /** Upstream --no-secure for CF Proxy/Worker only; never the default. */
    val cfPlaintextEnabled: Boolean = false
) {
    val fakeTlsEnabled: Boolean
        get() = fakeTlsDomain.isNotBlank()

    val cfWorkerEnabled: Boolean
        get() = cfWorkerDomains.isNotEmpty()

    /**
     * Domain fronting is opt-in: the user supplies the TLS server name to present
     * (one their network lets through). Blank means the route is off.
     */
    val frontingEnabled: Boolean
        get() = frontingSni.isNotBlank()

    /**
     * Pre-opened WebSocket connections only on Wi-Fi: on cellular they would keep
     * the radio awake for little gain, since Cloudflare is tried first there anyway.
     */
    val wsPoolActive: Boolean
        get() = wsPoolEnabled && !cellularNetwork

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
            // Matches upstream Worker available_domains() without retaining
            // redundant DNS/TLS fallback attempts; order stays stable for pooling.
            .distinct()

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

    /**
     * True when [other] describes the same proxy endpoint. Deliberately ignores
     * [cellularNetwork]: the network type only steers route ordering, so treating
     * a Wi-Fi/mobile switch as a different configuration would tear the proxy down
     * and drop every live Telegram connection for no reason.
     */
    fun sameConnection(other: ProxyOptions): Boolean =
        copy(cellularNetwork = false) == other.copy(cellularNetwork = false)

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
                // Do not resolve addresses using DNS: malformed literals must be
                // rejected before a custom DC override reaches the network layer.
                val groups = if ("::" in value) {
                    val halves = value.split("::", limit = 2)
                    if (halves.size != 2 || "::" in halves[1]) return false
                    val left = if (halves[0].isEmpty()) emptyList() else halves[0].split(':')
                    val right = if (halves[1].isEmpty()) emptyList() else halves[1].split(':')
                    (left + right).takeIf { it.size < 8 } ?: return false
                } else {
                    value.split(':').takeIf { it.size == 8 } ?: return false
                }
                return groups.all { group ->
                    group.length in 1..4 && group.all { c ->
                        c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
                    }
                }
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
