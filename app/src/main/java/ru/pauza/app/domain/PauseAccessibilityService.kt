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
