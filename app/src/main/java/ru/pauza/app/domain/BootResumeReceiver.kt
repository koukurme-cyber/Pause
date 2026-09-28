package ru.pauza.app.domain

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import ru.pauza.app.MainActivity
import ru.pauza.app.data.PauseStore

class BootResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (
            action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_USER_UNLOCKED &&
            action != Intent.ACTION_USER_PRESENT
        ) {
            return
        }

        val end = PauseStore(context).sessionEndEpochMs
        if (end <= System.currentTimeMillis()) return

        Log.i("PauseBoot", "Active Pause session detected on $action")

        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                }
            )
        }.onFailure {
            Log.w("PauseBoot", "Early Pause activity launch was not allowed", it)
        }
    }
}
