package com.flowseal.tgwsproxy

import java.util.concurrent.Executor

internal class ProxyCommandQueue(
    private val worker: Executor,
    private val deliver: (Runnable) -> Unit
) {
    fun submit(operation: () -> Unit, cleanupOnFailure: () -> Unit, complete: (Result<Unit>) -> Unit) {
        worker.execute {
            val result = runCatching(operation)
            if (result.isFailure) runCatching(cleanupOnFailure)
            deliver(Runnable { complete(result) })
        }
    }
}
