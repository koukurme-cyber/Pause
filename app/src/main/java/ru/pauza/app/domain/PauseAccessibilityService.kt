package ru.pauza.app.domain

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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

    private val launcherPackages by lazy {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        (
            packageManager.queryIntentActivities(homeIntent, PackageManager.MATCH_ALL)
                .mapNotNull { it.activityInfo?.packageName } +
                listOfNotNull(
                    packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
                        ?.activityInfo?.packageName
                )
            ).toSet() - packageName
    }

    private var lastReturnAt = 0L
    private var wasUnavailableForUnlock = false
    private var resumeProtectionAt = 0L
    private var shuttingDown = false
    private var shutdownReceiverRegistered = false
    private var fallbackCandidatePackage: String? = null
    private var fallbackCandidateSince = 0L

    private val shutdownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SHUTDOWN) {
                shuttingDown = true
                clearFallbackCandidate()
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
        if (end <= 0L || System.currentTimeMillis() >= end) {
            clearFallbackCandidate()
            return
        }

        if (shuttingDown) {
            clearFallbackCandidate()
            return
        }

        if (!powerManager.isInteractive ||
            keyguardManager.isKeyguardLocked ||
            keyguardManager.isDeviceLocked
        ) {
            wasUnavailableForUnlock = true
            resumeProtectionAt = 0L
            clearFallbackCandidate()
            return
        }

        if (wasUnavailableForUnlock) {
            wasUnavailableForUnlock = false
            resumeProtectionAt = SystemClock.elapsedRealtime() + UNLOCK_GRACE_MS
            clearFallbackCandidate()
            return
        }

        if (SystemClock.elapsedRealtime() < resumeProtectionAt) {
            clearFallbackCandidate()
            return
        }

        // Power menu, notification shade and other genuine Android system surfaces
        // are left completely alone. We do not draw an accessibility overlay at all.
        if (isSystemSurfaceVisible(event)) {
            clearFallbackCandidate()
            return
        }

        val accessibilityForeground = resolveAccessibilityForeground(event)
        if (!accessibilityForeground.isNullOrBlank()) {
            clearFallbackCandidate()
            enforcePackage(accessibilityForeground)
            return
        }

        // Usage Access is only a fallback. OEM usage events can lag behind the
        // visible app, so require the same blocked package to persist briefly
        // before acting. This avoids false flashes over allowed apps.
        val usageForeground = UsageAccessMonitor.foregroundPackage(this) ?: run {
            clearFallbackCandidate()
            return
        }

        if (isAllowedOrSystem(usageForeground)) {
            clearFallbackCandidate()
            return
        }

        if (usageForeground == ANDROID_FRAMEWORK_PACKAGE &&
            SystemClock.elapsedRealtime() < POST_BOOT_FRAMEWORK_GRACE_MS
        ) {
            clearFallbackCandidate()
            return
        }

        if (!fallbackBlockedPackagePersisted(usageForeground)) return
        enforcePackage(usageForeground)
    }

    private fun enforcePackage(foregroundPackage: String) {
        if (foregroundPackage == ANDROID_FRAMEWORK_PACKAGE) return
        if (isAllowedOrSystem(foregroundPackage)) return

        // Launcher is deliberately not whitelisted. Home may appear for a moment,
        // then Pause is brought back. Allowed apps are never covered by an overlay.
        if (foregroundPackage in launcherPackages) {
            returnToPause()
            return
        }

        val pendingLaunchPackage = store.pendingAllowedLaunchPackage
        val pendingLaunchUntil = store.pendingAllowedLaunchUntilEpochMs
        val pendingLaunchActive =
            !pendingLaunchPackage.isNullOrBlank() &&
                System.currentTimeMillis() <= pendingLaunchUntil

        if (pendingLaunchActive && foregroundPackage == pendingLaunchPackage) {
            store.clearPendingAllowedLaunch()
            return
        }

        if (!pendingLaunchActive && pendingLaunchPackage != null) {
            store.clearPendingAllowedLaunch()
        }

        returnToPause()
    }

    private fun isAllowedOrSystem(packageName: String): Boolean {
        if (packageName == this.packageName || packageName == SYSTEM_UI_PACKAGE) {
            return true
        }

        val allowed = store.selectedPackages +
            appsRepository.alwaysAllowedPackages() +
            this.packageName

        return packageName in allowed
    }

    private fun isSystemSurfaceVisible(event: AccessibilityEvent?): Boolean {
        val eventPackage = event?.packageName?.toString()
        if (eventPackage == SYSTEM_UI_PACKAGE) return true

        val rootPackage = rootInActiveWindow?.packageName?.toString()
        if (rootPackage == SYSTEM_UI_PACKAGE) return true

        // Some OEM global-actions / power menus are reported as the framework
        // package rather than com.android.systemui. Only treat it as a system
        // surface when it is actually the focused/root window.
        if (rootPackage == ANDROID_FRAMEWORK_PACKAGE) return true

        return windows.asSequence()
            .filter { it.isActive || it.isFocused }
            .mapNotNull { it.root?.packageName?.toString() }
            .any { it == SYSTEM_UI_PACKAGE || it == ANDROID_FRAMEWORK_PACKAGE }
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
            .firstOrNull {
                it != SYSTEM_UI_PACKAGE &&
                    it != ANDROID_FRAMEWORK_PACKAGE
            }

        if (!currentApplication.isNullOrBlank()) return currentApplication

        val rootPackage = rootInActiveWindow?.packageName?.toString()
        if (
            !rootPackage.isNullOrBlank() &&
            rootPackage != SYSTEM_UI_PACKAGE &&
            rootPackage != ANDROID_FRAMEWORK_PACKAGE
        ) {
            return rootPackage
        }

        val eventPackage = event?.packageName?.toString()
        if (
            !eventPackage.isNullOrBlank() &&
            eventPackage != SYSTEM_UI_PACKAGE &&
            eventPackage != ANDROID_FRAMEWORK_PACKAGE
        ) {
            return eventPackage
        }

        return null
    }

    private fun clearFallbackCandidate() {
        fallbackCandidatePackage = null
        fallbackCandidateSince = 0L
    }

    private fun fallbackBlockedPackagePersisted(packageName: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (fallbackCandidatePackage != packageName) {
            fallbackCandidatePackage = packageName
            fallbackCandidateSince = now
            return false
        }
        return now - fallbackCandidateSince >= USAGE_FALLBACK_CONFIRM_MS
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
        private const val WATCHDOG_INTERVAL_MS = 350L
        private const val UNLOCK_GRACE_MS = 1_000L
        private const val POST_BOOT_FRAMEWORK_GRACE_MS = 60_000L
        private const val USAGE_FALLBACK_CONFIRM_MS = 700L
    }
}
