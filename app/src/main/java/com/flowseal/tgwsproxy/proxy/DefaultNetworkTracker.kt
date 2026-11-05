package com.flowseal.tgwsproxy.proxy

/**
 * Remembers the Android default network so duplicate capability callbacks do not
 * repeatedly tear down healthy relay routes.
 *
 * The Android service calls this exclusively from its dedicated network handler.
 * This type intentionally has no Android dependencies and is unit-testable.
 */
internal class DefaultNetworkTracker {
    private data class Snapshot(val id: String?, val cellular: Boolean, val wifi: Boolean)

    private var initialized = false
    private var current: Snapshot? = null

    /**
     * True only when the network, rather than incidental capability metadata, changed.
     * The first observation is a baseline, not an artificial handover.
     */
    fun changed(networkId: String, cellular: Boolean, wifi: Boolean): Boolean {
        val next = Snapshot(networkId, cellular, wifi)
        val shouldReset = initialized && current != next
        initialized = true
        current = next
        return shouldReset
    }

    /**
     * Ignore late onLost callbacks for a network that is no longer the default.
     */
    fun lost(networkId: String): Boolean {
        if (!initialized || current?.id != networkId) return false
        current = Snapshot(null, cellular = false, wifi = false)
        return true
    }
}
