package ru.pauza.app.domain

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import ru.pauza.app.data.BootSessionStore

class DirectBootWarmupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val end = BootSessionStore.sessionEndEpochMs(context)
        if (end <= System.currentTimeMillis()) {
            Log.i("PauseBootWarmup", "No active session on $action")
            return
        }

        Log.i("PauseBootWarmup", "Starting warmup on $action; sessionEnd=$end")
        runCatching {
            context.startForegroundService(
                Intent(context, BootWarmupService::class.java)
                    .putExtra(BootWarmupService.EXTRA_TRIGGER, action)
            )
        }.onFailure {
            Log.w("PauseBootWarmup", "Warmup start failed on $action", it)
        }
    }
}
