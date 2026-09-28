package ru.pauza.app.domain

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.TextView
import ru.pauza.app.MainActivity
import ru.pauza.app.data.InstalledAppsRepository
import ru.pauza.app.data.PauseStore
import java.util.Locale
import kotlin.math.max

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
    private var bootRecoveryPending = false
    private var bootResumeAt = 0L
    private var transientSystemPackage: String? = null
    private var transientSystemUntil = 0L
    private var overlay: View? = null
    private var overlayTimer: TextView? = null

    private val watchdog = object : Runnable {
        override fun run() {
            try {
                enforceSafely()
            } finally {
                handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        val end = store.sessionEndEpochMs
        bootRecoveryPending =
            end > System.currentTimeMillis() &&
                SystemClock.elapsedRealtime() <= BOOT_RECOVERY_WINDOW_MS
        bootResumeAt = 0L

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
            bootRecoveryPending = false
            bootResumeAt = 0L
            hideOverlay()
            return
        }

        val screenInteractive = powerManager.isInteractive
        val deviceLocked = keyguardManager.isKeyguardLocked || keyguardManager.isDeviceLocked

        if (!screenInteractive || deviceLocked) {
            wasUnavailableForUnlock = true
            resumeProtectionAt = 0L
            if (bootRecoveryPending) {
                bootResumeAt = 0L
            }
            hideOverlay()
            return
        }

        if (bootRecoveryPending) {
            val nowElapsed = SystemClock.elapsedRealtime()

            if (bootResumeAt == 0L) {
                bootResumeAt = nowElapsed + BOOT_RESUME_GRACE_MS
                hideOverlay()
                return
            }

            if (nowElapsed < bootResumeAt) {
                hideOverlay()
                return
            }

            bootRecoveryPending = false
            bootResumeAt = 0L
            showOverlay(end)
            returnToPause()
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

        // Power/global-actions and voice-assistant surfaces are system windows,
        // even when their root belongs to another package (for example the Google
        // Assistant host). Treat the window type as authoritative and latch its
        // package briefly so our own accessibility overlay cannot steal focus and
        // start an overlay/MainActivity ping-pong loop.
        val nowElapsed = SystemClock.elapsedRealtime()
        val transientPackage = activeTransientSystemSurfacePackage(event)
        if (transientPackage != null) {
            transientSystemPackage = transientPackage
            transientSystemUntil = nowElapsed + TRANSIENT_SYSTEM_GRACE_MS
            hideOverlay()
            return
        }

        val eventPackage = event?.packageName?.toString()
        val isWindowTransition =
            event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED

        if (nowElapsed < transientSystemUntil) {
            val unknownTransientStillPresent =
                transientSystemPackage == UNKNOWN_SYSTEM_SURFACE &&
                    windows.any {
                        it.type == UNKNOWN_ACCESSIBILITY_WINDOW_TYPE
                    }

            val activeApplicationPackage = windows.asSequence()
                .filter {
                    it.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                        (it.isActive || it.isFocused)
                }
                .mapNotNull { it.root?.packageName?.toString() }
                .firstOrNull()

            val transitionedToApplication =
                isWindowTransition &&
                    !eventPackage.isNullOrBlank() &&
                    activeApplicationPackage == eventPackage &&
                    eventPackage != packageName &&
                    eventPackage != SYSTEM_UI_PACKAGE

            if (transitionedToApplication) {
                // A real application became active: drop the transient-system
                // latch immediately, so Home, Settings and other apps are still
                // evaluated and blocked without waiting for the grace period.
                transientSystemPackage = null
                transientSystemUntil = 0L
            } else {
                val sameTransientSurface =
                    eventPackage.isNullOrBlank() ||
                        eventPackage == transientSystemPackage ||
                        eventPackage == SYSTEM_UI_PACKAGE ||
                        unknownTransientStillPresent

                if (!isWindowTransition || sameTransientSurface) {
                    hideOverlay()
                    return
                }

                transientSystemPackage = null
                transientSystemUntil = 0L
            }
        } else {
            transientSystemPackage = null
            transientSystemUntil = 0L
        }

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

        val allowed = store.selectedPackages +
            appsRepository.alwaysAllowedPackages() +
            packageName

        if (foregroundPackage in allowed) {
            hideOverlay()
            return
        }

        showOverlay(end)
        returnToPause()
    }

    private fun activeTransientSystemSurfacePackage(
        event: AccessibilityEvent?,
    ): String? {
        val eventPackage = event?.packageName?.toString()
        if (
            eventPackage == SYSTEM_UI_PACKAGE &&
            (
                event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                    event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                )
        ) {
            return SYSTEM_UI_PACKAGE
        }

        val visibleSystemUi = windows.asSequence()
            .filter { it.isActive || it.isFocused }
            .mapNotNull { it.root?.packageName?.toString() }
            .firstOrNull { it == SYSTEM_UI_PACKAGE }

        if (visibleSystemUi != null) return SYSTEM_UI_PACKAGE

        // Android 15 exposes VoiceInteractionSession (WindowManager type 2031)
        // through AccessibilityWindowInfo as UNKNOWN (-1), taskId=-1. This
        // window becomes active/focused before the Assistant surface is drawn,
        // which is early enough to avoid creating our own blocking overlay.
        val unknownSystemSurface = windows.firstOrNull {
            it.type == UNKNOWN_ACCESSIBILITY_WINDOW_TYPE &&
                (
                    it.isActive ||
                        it.isFocused ||
                        (
                            isWindowTransitionEvent(event) &&
                                eventPackage != packageName
                            )
                    )
        }
        if (unknownSystemSurface != null) {
            return eventPackage
                ?.takeIf { it != packageName }
                ?: UNKNOWN_SYSTEM_SURFACE
        }

        // Some OEM transient surfaces are reported as TYPE_SYSTEM with a rooted
        // package. Do not require focus here because our own accessibility
        // overlay can briefly steal it.
        return windows.asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_SYSTEM }
            .mapNotNull { it.root?.packageName?.toString() }
            .firstOrNull { it != SYSTEM_UI_PACKAGE }
    }

    private fun isWindowTransitionEvent(event: AccessibilityEvent?): Boolean =
        event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED

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
        updateOverlayTimer(sessionEnd)
        if (overlay != null) return

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(247, 248, 244))
            isClickable = true
            isFocusable = true
            setOnClickListener { returnToPause() }
        }

        val timer = TextView(this).apply {
            setTextColor(Color.rgb(30, 36, 32))
            textSize = 52f
            gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.NORMAL
            )
        }
        overlayTimer = timer
        root.addView(
            timer,
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
            updateOverlayTimer(sessionEnd)
        }
    }

    private fun updateOverlayTimer(sessionEnd: Long) {
        overlayTimer?.text = formatRemaining(max(0L, sessionEnd - System.currentTimeMillis()))
    }

    private fun hideOverlay() {
        val current = overlay ?: return
        runCatching {
            getSystemService(WindowManager::class.java).removeView(current)
        }
        overlay = null
        overlayTimer = null
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        hideOverlay()
        super.onDestroy()
    }

    companion object {
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val RETURN_DEBOUNCE_MS = 180L
        private const val WATCHDOG_INTERVAL_MS = 200L
        private const val UNLOCK_GRACE_MS = 1_000L
        private const val BOOT_RESUME_GRACE_MS = 250L
        private const val BOOT_RECOVERY_WINDOW_MS = 180_000L
        private const val TRANSIENT_SYSTEM_GRACE_MS = 12_000L
        private const val UNKNOWN_ACCESSIBILITY_WINDOW_TYPE = -1
        private const val UNKNOWN_SYSTEM_SURFACE = "__pause_transient_system__"
    }
}

private fun formatRemaining(ms: Long): String {
    val total = ms / 1000
    val days = total / 86_400
    val hours = (total % 86_400) / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60

    return if (days > 0) {
        String.format(
            Locale.US,
            "%d дн %02d:%02d:%02d",
            days,
            hours,
            minutes,
            seconds
        )
    } else {
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }
}
