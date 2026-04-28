package com.flowseal.tgwsproxy.proxy

data class ProxyStats(
    val running: Boolean,
    val activeConnections: Int,
    val totalConnections: Int,
    val wsConnections: Int,
    val tcpFallbackConnections: Int,
    val cfProxyConnections: Int,
    val badConnections: Int,
    val maskedConnections: Int,
    val bytesUp: Long,
    val bytesDown: Long,
    val proxyLink: String,
    val plainProxyLink: String,
    val fakeTlsEnabled: Boolean,
    val cfProxyEnabled: Boolean,
    val routeStatus: String,
    val routePolicy: String,
    val networkMode: String,
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
            cfProxyConnections = 0,
            badConnections = 0,
            maskedConnections = 0,
            bytesUp = 0,
            bytesDown = 0,
            proxyLink = "",
            plainProxyLink = "",
            fakeTlsEnabled = false,
            cfProxyEnabled = false,
            routeStatus = "Маршрут ещё не выбран",
            routePolicy = "Авто: WS > TCP",
            networkMode = "Сеть не определена",
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
