package ru.pauza.app.data

import android.content.Context

object BootSessionStore {
    private const val PREFS_NAME = "pause_boot_store"
    private const val KEY_SESSION_END = "session_end_epoch_ms"

    fun sessionEndEpochMs(context: Context): Long {
        val directBootContext = context.createDeviceProtectedStorageContext()
        return directBootContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_SESSION_END, 0L)
    }

    fun setSessionEndEpochMs(context: Context, value: Long) {
        val directBootContext = context.createDeviceProtectedStorageContext()
        directBootContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_SESSION_END, value)
            .apply()
    }

    fun clearSession(context: Context) {
        val directBootContext = context.createDeviceProtectedStorageContext()
        directBootContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_SESSION_END)
            .apply()
    }
}
