package com.flowseal.tgwsproxy.proxy

internal object RouteAttemptSequence {
    fun <T> run(routes: List<T>, closed: () -> Boolean, attempt: (T) -> Boolean): Boolean {
        for (route in routes) {
            if (closed() || attempt(route)) return true
        }
        return closed()
    }
}
