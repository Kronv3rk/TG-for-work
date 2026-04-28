package com.flowseal.tgwsproxy.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive

/**
 * Minimal bridge loop helper.
 *
 * Previous content had placeholder text and top-level `while` blocks,
 * which makes Kotlin source invalid and breaks APK compilation.
 */
object Bridge {
    suspend fun run(scope: CoroutineScope, block: suspend () -> Unit) {
        while (scope.isActive) {
            block()
        }
    }
}
