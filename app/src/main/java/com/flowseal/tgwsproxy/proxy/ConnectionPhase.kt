package com.flowseal.tgwsproxy.proxy

/**
 * The app is a local server. Listening does not prove any external Telegram
 * relay is reachable, so the dashboard never labels it as "connected".
 */
internal enum class ConnectionPhase {
    STOPPED,
    LISTENING,
    HAS_CLIENTS;

    companion object {
        fun of(running: Boolean, activeConnections: Int): ConnectionPhase = when {
            !running -> STOPPED
            activeConnections > 0 -> HAS_CLIENTS
            else -> LISTENING
        }
    }
}
