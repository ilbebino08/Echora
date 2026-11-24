package com.echora.app.capture

import android.content.Context
import android.content.SharedPreferences

class Settings(private val prefs: SharedPreferences) {

    companion object {
        private const val PREFS_NAME = "echora"
        private const val KEY_BUFFER_MINUTES = "bufferMinutes"
        private const val KEY_LISTENING_ENABLED = "listeningEnabled"
        private const val KEY_START_ON_BOOT = "startOnBoot"

        private const val DEFAULT_BUFFER_MINUTES = 10
        private const val MIN_BUFFER_MINUTES = 1
        private const val MAX_BUFFER_MINUTES = 60

        fun clampMinutes(v: Int): Int = v.coerceIn(MIN_BUFFER_MINUTES, MAX_BUFFER_MINUTES)

        fun create(context: Context): Settings = Settings(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        )
    }

    var bufferMinutes: Int
        get() = clampMinutes(prefs.getInt(KEY_BUFFER_MINUTES, DEFAULT_BUFFER_MINUTES))
        set(value) {
            prefs.edit().putInt(KEY_BUFFER_MINUTES, clampMinutes(value)).apply()
        }

    var listeningEnabled: Boolean
        get() = prefs.getBoolean(KEY_LISTENING_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_LISTENING_ENABLED, value).apply()
        }

    var startOnBoot: Boolean
        get() = prefs.getBoolean(KEY_START_ON_BOOT, true)
        set(value) {
            prefs.edit().putBoolean(KEY_START_ON_BOOT, value).apply()
        }
}