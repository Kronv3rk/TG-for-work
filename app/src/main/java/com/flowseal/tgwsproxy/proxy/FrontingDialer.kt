package com.flowseal.tgwsproxy.proxy

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Upstream pool.py tries plain and domain-fronted TLS in the order that
 * last succeeded, then automatically tries the other mode if it fails.
 *
 * Domain-fronting is explicitly opt-in on Android: do not disable hostname
 * checks or alter the TLS server name unless the user enabled that route.
 */
internal class FrontingDialer<T>(
    private val normal: (host: String, domain: String) -> T,
    private val fronted: (host: String, domain: String) -> T,
    private val enabled: () -> Boolean
) {
    private val frontingPreferred = AtomicBoolean(false)

    fun connect(host: String, domain: String): T {
        if (!enabled()) return normal(host, domain)
        val preferFronted = frontingPreferred.get()
        var lastFailure: Exception? = null
        val attempts = if (preferFronted) listOf(true, false) else listOf(false, true)
        for (useFronting in attempts) {
            try {
                val result = if (useFronting) fronted(host, domain) else normal(host, domain)
                frontingPreferred.set(useFronting)
                return result
            } catch (error: Exception) {
                lastFailure = error
            }
        }
        throw lastFailure ?: IOException("No transport mode available")
    }

    fun reset() { frontingPreferred.set(false) }
}
