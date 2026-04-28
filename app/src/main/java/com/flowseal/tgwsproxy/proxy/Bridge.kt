package com.flowseal.tgwsproxy.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

/**
 * Minimal bridge loop helper.
 */
object Bridge {
    suspend inline fun runWhileActive(crossinline block: suspend () -> Unit) {
        while (true) {
            coroutineContext.ensureActive()
            block()
        }
    }

    suspend inline fun run(scope: CoroutineScope, crossinline block: suspend () -> Unit) {
        while (scope.isActive) {
            block()
        }
    }

    suspend inline fun run(isActive: () -> Boolean, crossinline block: suspend () -> Unit) {
        while (isActive()) {
            block()
        }
    }
}
