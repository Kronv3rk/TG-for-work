package com.flowseal.tgwsproxy.proxy

/**
 * Telegram test servers are identified by adding 10000 to the DC index.
 * The media bit is the sign of the original signed DC index and is preserved
 * separately when preparing the relay handshake.
 *
 * Upstream references: proxy/tg_ws_proxy.py and proxy/utils.py.
 */
internal data class TelegramDcTarget(
    val id: Int,
    val isTest: Boolean
) {
    val webSocketPath: String
        get() = if (isTest) "/apiws_test" else "/apiws"

    fun directIp(productionOverrides: Map<Int, String>): String? =
        if (isTest) TEST_IPS[id] else productionOverrides[id] ?: PRODUCTION_IPS[id]

    companion object {
        private val TEST_IPS = mapOf(
            1 to "149.154.175.10",
            2 to "149.154.167.40",
            3 to "149.154.175.117"
        )
        private val PRODUCTION_IPS = mapOf(
            1 to "149.154.175.50",
            2 to "149.154.167.51",
            3 to "149.154.175.100",
            4 to "149.154.167.91",
            5 to "149.154.171.5",
            203 to "91.105.192.100"
        )

        fun resolve(absoluteDcId: Int, forceTestDc: Boolean): TelegramDcTarget {
            require(absoluteDcId > 0)
            val marked = absoluteDcId >= 10_000
            val normalized = if (marked) absoluteDcId - 10_000 else absoluteDcId
            return TelegramDcTarget(normalized, marked || forceTestDc)
        }
    }
}
