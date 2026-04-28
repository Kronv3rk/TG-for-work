package com.flowseal.tgwsproxy.proxy

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import kotlin.random.Random

class CfProxyBalancer {
    private val lock = Any()
    private var domains: List<String> = DEFAULT_ENCODED_DOMAINS.map(::decodeDomain)
    private var dcToDomain: Map<Int, String> = chooseDcDomains(domains)

    fun setUserDomain(domain: String) {
        val clean = domain.trim()
        if (clean.isBlank()) return
        updateDomains(listOf(clean))
    }

    fun refreshAsync(executor: ExecutorService) {
        executor.execute {
            val fetched = runCatching { fetchDomains() }.getOrDefault(emptyList())
            if (fetched.isNotEmpty()) {
                updateDomains(fetched)
            }
        }
    }

    fun getDomainsForDc(dcId: Int): List<String> {
        synchronized(lock) {
            val current = dcToDomain[dcId]
            val shuffled = domains.shuffled()
            return buildList {
                if (current != null) add(current)
                for (domain in shuffled) {
                    if (domain != current) add(domain)
                }
            }
        }
    }

    fun markActiveDomain(dcId: Int, domain: String) {
        synchronized(lock) {
            dcToDomain = dcToDomain + (dcId to domain)
        }
    }

    private fun updateDomains(newDomains: List<String>) {
        val unique = newDomains.map(String::trim).filter(String::isNotBlank).distinct()
        if (unique.isEmpty()) return
        synchronized(lock) {
            domains = unique
            dcToDomain = chooseDcDomains(unique)
        }
    }

    private fun fetchDomains(): List<String> {
        val url = URL("$CFPROXY_DOMAINS_URL?${System.currentTimeMillis()}")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "TG-for-Work-Android")
        }
        return connection.inputStream.bufferedReader().useLines { lines ->
            lines.map(String::trim)
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map(::decodeDomain)
                .distinct()
                .toList()
        }
    }

    private fun chooseDcDomains(source: List<String>): Map<Int, String> {
        if (source.isEmpty()) return emptyMap()
        return listOf(1, 2, 3, 4, 5, 203).associateWith { source[Random.nextInt(source.size)] }
    }

    companion object {
        private const val CFPROXY_DOMAINS_URL =
            "https://raw.githubusercontent.com/Flowseal/tg-ws-proxy/main/.github/cfproxy-domains.txt"
        private val DEFAULT_ENCODED_DOMAINS = listOf(
            "virkgj.com",
            "vmmzovy.com",
            "mkuosckvso.com",
            "zaewayzmplad.com",
            "twdmbzcm.com",
            "awzwsldi.com",
            "clngqrflngqin.com",
            "tjacxbqtj.com"
        )
        private const val DECODED_SUFFIX = ".co.uk"

        fun decodeDomain(value: String): String {
            if (!value.endsWith(".com")) return value
            val prefix = value.removeSuffix(".com")
            val shift = prefix.count(Char::isLetter)
            val decoded = prefix.map { char ->
                if (!char.isLetter()) {
                    char
                } else {
                    val base = if (char >= 'a') 'a'.code else 'A'.code
                    (((char.code - base - shift).floorMod(26)) + base).toChar()
                }
            }.joinToString("")
            return decoded + DECODED_SUFFIX
        }

        private fun Int.floorMod(mod: Int): Int = ((this % mod) + mod) % mod
    }
}
