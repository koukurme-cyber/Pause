package ru.pauza.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class PauseStore(context: Context) {
    data class SavedAppSet(
        val name: String,
        val packages: Set<String>,
    )
    private val prefs = context.getSharedPreferences("pause_store", Context.MODE_PRIVATE)

    var selectedPackages: Set<String>
        get() = prefs.getStringSet(KEY_SELECTED, emptySet())?.toSet().orEmpty()
        set(value) { prefs.edit().putStringSet(KEY_SELECTED, value).apply() }

    var savedAppSets: List<SavedAppSet>
        get() = decodeSavedAppSets(prefs.getString(KEY_SAVED_APP_SETS, null))
        set(value) {
            val limited = value.take(MAX_SAVED_APP_SETS)
            prefs.edit()
                .putString(KEY_SAVED_APP_SETS, encodeSavedAppSets(limited))
                .apply()
        }

    var activeSavedSetName: String?
        get() = prefs.getString(KEY_ACTIVE_SAVED_SET_NAME, null)
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) {
                    remove(KEY_ACTIVE_SAVED_SET_NAME)
                } else {
                    putString(
                        KEY_ACTIVE_SAVED_SET_NAME,
                        value.trim().take(MAX_SAVED_APP_SET_NAME_LENGTH)
                    )
                }
            }.apply()
        }

    var sessionEndEpochMs: Long
        get() = prefs.getLong(KEY_SESSION_END, 0L)
        set(value) { prefs.edit().putLong(KEY_SESSION_END, value).apply() }

    var firstSetupCompleted: Boolean
        get() = prefs.getBoolean(KEY_FIRST_SETUP_COMPLETED, false)
        set(value) { prefs.edit().putBoolean(KEY_FIRST_SETUP_COMPLETED, value).apply() }

    var restrictedSettingsConfirmed: Boolean
        get() = prefs.getBoolean(KEY_RESTRICTED_SETTINGS_CONFIRMED, false)
        set(value) { prefs.edit().putBoolean(KEY_RESTRICTED_SETTINGS_CONFIRMED, value).apply() }

    var setupChecklistCompleted: Boolean
        get() = prefs.getBoolean(KEY_SETUP_CHECKLIST_COMPLETED, false)
        set(value) { prefs.edit().putBoolean(KEY_SETUP_CHECKLIST_COMPLETED, value).apply() }

    var testModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_TEST_MODE_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_TEST_MODE_ENABLED, value).apply() }

    var startVibrationEnabled: Boolean
        get() = prefs.getBoolean(KEY_START_VIBRATION_ENABLED, true)
        set(value) { prefs.edit().putBoolean(KEY_START_VIBRATION_ENABLED, value).apply() }

    var suppressNotificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_SUPPRESS_NOTIFICATIONS_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_SUPPRESS_NOTIFICATIONS_ENABLED, value).apply() }

    var notificationSilencingActive: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION_SILENCING_ACTIVE, false)
        set(value) { prefs.edit().putBoolean(KEY_NOTIFICATION_SILENCING_ACTIVE, value).apply() }

    var previousInterruptionFilter: Int
        get() = prefs.getInt(KEY_PREVIOUS_INTERRUPTION_FILTER, -1)
        set(value) { prefs.edit().putInt(KEY_PREVIOUS_INTERRUPTION_FILTER, value).apply() }

    fun clearSession() {
        prefs.edit().remove(KEY_SESSION_END).apply()
    }

    private fun decodeSavedAppSets(raw: String?): List<SavedAppSet> {
        if (raw.isNullOrBlank()) return emptyList()

        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until minOf(array.length(), MAX_SAVED_APP_SETS)) {
                    val item = array.optJSONObject(index) ?: continue
                    val name = item.optString("name").trim()
                    if (name.isBlank()) continue

                    val packagesJson = item.optJSONArray("packages") ?: JSONArray()
                    val packages = buildSet {
                        for (packageIndex in 0 until packagesJson.length()) {
                            val packageName = packagesJson.optString(packageIndex).trim()
                            if (packageName.isNotBlank()) add(packageName)
                        }
                    }

                    add(
                        SavedAppSet(
                            name = name.take(MAX_SAVED_APP_SET_NAME_LENGTH),
                            packages = packages,
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeSavedAppSets(value: List<SavedAppSet>): String {
        val array = JSONArray()

        value.take(MAX_SAVED_APP_SETS).forEach { savedSet ->
            val packages = JSONArray()
            savedSet.packages
                .asSequence()
                .filter { it.isNotBlank() }
                .sorted()
                .forEach(packages::put)

            array.put(
                JSONObject()
                    .put("name", savedSet.name.trim().take(MAX_SAVED_APP_SET_NAME_LENGTH))
                    .put("packages", packages)
            )
        }

        return array.toString()
    }

    companion object {
        const val MAX_SAVED_APP_SETS = 5
        const val MAX_SAVED_APP_SET_NAME_LENGTH = 32

        private const val KEY_SELECTED = "selected_packages"
        private const val KEY_SAVED_APP_SETS = "saved_app_sets"
        private const val KEY_ACTIVE_SAVED_SET_NAME = "active_saved_set_name"
        private const val KEY_SESSION_END = "session_end_epoch_ms"
        private const val KEY_FIRST_SETUP_COMPLETED = "first_setup_completed"
        private const val KEY_RESTRICTED_SETTINGS_CONFIRMED = "restricted_settings_confirmed"
        private const val KEY_SETUP_CHECKLIST_COMPLETED = "setup_checklist_completed"
        private const val KEY_TEST_MODE_ENABLED = "test_mode_enabled"
        private const val KEY_START_VIBRATION_ENABLED = "start_vibration_enabled"
        private const val KEY_SUPPRESS_NOTIFICATIONS_ENABLED = "suppress_notifications_enabled"
        private const val KEY_NOTIFICATION_SILENCING_ACTIVE = "notification_silencing_active"
        private const val KEY_PREVIOUS_INTERRUPTION_FILTER = "previous_interruption_filter"
    }
}
