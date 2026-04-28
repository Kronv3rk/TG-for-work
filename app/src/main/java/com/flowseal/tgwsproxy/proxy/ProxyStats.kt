package com.flowseal.tgwsproxy.proxy

data class ProxyStats(
    val running: Boolean,
    val activeConnections: Int,
    val totalConnections: Int,
    val wsConnections: Int,
    val tcpFallbackConnections: Int,
    val badConnections: Int,
    val bytesUp: Long,
    val bytesDown: Long,
    val lastMessage: String
) {
    val transferred: String
        get() = humanBytes(bytesUp + bytesDown)

    companion object {
        fun empty(message: String = "Прокси остановлен") = ProxyStats(
            running = false,
            activeConnections = 0,
            totalConnections = 0,
            wsConnections = 0,
            tcpFallbackConnections = 0,
            badConnections = 0,
            bytesUp = 0,
            bytesDown = 0,
            lastMessage = message
        )

        private fun humanBytes(value: Long): String {
            var n = value.toDouble()
            val units = arrayOf("B", "KB", "MB", "GB")
            for (unit in units) {
                if (n < 1024.0) {
                    return String.format("%.1f %s", n, unit)
                }
                n /= 1024.0
            }
            return String.format("%.1f TB", n)
        }
    }
}
