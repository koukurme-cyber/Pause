package ru.pauza.app.domain

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.TextView
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
    private var overlay: View? = null
    private var shuttingDown = false
    private var shutdownReceiverRegistered = false
    private var systemUiGraceUntil = 0L
    private var systemUiBackgroundRequested = false

    private val shutdownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SHUTDOWN) {
                shuttingDown = true
                hideOverlay()
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
            hideOverlay()
            return
        }

        if (shuttingDown) {
            hideOverlay()
            return
        }

        val screenInteractive = powerManager.isInteractive
        val deviceLocked = keyguardManager.isKeyguardLocked || keyguardManager.isDeviceLocked

        if (!screenInteractive || deviceLocked) {
            wasUnavailableForUnlock = true
            resumeProtectionAt = 0L
            hideOverlay()
            return
        }

        if (wasUnavailableForUnlock) {
            wasUnavailableForUnlock = false
            resumeProtectionAt = SystemClock.elapsedRealtime() + UNLOCK_GRACE_MS
            hideOverlay()
            return
        }

        if (SystemClock.elapsedRealtime() < resumeProtectionAt) {
            hideOverlay()
            return
        }

        val elapsedNow = SystemClock.elapsedRealtime()
        if (isSystemTransitionSurfaceVisible(event)) {
            systemUiGraceUntil = elapsedNow + SYSTEM_UI_GRACE_MS
            if (!systemUiBackgroundRequested) {
                systemUiBackgroundRequested = true
                requestActivityBackground()
            }
            hideOverlay()
            return
        }

        if (elapsedNow < systemUiGraceUntil) {
            hideOverlay()
            return
        }

        systemUiBackgroundRequested = false

        val foregroundPackage = resolveForegroundPackage(event) ?: return

        // System UI itself is allowed, but a launcher is not: pressing Home may
        // briefly show the normal desktop, then Usage Access detects that launcher
        // as foreground and returns the user to the active Pause.
        if (
            foregroundPackage == packageName ||
            foregroundPackage == SYSTEM_UI_PACKAGE
        ) {
            hideOverlay()
            return
        }

        // Home is intentionally not whitelisted. Return to Pause directly
        // without flashing the blocking-overlay timer over the launcher.
        if (foregroundPackage in launcherPackages) {
            hideOverlay()
            returnToPause()
            return
        }

        // Immediately after a device reboot Android may briefly report the
        // framework package while System UI and the launcher settle. Treat only
        // that transient framework surface as a system transition; Settings and
        // every real application keep their normal whitelist enforcement.
        if (
            foregroundPackage == ANDROID_FRAMEWORK_PACKAGE &&
            SystemClock.elapsedRealtime() < POST_BOOT_FRAMEWORK_GRACE_MS
        ) {
            hideOverlay()
            returnToPause()
            return
        }

        val allowed = store.selectedPackages +
            appsRepository.alwaysAllowedPackages() +
            packageName

        if (foregroundPackage in allowed) {
            if (foregroundPackage == store.pendingAllowedLaunchPackage) {
                store.clearPendingAllowedLaunch()
            }
            hideOverlay()
            return
        }

        val pendingLaunchPackage = store.pendingAllowedLaunchPackage
        val pendingLaunchUntil = store.pendingAllowedLaunchUntilEpochMs
        val pendingLaunchActive =
            !pendingLaunchPackage.isNullOrBlank() &&
                System.currentTimeMillis() <= pendingLaunchUntil

        if (
            pendingLaunchActive &&
            foregroundPackage == ANDROID_FRAMEWORK_PACKAGE
        ) {
            hideOverlay()
            return
        }

        if (!pendingLaunchActive && pendingLaunchPackage != null) {
            store.clearPendingAllowedLaunch()
        }

        showOverlay(end)
        returnToPause()
    }

    private fun requestActivityBackground() {
        sendBroadcast(
            Intent(ACTION_BACKGROUND_FOR_SYSTEM_UI).setPackage(packageName)
        )
    }

    private fun isSystemTransitionSurfaceVisible(event: AccessibilityEvent?): Boolean {
        val eventPackage = event?.packageName?.toString()
        if (
            eventPackage == SYSTEM_UI_PACKAGE ||
            eventPackage == ANDROID_FRAMEWORK_PACKAGE
        ) {
            return true
        }

        val activeSystemWindow = windows.asSequence()
            .filter { it.isActive || it.isFocused }
            .any { window ->
                val pkg = window.root?.packageName?.toString()
                window.type == AccessibilityWindowInfo.TYPE_SYSTEM ||
                    pkg == SYSTEM_UI_PACKAGE ||
                    pkg == ANDROID_FRAMEWORK_PACKAGE
            }

        if (activeSystemWindow) return true

        val rootPackage = rootInActiveWindow?.packageName?.toString()
        return rootPackage == SYSTEM_UI_PACKAGE ||
            rootPackage == ANDROID_FRAMEWORK_PACKAGE
    }

    private fun resolveForegroundPackage(event: AccessibilityEvent?): String? {
        // Primary detector: Android Usage Access. Unlike Accessibility windows,
        // this records the application that actually entered the resumed state.
        UsageAccessMonitor.foregroundPackage(this)?.let { return it }

        // Fallback: accessibility windows/events for OEMs that delay usage events.
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

    private fun showOverlay(sessionEnd: Long) {
        if (overlay != null) return

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(247, 248, 244))
            isClickable = true
            isFocusable = true
            setOnClickListener { returnToPause() }
        }

        // Deliberately static. The real countdown lives only on ActiveScreen.
        // A second independently-updated timer in an accessibility overlay can
        // flash during OEM power/global-actions transitions.
        val label = TextView(this).apply {
            text = "Пауза"
            setTextColor(Color.rgb(30, 36, 32))
            textSize = 28f
            gravity = android.view.Gravity.CENTER
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD
            )
        }
        root.addView(
            label,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        )

        runCatching {
            getSystemService(WindowManager::class.java).addView(root, params)
            overlay = root
        }
    }

    private fun hideOverlay() {
        val current = overlay ?: return
        runCatching {
            getSystemService(WindowManager::class.java).removeView(current)
        }
        overlay = null
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        hideOverlay()
        if (shutdownReceiverRegistered) {
            runCatching { unregisterReceiver(shutdownReceiver) }
            shutdownReceiverRegistered = false
        }
        super.onDestroy()
    }

    companion object {
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val ANDROID_FRAMEWORK_PACKAGE = "android"
        private const val RETURN_DEBOUNCE_MS = 180L
        private const val WATCHDOG_INTERVAL_MS = 200L
        private const val UNLOCK_GRACE_MS = 1_000L
        private const val POST_BOOT_FRAMEWORK_GRACE_MS = 60_000L
        private const val SYSTEM_UI_GRACE_MS = 1_500L
        const val ACTION_BACKGROUND_FOR_SYSTEM_UI =
            "ru.pauza.app.action.BACKGROUND_FOR_SYSTEM_UI"
    }
}

