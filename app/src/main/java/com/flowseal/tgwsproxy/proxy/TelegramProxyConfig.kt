package com.flowseal.tgwsproxy.proxy

/**
 * User supplied Telegram proxy settings after basic validation.
 *
 * Telegram disables a proxy with the "incorrect settings" dialog when the
 * endpoint cannot complete the expected protocol handshake. Most local causes
 * are malformed host/port/secret values or accidentally passing a whole URL
 * where only the host is expected.
 */
data class TelegramProxyConfig(
    val host: String,
    val port: Int,
    val secret: String? = null,
) {
    init {
        require(host == host.trim()) { "Host must not contain leading or trailing spaces." }
        require(host.isNotBlank()) { "Host must not be blank." }
        require(!host.contains("://")) { "Host must not include a URL scheme." }
        require(!host.contains('/')) { "Host must not include a path." }
        require(port in MIN_PORT..MAX_PORT) { "Port must be in $MIN_PORT..$MAX_PORT." }
        secret?.let { require(isValidMtProtoSecret(it)) { "MTProto secret must be a valid hex secret." } }
    }

    companion object {
        private const val MIN_PORT = 1
        private const val MAX_PORT = 65_535
        private const val SIMPLE_SECRET_HEX_LENGTH = 32
        private const val TLS_SECRET_PREFIX_LENGTH = 2
        private val tlsSecretPrefixes = setOf("dd", "ee")

        fun fromUserInput(host: String, port: String, secret: String? = null): TelegramProxyConfig {
            val cleanPort = port.trim().toIntOrNull()
                ?: throw IllegalArgumentException("Port must be a number.")

            return TelegramProxyConfig(
                host = host.trim(),
                port = cleanPort,
                secret = secret?.trim()?.takeIf { it.isNotEmpty() },
            )
        }

        fun isValidMtProtoSecret(secret: String): Boolean {
            val normalized = secret.trim().removePrefix("0x").removePrefix("0X")

            if (!normalized.all { it.isHexDigit() }) {
                return false
            }

            if (normalized.length == SIMPLE_SECRET_HEX_LENGTH) {
                return true
            }

            val prefix = normalized.take(TLS_SECRET_PREFIX_LENGTH).lowercase()
            val payloadLength = normalized.length - TLS_SECRET_PREFIX_LENGTH

            return prefix in tlsSecretPrefixes &&
                payloadLength >= SIMPLE_SECRET_HEX_LENGTH &&
                payloadLength % 2 == 0
        }
    }
}

private fun Char.isHexDigit(): Boolean = digitToIntOrNull(16) != null
