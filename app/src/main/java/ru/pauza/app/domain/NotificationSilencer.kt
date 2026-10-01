package ru.pauza.app.domain

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import ru.pauza.app.data.PauseStore

/**
 * Applies Android's Do Not Disturb interruption filter for an active Pause.
 *
 * This intentionally suppresses audible / heads-up interruptions without deleting
 * notifications. Notifications remain available in the notification shade after
 * the Pause and the user's previous interruption filter is restored.
 */
object NotificationSilencer {
    fun isPolicyAccessGranted(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java)
            ?.isNotificationPolicyAccessGranted == true

    fun openPolicyAccessSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun applyForPause(context: Context, store: PauseStore): Boolean {
        if (!store.suppressNotificationsEnabled) return false

        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!manager.isNotificationPolicyAccessGranted) return false

        if (!store.notificationSilencingActive) {
            store.previousInterruptionFilter = manager.currentInterruptionFilter
        }

        return runCatching {
            manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
            store.notificationSilencingActive = true
            true
        }.getOrDefault(false)
    }

    fun restoreAfterPause(context: Context, store: PauseStore) {
        if (!store.notificationSilencingActive) return

        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager == null || !manager.isNotificationPolicyAccessGranted) {
            clearSavedState(store)
            return
        }

        val previous = store.previousInterruptionFilter
        val filter = when (previous) {
            NotificationManager.INTERRUPTION_FILTER_ALL,
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            NotificationManager.INTERRUPTION_FILTER_ALARMS,
            NotificationManager.INTERRUPTION_FILTER_NONE -> previous
            else -> NotificationManager.INTERRUPTION_FILTER_ALL
        }

        runCatching {
            manager.setInterruptionFilter(filter)
        }
        clearSavedState(store)
    }

    private fun clearSavedState(store: PauseStore) {
        store.notificationSilencingActive = false
        store.previousInterruptionFilter = -1
    }
}
