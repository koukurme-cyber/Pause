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
    private var navGuardOverlay: View? = null

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
            Log.w("PauseProtection", "Window enforcement failed; will retry", error)
        }
    }

    private fun enforceCurrentWindow(event: AccessibilityEvent? = null) {
        val end = store.sessionEndEpochMs
        if (end <= 0L || System.currentTimeMillis() >= end) {
            hideOverlay()
            hideNavGuard()
            return
        }

        val screenInteractive = powerManager.isInteractive
        val deviceLocked = keyguardManager.isKeyguardLocked || keyguardManager.isDeviceLocked

        if (!screenInteractive || deviceLocked) {
            wasUnavailableForUnlock = true
            resumeProtectionAt = 0L
            hideOverlay()
            hideNavGuard()
            return
        }

        if (wasUnavailableForUnlock) {
            wasUnavailableForUnlock = false
            resumeProtectionAt = SystemClock.elapsedRealtime() + UNLOCK_GRACE_MS
            hideOverlay()
            hideNavGuard()
            return
        }

        if (SystemClock.elapsedRealtime() < resumeProtectionAt) {
            hideOverlay()
            hideNavGuard()
            return
        }

        if (isSystemEscapeEvent(event)) {
            hideOverlay()
            hideNavGuard()
            performGlobalAction(GLOBAL_ACTION_BACK)
            returnToPause(force = true)
            return
        }

        val foregroundPackage = resolveForegroundPackage(event) ?: return

        if (foregroundPackage in launcherPackages) {
            hideOverlay()
            hideNavGuard()
            returnToPause(force = true)
            return
        }

        val allowed = store.selectedPackages +
            appsRepository.alwaysAllowedPackages() +
            packageName

        if (foregroundPackage in allowed) {
            hideOverlay()
            if (foregroundPackage == packageName) {
                hideNavGuard()
            } else {
                showNavGuard()
            }
            return
        }

        hideNavGuard()
        showOverlay(end)
        returnToPause()
    }

    private fun isSystemEscapeEvent(event: AccessibilityEvent?): Boolean {
        event ?: return false

        val eventPackage = event.packageName?.toString().orEmpty()
        if (eventPackage in launcherPackages) return true

        if (eventPackage != SYSTEM_UI_PACKAGE) return false

        val className = event.className?.toString()?.lowercase(Locale.ROOT).orEmpty()
        val sourceId = event.source?.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
        val eventText = event.text.joinToString(" ").lowercase(Locale.ROOT)

        return listOf(
            "recent",
            "recents",
            "overview",
            "quickstep",
            "taskview",
            "task_view",
            "recent_apps",
            "recentapps"
        ).any { hint ->
            className.contains(hint) ||
                sourceId.contains(hint) ||
                eventText.contains(hint)
        }
    }

    private fun resolveForegroundPackage(event: AccessibilityEvent?): String? {
        val eventPackage = event?.packageName?.toString()

        if (
            !eventPackage.isNullOrBlank() &&
            eventPackage != SYSTEM_UI_PACKAGE &&
            eventPackage in launcherPackages
        ) {
            return eventPackage
        }

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
        val pipPackages = windows
            .filter { it.isInPictureInPictureMode }
            .mapNotNull { it.root?.packageName?.toString() }
            .toSet()

        if (!rootPackage.isNullOrBlank() && rootPackage !in pipPackages) {
            return rootPackage
        }

        return eventPackage?.takeUnless { it in pipPackages }
    }

    private fun returnToPause(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastReturnAt < RETURN_DEBOUNCE_MS) return
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

    private fun showNavGuard() {
        if (navGuardOverlay != null) return

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            isFocusable = false
            setOnClickListener { returnToPause(force = true) }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            navigationBarHeight(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.BOTTOM
        }

        runCatching {
            getSystemService(WindowManager::class.java).addView(root, params)
            navGuardOverlay = root
        }.onFailure {
            Log.w("PauseProtection", "Navigation guard overlay failed", it)
        }
    }

    private fun hideNavGuard() {
        val current = navGuardOverlay ?: return
        runCatching {
            getSystemService(WindowManager::class.java).removeView(current)
        }
        navGuardOverlay = null
    }

    private fun navigationBarHeight(): Int {
        val resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        val systemHeight =
            if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
        return max(systemHeight, dp(NAV_GUARD_FALLBACK_DP))
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

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
        hideNavGuard()
        super.onDestroy()
    }

    companion object {
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val RETURN_DEBOUNCE_MS = 250L
        private const val WATCHDOG_INTERVAL_MS = 300L
        private const val UNLOCK_GRACE_MS = 1_000L
        private const val NAV_GUARD_FALLBACK_DP = 48
    }
}

private fun formatRemaining(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
}
