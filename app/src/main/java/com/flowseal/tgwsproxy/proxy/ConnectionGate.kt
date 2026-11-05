package com.flowseal.tgwsproxy.proxy

import java.util.concurrent.Semaphore

/** Bounds concurrent decoy-domain forwarding so malformed clients cannot create
 * arbitrarily many socket pairs and platform threads. */
internal class ConnectionGate(maxConcurrent: Int) {
    private val permits = Semaphore(maxConcurrent)

    fun withPermit(block: () -> Unit): Boolean {
        if (!permits.tryAcquire()) return false
        try {
            block()
            return true
        } finally {
            permits.release()
        }
    }
}
