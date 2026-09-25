package ru.pauza.app.domain

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import ru.pauza.app.MainActivity
import ru.pauza.app.data.InstalledAppsRepository
import ru.pauza.app.data.PauseStore

class PauseAccessibilityService : AccessibilityService() {
    private val store by lazy { PauseStore(this) }
    private val appsRepository by lazy { InstalledAppsRepository(this) }
    private val handler = Handler(Looper.getMainLooper())
    private val keyguardManager by lazy { getSystemService(KeyguardManager::class.java) }
    private val powerManager by lazy { getSystemService(PowerManager::class.java) }

    private var lastReturnAt = 0L
    private var wasUnavailableForUnlock = false
    private var resumeProtectionAt = 0L
    private var shuttingDown = false
    private var shutdownReceiverRegistered = false

    private val shutdownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SHUTDOWN) {
                shuttingDown = true
            }
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            try {
                enforceSafely()
            } finally {
                handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(Intent.ACTION_SHUTDOWN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(shutdownReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(shutdownReceiver, filter)
        }
        shutdownReceiverRegistered = true
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        shuttingDown = false
        handler.removeCallbacks(watchdog)
        handler.post(watchdog)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        enforceSafely(event)
    }

    private fun enforceSafely(event: AccessibilityEvent? = null) {
        try {
            enforceCurrentWindow(event)
        } catch (error: RuntimeException) {
            Log.w("PauseProtection", "Foreground enforcement failed; will retry", error)
        }
    }

    private fun enforceCurrentWindow(event: AccessibilityEvent? = null) {
        val end = store.sessionEndEpochMs
        if (end <= 0L || System.currentTimeMillis() >= end) return
        if (shuttingDown) return

        if (!powerManager.isInteractive ||
            keyguardManager.isKeyguardLocked ||
            keyguardManager.isDeviceLocked
        ) {
            wasUnavailableForUnlock = true
            resumeProtectionAt = 0L
            return
        }

        if (wasUnavailableForUnlock) {
            wasUnavailableForUnlock = false
            resumeProtectionAt = SystemClock.elapsedRealtime() + UNLOCK_GRACE_MS
            return
        }

        if (SystemClock.elapsedRealtime() < resumeProtectionAt) return

        // Restore the 0.7.4 rule that worked on the real device:
        // Usage Access is the primary source of the foreground app.
        // Accessibility is only a fallback when Usage Access has no result.
        val foregroundPackage =
            UsageAccessMonitor.foregroundPackage(this)
                ?: resolveAccessibilityForeground(event)
                ?: return

        if (
            foregroundPackage == packageName ||
            foregroundPackage == SYSTEM_UI_PACKAGE ||
            foregroundPackage == ANDROID_FRAMEWORK_PACKAGE
        ) {
            return
        }

        val allowed =
            store.selectedPackages +
                appsRepository.alwaysAllowedPackages() +
                packageName

        if (foregroundPackage in allowed) {
            if (foregroundPackage == store.pendingAllowedLaunchPackage) {
                store.clearPendingAllowedLaunch()
            }
            return
        }

        // No accessibility overlay at all. A blocked app simply causes the
        // existing Pause activity to be brought to the front.
        returnToPause()
    }

    private fun resolveAccessibilityForeground(event: AccessibilityEvent?): String? {
        val currentApplication = windows.asSequence()
            .filter {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    !it.isInPictureInPictureMode
            }
            .filter { it.isActive || it.isFocused }
            .sortedByDescending { it.isActive }
            .mapNotNull { it.root?.packageName?.toString() }
            .firstOrNull()

        if (!currentApplication.isNullOrBlank()) return currentApplication

        val rootPackage = rootInActiveWindow?.packageName?.toString()
        if (!rootPackage.isNullOrBlank()) return rootPackage

        return event?.packageName?.toString()
    }

    private fun returnToPause() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastReturnAt < RETURN_DEBOUNCE_MS) return
        lastReturnAt = now

        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
        )
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        if (shutdownReceiverRegistered) {
            runCatching { unregisterReceiver(shutdownReceiver) }
            shutdownReceiverRegistered = false
        }
        super.onDestroy()
    }

    companion object {
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val ANDROID_FRAMEWORK_PACKAGE = "android"
        private const val RETURN_DEBOUNCE_MS = 220L
        private const val WATCHDOG_INTERVAL_MS = 200L
        private const val UNLOCK_GRACE_MS = 1_000L
    }
}
