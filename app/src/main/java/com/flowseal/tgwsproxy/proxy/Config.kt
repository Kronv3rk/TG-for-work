package com.flowseal.tgwsproxy.proxy

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import java.security.SecureRandom

data class ProxyConfig(
    val host: String = "127.0.0.1",
    val port: Int = 1443,
    val secret: String = randomSecret(),
    /** DC -> Telegram IP. Matches the Python --dc-ip default. */
    val dcRedirects: Map<Int, String> = mapOf(
        2 to "149.154.167.220",
        4 to "149.154.167.220",
    ),
    val bufferSizeBytes: Int = 256 * 1024,
    val poolSizePerDc: Int = 4,
    val verbose: Boolean = false,
) {
    fun proxyLink(): String =
        "tg://proxy?server=$host&port=$port&secret=dd$secret"

    companion object {
        private const val PREFS = "tg_ws_proxy"
        private const val K_HOST = "host"
        private const val K_PORT = "port"
        private const val K_SECRET = "secret"
        private const val K_DC_IP = "dc_ip_csv"
        private const val K_BUF_KB = "buf_kb"
        private const val K_POOL = "pool_size"
        private const val K_VERBOSE = "verbose"

        fun load(ctx: Context): ProxyConfig {
            val sp = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val def = ProxyConfig()
            val dcCsv = sp.getString(K_DC_IP, def.dcRedirects.entries.joinToString(",") { "${it.key}:${it.value}" })!!
            return ProxyConfig(
                host = sp.getString(K_HOST, def.host)!!,
                port = sp.getInt(K_PORT, def.port),
                secret = sp.getString(K_SECRET, def.secret)!!,
                dcRedirects = parseDcIpCsv(dcCsv) ?: def.dcRedirects,
                bufferSizeBytes = sp.getInt(K_BUF_KB, def.bufferSizeBytes / 1024) * 1024,
                poolSizePerDc = sp.getInt(K_POOL, def.poolSizePerDc),
                verbose = sp.getBoolean(K_VERBOSE, def.verbose),
            )
        }

        fun save(ctx: Context, cfg: ProxyConfig) {
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                putString(K_HOST, cfg.host)
                putInt(K_PORT, cfg.port)
                putString(K_SECRET, cfg.secret)
                putString(K_DC_IP, cfg.dcRedirects.entries.joinToString(",") { "${it.key}:${it.value}" })
                putInt(K_BUF_KB, cfg.bufferSizeBytes / 1024)
                putInt(K_POOL, cfg.poolSizePerDc)
                putBoolean(K_VERBOSE, cfg.verbose)
            }
        }

        fun randomSecret(): String {
            val b = ByteArray(16)
            SecureRandom().nextBytes(b)
            return b.toHex()
        }

        fun parseDcIpCsv(csv: String): Map<Int, String>? {
            val out = HashMap<Int, String>()
            for (raw in csv.split(',')) {
                val s = raw.trim()
                if (s.isEmpty()) continue
                val (dcS, ipS) = s.split(':', limit = 2).let {
                    if (it.size != 2) return null
                    it[0] to it[1]
                }
                val dc = dcS.toIntOrNull() ?: return null
                if (!isValidIpv4(ipS)) return null
                out[dc] = ipS
            }
            return out.takeIf { it.isNotEmpty() }
        }

        private fun isValidIpv4(s: String): Boolean {
            val parts = s.split('.')
            if (parts.size != 4) return false
            for (p in parts) {
                val v = p.toIntOrNull() ?: return false
                if (v !in 0..255) return false
            }
            return true
        }
    }
}

internal fun ByteArray.toHex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        sb.append(HEX[v ushr 4])
        sb.append(HEX[v and 0x0F])
    }
    return sb.toString()
}

internal fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "hex length must be even" }
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        val hi = Character.digit(this[i * 2], 16)
        val lo = Character.digit(this[i * 2 + 1], 16)
        require(hi >= 0 && lo >= 0) { "invalid hex" }
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

private val HEX = "0123456789abcdef".toCharArray()
