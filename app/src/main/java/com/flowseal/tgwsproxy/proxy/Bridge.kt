package com.flowseal.tgwsproxy.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
<<<<<<< ours
import kotlin.coroutines.coroutineContext

/**
 * Minimal bridge loop helper.
 */
object Bridge {
=======
import kotlinx.coroutines.yield
import kotlin.coroutines.coroutineContext

/**
 * Small helpers for long-running proxy loops.
 *
 * Each helper performs cooperative cancellation checks and yields after every
 * iteration. That keeps tight loops from monopolizing the dispatcher when the
 * supplied [block] completes quickly.
 */
object Bridge {
    /** Runs [block] until the current coroutine is cancelled. */
>>>>>>> theirs
    suspend inline fun runWhileActive(crossinline block: suspend () -> Unit) {
        while (true) {
            coroutineContext.ensureActive()
            block()
<<<<<<< ours
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
=======
            yield()
        }
    }

    /** Runs [block] while [scope] is active. */
    suspend inline fun run(scope: CoroutineScope, crossinline block: suspend () -> Unit) {
        while (scope.isActive) {
            scope.coroutineContext.ensureActive()
            block()
            yield()
        }
    }

    /** Runs [block] while [isActive] returns true. */
    suspend inline fun run(isActive: () -> Boolean, crossinline block: suspend () -> Unit) {
        while (isActive()) {
            coroutineContext.ensureActive()
            block()
            yield()
>>>>>>> theirs
        }
    }
}
