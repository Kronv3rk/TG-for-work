package com.flowseal.tgwsproxy

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

internal enum class AppearanceMode(val key: String, val label: Int, val nightMode: Int) {
    SYSTEM("system", R.string.appearance_system, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    LIGHT("light", R.string.appearance_light, AppCompatDelegate.MODE_NIGHT_NO),
    DARK("dark", R.string.appearance_dark, AppCompatDelegate.MODE_NIGHT_YES);

    companion object {
        fun fromKey(key: String?): AppearanceMode = entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

internal object AppearancePreferences {
    private const val PREFS = "appearance"
    private const val KEY = "mode"

    fun load(context: Context): AppearanceMode =
        AppearanceMode.fromKey(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    fun save(context: Context, mode: AppearanceMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, mode.key).apply()
    }
}
