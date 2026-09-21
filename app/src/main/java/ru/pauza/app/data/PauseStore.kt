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

    var blockShortVideos: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_SHORT_VIDEOS, false)
        set(value) { prefs.edit().putBoolean(KEY_BLOCK_SHORT_VIDEOS, value).apply() }

    var firstSetupCompleted: Boolean
        get() = prefs.getBoolean(KEY_FIRST_SETUP_COMPLETED, false)
        set(value) { prefs.edit().putBoolean(KEY_FIRST_SETUP_COMPLETED, value).apply() }

    fun clearSession() {
        prefs.edit().remove(KEY_SESSION_END).apply()
    }

    companion object {
        private const val KEY_SELECTED = "selected_packages"
        private const val KEY_SESSION_END = "session_end_epoch_ms"
        private const val KEY_BLOCK_SHORT_VIDEOS = "block_short_videos"
        private const val KEY_FIRST_SETUP_COMPLETED = "first_setup_completed"
    }
}
