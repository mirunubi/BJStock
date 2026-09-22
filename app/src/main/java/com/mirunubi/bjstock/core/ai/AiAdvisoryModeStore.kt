package com.mirunubi.bjstock.core.ai

import android.content.Context
import android.content.SharedPreferences

class AiAdvisoryModeStore(
    context: Context,
) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getMode(): AiAdvisoryMode {
        val raw = preferences.getString(KEY_MODE, AiAdvisoryMode.OFF.name) ?: AiAdvisoryMode.OFF.name
        return runCatching { AiAdvisoryMode.valueOf(raw) }.getOrDefault(AiAdvisoryMode.OFF)
    }

    fun setMode(mode: AiAdvisoryMode) {
        preferences.edit().putString(KEY_MODE, mode.name).apply()
    }

    companion object {
        private const val PREFS = "bjstock_ai_advisory"
        private const val KEY_MODE = "mode"
    }
}
