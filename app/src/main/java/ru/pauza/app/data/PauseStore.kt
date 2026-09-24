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

    // Visual progress metadata only; blocking and expiry still use sessionEndEpochMs.
    var sessionDurationMs: Long
        get() = prefs.getLong("session_duration_ms", 0L)
        set(value) { prefs.edit().putLong("session_duration_ms", value).apply() }

    var firstSetupCompleted: Boolean
        get() = prefs.getBoolean(KEY_FIRST_SETUP_COMPLETED, false)
        set(value) { prefs.edit().putBoolean(KEY_FIRST_SETUP_COMPLETED, value).apply() }

    var restrictedSettingsConfirmed: Boolean
        get() = prefs.getBoolean(KEY_RESTRICTED_SETTINGS_CONFIRMED, false)
        set(value) { prefs.edit().putBoolean(KEY_RESTRICTED_SETTINGS_CONFIRMED, value).apply() }

    var setupChecklistCompleted: Boolean
        get() = prefs.getBoolean(KEY_SETUP_CHECKLIST_COMPLETED, false)
        set(value) { prefs.edit().putBoolean(KEY_SETUP_CHECKLIST_COMPLETED, value).apply() }

    var pendingAllowedLaunchPackage: String?
        get() = prefs.getString(KEY_PENDING_ALLOWED_LAUNCH_PACKAGE, null)
        set(value) {
            val edit = prefs.edit()
            if (value == null) edit.remove(KEY_PENDING_ALLOWED_LAUNCH_PACKAGE)
            else edit.putString(KEY_PENDING_ALLOWED_LAUNCH_PACKAGE, value)
            edit.apply()
        }

    var pendingAllowedLaunchUntilEpochMs: Long
        get() = prefs.getLong(KEY_PENDING_ALLOWED_LAUNCH_UNTIL, 0L)
        set(value) { prefs.edit().putLong(KEY_PENDING_ALLOWED_LAUNCH_UNTIL, value).apply() }

    fun clearPendingAllowedLaunch() {
        prefs.edit()
            .remove(KEY_PENDING_ALLOWED_LAUNCH_PACKAGE)
            .remove(KEY_PENDING_ALLOWED_LAUNCH_UNTIL)
            .apply()
    }

    fun clearSession() {
        prefs.edit()
            .remove(KEY_SESSION_END)
            .remove("session_duration_ms")
            .remove(KEY_PENDING_ALLOWED_LAUNCH_PACKAGE)
            .remove(KEY_PENDING_ALLOWED_LAUNCH_UNTIL)
            .apply()
    }

    companion object {
        private const val KEY_SELECTED = "selected_packages"
        private const val KEY_SESSION_END = "session_end_epoch_ms"
        private const val KEY_FIRST_SETUP_COMPLETED = "first_setup_completed"
        private const val KEY_RESTRICTED_SETTINGS_CONFIRMED = "restricted_settings_confirmed"
        private const val KEY_SETUP_CHECKLIST_COMPLETED = "setup_checklist_completed"
        private const val KEY_PENDING_ALLOWED_LAUNCH_PACKAGE = "pending_allowed_launch_package"
        private const val KEY_PENDING_ALLOWED_LAUNCH_UNTIL = "pending_allowed_launch_until_epoch_ms"
    }
}
