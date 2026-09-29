package ru.pauza.app.domain

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import ru.pauza.app.MainActivity
import ru.pauza.app.R

class BootWarmupService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val stopRunnable = Runnable {
        Log.i("PauseBootWarmup", "Warmup timeout; stopping")
        stopSelf()
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()

        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        Log.i(
            "PauseBootWarmup",
            "Warmup service created uptimeMs=${SystemClock.elapsedRealtime()}"
        )
        handler.postDelayed(stopRunnable, MAX_WARMUP_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val trigger = intent?.getStringExtra(EXTRA_TRIGGER)
        Log.i(
            "PauseBootWarmup",
            "Warmup start trigger=$trigger uptimeMs=${SystemClock.elapsedRealtime()}"
        )
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(stopRunnable)
        Log.i(
            "PauseBootWarmup",
            "Warmup service destroyed uptimeMs=${SystemClock.elapsedRealtime()}"
        )
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Восстановление Паузы",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Краткая подготовка защиты после перезагрузки"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_pauza)
            .setContentTitle("Пауза")
            .setContentText("Восстановление защиты после перезагрузки")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    companion object {
        const val EXTRA_TRIGGER = "trigger"

        private const val CHANNEL_ID = "pause_boot_warmup"
        private const val NOTIFICATION_ID = 7407
        private const val MAX_WARMUP_MS = 60_000L

        fun stop(context: Context) {
            context.stopService(Intent(context, BootWarmupService::class.java))
        }
    }
}
