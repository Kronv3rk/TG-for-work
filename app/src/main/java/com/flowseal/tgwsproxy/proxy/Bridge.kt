package com.flowseal.tgwsproxy.proxy

/**
 * Coroutine-agnostic bridge loop helper.
 *
 * Keeps looping while [isActive] returns true and invokes [block] on each iteration.
 * This file intentionally avoids external coroutine library imports so it can compile
 * in minimal environments.
 */
object Bridge {
    suspend fun run(isActive: () -> Boolean, block: suspend () -> Unit) {
        while (isActive()) {
            block()
        }
    }
}
