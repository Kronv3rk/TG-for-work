package com.flowseal.tgwsproxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SettingsDraftStateTest {
    @Test fun recreatedScreenConsumesDraftOnlyOnce() {
        val state = SettingsDraftState()
        val draft = SettingsDraft(mapOf(1 to "1444", 2 to "temporary"), mapOf(3 to false), true, true, 240)
        state.retain(draft)
        assertSame(draft, state.take())
        assertNull(state.take())
    }

    @Test fun cancellationClearsRetainedDraft() {
        val state = SettingsDraftState()
        state.retain(SettingsDraft(mapOf(1 to "invalid"), emptyMap(), false, false, 0))
        state.retain(null)
        assertNull(state.take())
    }
}
