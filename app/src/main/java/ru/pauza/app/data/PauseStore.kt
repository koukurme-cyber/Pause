package ru.pauza.app.data

import android.content.Context

class PauseStore(context: Context) {
    private val prefs = context.getSharedPreferences("pause_store", Context.MODE_PRIVATE)

    var selectedPackages: Set<String>
        get() = prefs.getStringSet(KEY_SELECTED, emptySet())?.toSet().orEmpty()
        set(value) { prefs.edit().putStringSet(KEY_SELECTED, value).apply() }

    var sessionEndEpochMs: Long
        get() = prefs.getLong(KEY_SESSION_END, 0L)
        set(value) { prefs.edit().putLong(KEY_SESSION_END, value).apply() }

    var protectionStartEpochMs: Long
        get() = prefs.getLong(KEY_PROTECTION_START, 0L)
        set(value) { prefs.edit().putLong(KEY_PROTECTION_START, value).apply() }

    var authorizedForegroundPackage: String
        get() = prefs.getString(KEY_AUTHORIZED_FOREGROUND, "").orEmpty()
        set(value) { prefs.edit().putString(KEY_AUTHORIZED_FOREGROUND, value).apply() }

    var firstSetupCompleted: Boolean
        get() = prefs.getBoolean(KEY_FIRST_SETUP_COMPLETED, false)
        set(value) { prefs.edit().putBoolean(KEY_FIRST_SETUP_COMPLETED, value).apply() }

    fun clearSession() {
        prefs.edit()
            .remove(KEY_SESSION_END)
            .remove(KEY_PROTECTION_START)
            .remove(KEY_AUTHORIZED_FOREGROUND)
            .apply()
    }

    companion object {
        private const val KEY_SELECTED = "selected_packages"
        private const val KEY_SESSION_END = "session_end_epoch_ms"
        private const val KEY_PROTECTION_START = "protection_start_epoch_ms"
        private const val KEY_AUTHORIZED_FOREGROUND = "authorized_foreground_package"
        private const val KEY_FIRST_SETUP_COMPLETED = "first_setup_completed"
    }
}
