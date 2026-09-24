package ru.pauza.app.domain

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ru.pauza.app.MainActivity
import ru.pauza.app.data.PauseStore

class PauseBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (
            intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_USER_UNLOCKED
        ) {
            return
        }

        val store = PauseStore(context)
        val end = store.sessionEndEpochMs
        val now = System.currentTimeMillis()

        if (end <= 0L) return
        if (now >= end) {
            store.clearSession()
            return
        }

        // A launch that was pending before shutdown is no longer meaningful.
        store.clearPendingAllowedLaunch()

        val keyguard = context.getSystemService(KeyguardManager::class.java)
        if (keyguard.isDeviceLocked) return

        // Android/OEM background-activity policy may defer this launch. The
        // accessibility watchdog remains the fallback and will return the user
        // to Pause as soon as an ordinary foreground surface appears.
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
        }
    }
}
