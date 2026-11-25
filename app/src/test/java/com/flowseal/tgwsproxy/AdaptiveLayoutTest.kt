package com.flowseal.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveLayoutTest {
    @Test fun normalPhonePreservesInsets() {
        assertEquals(0 to 0, AdaptiveLayout.horizontalPadding(432, 560, 0, 0))
        assertEquals(16 to 24, AdaptiveLayout.horizontalPadding(480, 560, 16, 24))
    }

    @Test fun wideWindowCentersReadableContent() {
        assertEquals(120 to 120, AdaptiveLayout.horizontalPadding(800, 560, 0, 0))
    }

    @Test fun landscapeCutoutCentersWithinSafeArea() {
        assertEquals(196 to 156, AdaptiveLayout.horizontalPadding(912, 560, 52, 12))
    }

    @Test fun oddWidthDistributesRemainderWithoutOverflow() {
        assertEquals(120 to 121, AdaptiveLayout.horizontalPadding(801, 560, 0, 0))
    }
}
