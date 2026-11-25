package com.flowseal.tgwsproxy

import androidx.appcompat.app.AppCompatDelegate
import org.junit.Assert.assertEquals
import org.junit.Test

class AppearanceModeTest {
    @Test fun absentOrUnknownPreferenceFollowsSystem() {
        assertEquals(AppearanceMode.SYSTEM, AppearanceMode.fromKey(null))
        assertEquals(AppearanceMode.SYSTEM, AppearanceMode.fromKey("unknown"))
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, AppearanceMode.SYSTEM.nightMode)
    }

    @Test fun savedModesRoundTripWithStableKeys() {
        for (mode in AppearanceMode.entries) assertEquals(mode, AppearanceMode.fromKey(mode.key))
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, AppearanceMode.LIGHT.nightMode)
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, AppearanceMode.DARK.nightMode)
    }
}
