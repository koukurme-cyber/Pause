package ru.pauza.app.domain

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings

object UsageAccessMonitor {
    fun isGranted(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            }

        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun openSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                if (context !is android.app.Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        )
    }

    fun foregroundPackage(context: Context): String? {
        if (!isGranted(context)) return null

        val manager = context.getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val events = manager.queryEvents(now - LOOKBACK_MS, now)
        val event = UsageEvents.Event()

        var packageName: String? = null
        var timestamp = 0L

        while (events.hasNextEvent()) {
            events.getNextEvent(event)

            val resumed =
                event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                    (
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                            event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                        )

            if (resumed && event.timeStamp >= timestamp) {
                packageName = event.packageName
                timestamp = event.timeStamp
            }
        }

        return packageName
    }

    private const val LOOKBACK_MS = 10_000L
}
