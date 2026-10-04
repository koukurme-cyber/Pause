package ru.pauza.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class PauseStore(context: Context) {
    data class SavedSetSchedule(
        val id: String,
        val enabled: Boolean = true,
        val daysOfWeek: Set<Int> = setOf(1, 2, 3, 4, 5),
        val startMinuteOfDay: Int = 8 * 60 + 30,
        val durationMinutes: Int = 60,
    )

    data class SavedAppSet(
        val name: String,
        val packages: Set<String>,
        val schedules: List<SavedSetSchedule> = emptyList(),
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

    var lastScheduleOccurrenceKey: String?
        get() = prefs.getString(KEY_LAST_SCHEDULE_OCCURRENCE, null)
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove(KEY_LAST_SCHEDULE_OCCURRENCE)
                else putString(KEY_LAST_SCHEDULE_OCCURRENCE, value)
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

    var blockShortVideos: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_SHORT_VIDEOS, false)
        set(value) { prefs.edit().putBoolean(KEY_BLOCK_SHORT_VIDEOS, value).apply() }

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

                    val schedulesJson = item.optJSONArray("schedules") ?: JSONArray()
                    val schedules = buildList {
                        for (
                            scheduleIndex in 0 until minOf(
                                schedulesJson.length(),
                                MAX_SCHEDULES_PER_SET
                            )
                        ) {
                            val schedule = schedulesJson.optJSONObject(scheduleIndex) ?: continue
                            val id = schedule.optString("id").trim().ifBlank {
                                "schedule-$index-$scheduleIndex"
                            }
                            val daysJson = schedule.optJSONArray("days") ?: JSONArray()
                            val days = buildSet {
                                for (dayIndex in 0 until daysJson.length()) {
                                    val day = daysJson.optInt(dayIndex)
                                    if (day in 1..7) add(day)
                                }
                            }
                            if (days.isEmpty()) continue

                            add(
                                SavedSetSchedule(
                                    id = id.take(80),
                                    enabled = schedule.optBoolean("enabled", true),
                                    daysOfWeek = days,
                                    startMinuteOfDay = schedule
                                        .optInt("startMinuteOfDay", 8 * 60 + 30)
                                        .coerceIn(0, 24 * 60 - 1),
                                    durationMinutes = schedule
                                        .optInt("durationMinutes", 60)
                                        .coerceIn(1, MAX_SCHEDULE_DURATION_MINUTES),
                                )
                            )
                        }
                    }

                    add(
                        SavedAppSet(
                            name = name.take(MAX_SAVED_APP_SET_NAME_LENGTH),
                            packages = packages,
                            schedules = schedules,
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

            val schedules = JSONArray()
            savedSet.schedules
                .take(MAX_SCHEDULES_PER_SET)
                .forEach { schedule ->
                    val days = JSONArray()
                    schedule.daysOfWeek
                        .asSequence()
                        .filter { it in 1..7 }
                        .sorted()
                        .forEach(days::put)

                    if (days.length() > 0) {
                        schedules.put(
                            JSONObject()
                                .put("id", schedule.id.take(80))
                                .put("enabled", schedule.enabled)
                                .put("days", days)
                                .put(
                                    "startMinuteOfDay",
                                    schedule.startMinuteOfDay.coerceIn(0, 24 * 60 - 1)
                                )
                                .put(
                                    "durationMinutes",
                                    schedule.durationMinutes.coerceIn(
                                        1,
                                        MAX_SCHEDULE_DURATION_MINUTES
                                    )
                                )
                        )
                    }
                }

            array.put(
                JSONObject()
                    .put("name", savedSet.name.trim().take(MAX_SAVED_APP_SET_NAME_LENGTH))
                    .put("packages", packages)
                    .put("schedules", schedules)
            )
        }

        return array.toString()
    }

    companion object {
        const val MAX_SAVED_APP_SETS = 5
        const val MAX_SAVED_APP_SET_NAME_LENGTH = 32
        const val MAX_SCHEDULES_PER_SET = 8
        const val MAX_SCHEDULE_DURATION_MINUTES = 30 * 24 * 60

        private const val KEY_SELECTED = "selected_packages"
        private const val KEY_SAVED_APP_SETS = "saved_app_sets"
        private const val KEY_ACTIVE_SAVED_SET_NAME = "active_saved_set_name"
        private const val KEY_LAST_SCHEDULE_OCCURRENCE = "last_schedule_occurrence"
        private const val KEY_SESSION_END = "session_end_epoch_ms"
        private const val KEY_FIRST_SETUP_COMPLETED = "first_setup_completed"
        private const val KEY_RESTRICTED_SETTINGS_CONFIRMED = "restricted_settings_confirmed"
        private const val KEY_SETUP_CHECKLIST_COMPLETED = "setup_checklist_completed"
        private const val KEY_TEST_MODE_ENABLED = "test_mode_enabled"
        private const val KEY_BLOCK_SHORT_VIDEOS = "block_short_videos"
        private const val KEY_START_VIBRATION_ENABLED = "start_vibration_enabled"
        private const val KEY_SUPPRESS_NOTIFICATIONS_ENABLED = "suppress_notifications_enabled"
        private const val KEY_NOTIFICATION_SILENCING_ACTIVE = "notification_silencing_active"
        private const val KEY_PREVIOUS_INTERRUPTION_FILTER = "previous_interruption_filter"
    }
}
