package ru.pauza.app.domain

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.view.accessibility.AccessibilityWindowInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
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
    private val launcherPackageName by lazy {
        packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName
    }

    private var lastReturnAt = 0L
    private var wasUnavailableForUnlock = false
    private var resumeProtectionAt = 0L
    private var overlay: View? = null
    private var overlayTimer: TextView? = null

    private val watchdog = object : Runnable {
        override fun run() {
            enforceCurrentWindow()
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        handler.removeCallbacks(watchdog)
        handler.post(watchdog)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        enforceCurrentWindow(event)
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

        if (isSystemEscapeEvent(event)) {
            hideOverlay()
            performGlobalAction(GLOBAL_ACTION_BACK)
            returnToPause(force = true)
            return
        }

        val foregroundPackage = resolveForegroundPackage(event) ?: return
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

    private fun isSystemEscapeEvent(event: AccessibilityEvent?): Boolean {
        event ?: return false

        val eventPackage = event.packageName?.toString().orEmpty()
        if (
            launcherPackageName != null &&
            eventPackage == launcherPackageName
        ) {
            return true
        }

        val className = event.className?.toString()?.lowercase(Locale.ROOT).orEmpty()
        val sourceId =
            event.source?.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
        val eventText =
            event.text.joinToString(" ")
                .lowercase(Locale.ROOT)

        val recentsSignature =
            listOf("recent", "recents", "overview", "quickstep", "taskview", "task_view")
                .any { hint ->
                    className.contains(hint) ||
                        sourceId.contains(hint) ||
                        eventText.contains(hint)
                }

        return eventPackage == SYSTEM_UI_PACKAGE && recentsSignature
    }

    private fun resolveForegroundPackage(event: AccessibilityEvent?): String? {
        val focusedApplication = windows
            .asSequence()
            .filter {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    it.isFocused
            }
            .mapNotNull { it.root?.packageName?.toString() }
            .firstOrNull { it != SYSTEM_UI_PACKAGE }

        if (focusedApplication != null) return focusedApplication

        val rootPackage = rootInActiveWindow?.packageName?.toString()
        if (!rootPackage.isNullOrBlank() && rootPackage != SYSTEM_UI_PACKAGE) {
            return rootPackage
        }

        return event?.packageName?.toString()
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
        private const val RETURN_DEBOUNCE_MS = 250L
        private const val WATCHDOG_INTERVAL_MS = 400L
        private const val UNLOCK_GRACE_MS = 1_000L
    }
}

private fun formatRemaining(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
}
