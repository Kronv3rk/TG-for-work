package com.flowseal.tgwsproxy

import androidx.lifecycle.ViewModel

internal class SettingsDraft(
    val text: Map<Int, String>,
    val checked: Map<Int, Boolean>,
    val advancedExpanded: Boolean,
    val secretExpanded: Boolean,
    val scrollY: Int
)

internal class SettingsDraftState : ViewModel() {
    private var draft: SettingsDraft? = null

    fun retain(value: SettingsDraft?) {
        draft = value
    }

    fun take(): SettingsDraft? = draft.also { draft = null }
}
