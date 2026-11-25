package com.flowseal.tgwsproxy

internal object AdaptiveLayout {
    fun horizontalPadding(width: Int, maxWidth: Int, left: Int, right: Int): Pair<Int, Int> {
        val extra = (width - left - right - maxWidth).coerceAtLeast(0)
        val leading = extra / 2
        return (left + leading) to (right + extra - leading)
    }
}
