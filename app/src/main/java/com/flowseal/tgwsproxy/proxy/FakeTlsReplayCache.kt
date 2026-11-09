package com.flowseal.tgwsproxy.proxy

/**
 * One process-wide cache for authenticated Fake TLS client_random values.
 *
 * A replayed ClientHello is indistinguishable from a probing attempt and must
 * be forwarded to the camouflage site rather than answered as an MTProxy.
 * Entries expire after the ±120 second timestamp acceptance window plus 1 s.
 */
internal class FakeTlsReplayCache(
    private val windowMs: Long = 241_000L,
    private val maxEntries: Int = 65_536,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    init {
        require(windowMs > 0 && maxEntries > 0)
    }

    private class Key(bytes: ByteArray) {
        private val random = bytes.copyOf()
        override fun equals(other: Any?): Boolean =
            other is Key && random.contentEquals(other.random)
        override fun hashCode(): Int = random.contentHashCode()
    }

    // Linked insertion order also represents expiration order.
    private val expiry = LinkedHashMap<Key, Long>()

    @Synchronized
    fun firstSeen(clientRandom: ByteArray): Boolean {
        require(clientRandom.size == 32) { "Fake TLS client random must be 32 bytes" }
        val now = clock()
        val iterator = expiry.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value > now) break
            iterator.remove()
        }
        val key = Key(clientRandom)
        if (expiry.containsKey(key)) return false
        if (expiry.size >= maxEntries) {
            val eldest = expiry.keys.iterator()
            if (eldest.hasNext()) {
                eldest.next()
                eldest.remove()
            }
        }
        expiry[key] = now + windowMs
        return true
    }
}
