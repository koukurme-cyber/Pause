package ru.pauza.app.domain

import android.content.Context
import ru.pauza.app.data.PauseStore
import java.util.Calendar

object SavedSetScheduleEngine {
    private const val START_GRACE_MS = 2 * 60_000L
    private const val OCCURRENCE_RETENTION_MS = 2 * 24 * 60 * 60_000L

    fun maybeStart(
        context: Context,
        store: PauseStore,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val activeSessionEnd = store.sessionEndEpochMs

        val nowCalendar = Calendar.getInstance().apply {
            timeInMillis = nowEpochMs
        }
        val isoDay = when (nowCalendar.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> 1
            Calendar.TUESDAY -> 2
            Calendar.WEDNESDAY -> 3
            Calendar.THURSDAY -> 4
            Calendar.FRIDAY -> 5
            Calendar.SATURDAY -> 6
            else -> 7
        }

        val handled = store.handledScheduleOccurrences
            .filterTo(mutableSetOf()) { occurrence ->
                val epoch = occurrence.substringAfterLast(':').toLongOrNull()
                epoch == null || nowEpochMs - epoch <= OCCURRENCE_RETENTION_MS
            }

        data class DueOccurrence(
            val savedSet: PauseStore.SavedAppSet,
            val schedule: PauseStore.SavedSetSchedule,
            val scheduledAt: Long,
            val key: String,
        )

        val due = mutableListOf<DueOccurrence>()

        store.savedAppSets.forEach { savedSet ->
            savedSet.schedules.forEach { schedule ->
                if (!schedule.enabled || isoDay !in schedule.daysOfWeek) return@forEach

                val scheduledAt = Calendar.getInstance().apply {
                    timeInMillis = nowEpochMs
                    set(Calendar.HOUR_OF_DAY, schedule.startMinuteOfDay / 60)
                    set(Calendar.MINUTE, schedule.startMinuteOfDay % 60)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis

                if (nowEpochMs < scheduledAt) return@forEach
                if (nowEpochMs - scheduledAt > START_GRACE_MS) return@forEach

                val key = "${schedule.id}:$scheduledAt"
                if (key in handled) return@forEach

                due += DueOccurrence(
                    savedSet = savedSet,
                    schedule = schedule,
                    scheduledAt = scheduledAt,
                    key = key,
                )
            }
        }

        if (due.isEmpty()) {
            if (handled != store.handledScheduleOccurrences) {
                store.handledScheduleOccurrences = handled
            }
            return false
        }

        // Mark every simultaneous due rule as handled. If several rules overlap,
        // the first one starts and the others do not fire a few seconds later.
        handled += due.map { it.key }
        store.handledScheduleOccurrences = handled

        if (activeSessionEnd > nowEpochMs) {
            ShortVideoDiagnostics.log(
                context,
                "PauseSchedule",
                "scheduled occurrence skipped because another pause is active"
            )
            return false
        }

        val occurrence = due.minByOrNull { it.scheduledAt } ?: return false
        val scheduledEnd =
            occurrence.scheduledAt + occurrence.schedule.durationMinutes * 60_000L

        if (scheduledEnd <= nowEpochMs) return false

        store.selectedPackages = occurrence.savedSet.packages
        store.activeSavedSetName = occurrence.savedSet.name
        store.sessionEndEpochMs = scheduledEnd

        NotificationSilencer.applyForPause(context, store)

        ShortVideoDiagnostics.log(
            context,
            "PauseSchedule",
            "scheduled pause started set=${occurrence.savedSet.name} " +
                "schedule=${occurrence.schedule.id} end=$scheduledEnd"
        )

        return true
    }
}
