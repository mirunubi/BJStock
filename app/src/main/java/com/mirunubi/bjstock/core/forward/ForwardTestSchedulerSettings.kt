package com.mirunubi.bjstock.core.forward

import android.content.Context
import android.content.SharedPreferences

class ForwardTestSchedulerSettings(
    context: Context,
) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isAutoEnabled(): Boolean =
        preferences.getBoolean(KEY_AUTO, false)

    fun setAutoEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_AUTO, enabled).apply()
    }

    companion object {
        private const val PREFS = "bjstock_forward_test"
        private const val KEY_AUTO = "forward_test_auto_enabled"
    }
}
