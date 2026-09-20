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

    fun clearSession() {
        prefs.edit().remove(KEY_SESSION_END).apply()
    }

    companion object {
        private const val KEY_SELECTED = "selected_packages"
        private const val KEY_SESSION_END = "session_end_epoch_ms"
    }
}
