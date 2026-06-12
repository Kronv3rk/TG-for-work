package com.flowseal.tgwsproxy.proxy

import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ExecutorService
import kotlin.random.Random

class CfProxyBalancer {
    private val lock = Any()
    private var domains: List<String> = DEFAULT_ENCODED_DOMAINS.map(::decodeDomain)
    private var dcToDomain: Map<Int, String> = chooseDcDomains(domains)

    fun setUserDomain(domain: String) {
        val clean = normalizeDomain(domain) ?: return
        updateDomains(listOf(clean), minValidDomains = 1)
    }

    fun refreshAsync(executor: ExecutorService) {
        executor.execute {
            val fetched = runCatching { fetchDomains() }.getOrDefault(emptyList())
            if (fetched.isNotEmpty()) {
                updateDomains(fetched, minValidDomains = MIN_FETCHED_VALID_DOMAINS)
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

    private fun updateDomains(newDomains: List<String>, minValidDomains: Int) {
        val unique = newDomains.mapNotNull(::normalizeDomain).distinct()
        if (unique.size < minValidDomains) return
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
        return connection.inputStream.bufferedReader().use { reader ->
            reader.readText()
                .lineSequence()
                .flatMap { line ->
                    line.substringBefore("#")
                        .trim()
                        .splitToSequence(WHITESPACE_REGEX)
                }
                .filter(String::isNotBlank)
                .map(::decodeDomain)
                .mapNotNull(::normalizeDomain)
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
        private const val MIN_FETCHED_VALID_DOMAINS = 3
        private val WHITESPACE_REGEX = Regex("\\s+")
        private val DEFAULT_ENCODED_DOMAINS = listOf(
            "virkgj.com",
            "vmmzovy.com",
            "mkuosckvso.com",
            "zaewayzmplad.com",
            "twdmbzcm.com",
            "awzwsldi.com",
            "clngqrflngqin.com",
            "tjacxbqtj.com",
            "bxaxtxmrw.com",
            "dmohrsgmohcrwb.com",
            "vwbmtmoi.com",
            "khgrre.com",
            "ulihssf.com",
            "tmhqsdqmfpmk.com",
            "xwuwoqbm.com"
        )
        private const val DECODED_SUFFIX = ".co.uk"

        fun decodeDomain(value: String): String {
            val clean = value.trim()
            if (!clean.endsWith(".com")) return clean
            val prefix = clean.removeSuffix(".com")
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

        fun normalizeDomain(value: String): String? {
            val domain = value.trim().lowercase(Locale.US)
            if (!isValidDomain(domain)) return null
            return domain
        }

        fun isValidDomain(domain: String): Boolean {
            if (domain.isBlank() || domain.length > 253) return false
            if (domain.startsWith('.') || domain.endsWith('.')) return false
            val labels = domain.split('.')
            if (labels.size < 2) return false
            for (label in labels) {
                if (label.isBlank() || label.length > 63) return false
                if (label.startsWith('-') || label.endsWith('-')) return false
                if (!label.all { it.isLetterOrDigit() || it == '-' }) return false
            }
            val tld = labels.last()
            return tld.length >= 2 && tld.any(Char::isLetter)
        }

        private fun Int.floorMod(mod: Int): Int = ((this % mod) + mod) % mod
    }
}
