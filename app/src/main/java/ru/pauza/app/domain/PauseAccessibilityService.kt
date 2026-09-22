package ru.pauza.app.domain

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.view.accessibility.AccessibilityWindowInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import ru.pauza.app.MainActivity
import ru.pauza.app.data.InstalledAppsRepository
import ru.pauza.app.data.PauseStore
import java.util.Locale

class PauseAccessibilityService : AccessibilityService() {
    private val store by lazy { PauseStore(this) }
    private val appsRepository by lazy { InstalledAppsRepository(this) }
    private val handler = Handler(Looper.getMainLooper())
    private val keyguardManager by lazy { getSystemService(KeyguardManager::class.java) }
    private val powerManager by lazy { getSystemService(PowerManager::class.java) }
    private val launcherPackages by lazy {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        (packageManager.queryIntentActivities(home, PackageManager.MATCH_ALL)
            .mapNotNull { it.activityInfo?.packageName } +
            listOfNotNull(packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName)).toSet() - packageName
    }

    private var lastReturnAt = 0L
    private var shortVideoNavigating = false
    private var shortVideoBlockedPackage: String? = null
    private var shortVideoRetryAt = 0L
    private var shortVideoRetryAttempts = 0
    private var pendingShortVideoNotice = false
    private var shortVideoCooldownUntil = 0L
    private var shortVideoUsesDetectedPlayerEscape = false
    private var shortVideoExitCandidateAt = 0L
    private var lastShortContentScanAt = 0L
    private var lastShortNoticeShownAt = 0L
    private var wasUnavailableForUnlock = false
    private var resumeProtectionAt = 0L
    private var shortNoticeOverlay: View? = null
    private var shortNoticeDismissRunnable: Runnable? = null
    private var shortNoticeHideAt = 0L
    private var shortNoticePackage: String? = null
    private var shortNoticeArmed = true
    private var shortSafeSince = 0L

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
            // A transient window/startActivity failure must not stop the watchdog.
            Log.w("PauseProtection", "Window enforcement failed; will retry", error)
        }
    }

    private fun enforceCurrentWindow(event: AccessibilityEvent? = null) {
        expireShortNoticeIfNeeded()

        val end = store.sessionEndEpochMs
        if (end <= 0L || System.currentTimeMillis() >= end) {
            resetShortVideoNavigation()
            hideShortNotice()
            return
        }

        val screenInteractive = powerManager.isInteractive
        val deviceLocked = keyguardManager.isKeyguardLocked || keyguardManager.isDeviceLocked

        if (!screenInteractive || deviceLocked) {
            resetShortVideoNavigation()
            wasUnavailableForUnlock = true
            resumeProtectionAt = 0L
            hideShortNotice()
            return
        }

        if (wasUnavailableForUnlock) {
            wasUnavailableForUnlock = false
            resumeProtectionAt = SystemClock.elapsedRealtime() + UNLOCK_GRACE_MS
            hideShortNotice()
            return
        }

        if (SystemClock.elapsedRealtime() < resumeProtectionAt) {
            hideShortNotice()
            return
        }

        val foregroundPackage = resolveForegroundPackage(event) ?: return
        if (foregroundPackage in launcherPackages || isSystemEscapeEvent(event)) {
            resetShortVideoNavigation()
            hideShortNotice()
            // Back can resume RuTube or race Home. Bring Pause forward directly.
            returnToPause()
            return
        }
        if (shortNoticePackage != null && foregroundPackage != shortNoticePackage) {
            hideShortNotice()
            shortNoticeArmed = true
            shortSafeSince = 0L
        }
        if (shortVideoBlockedPackage != null && foregroundPackage != shortVideoBlockedPackage) {
            resetShortVideoNavigation()
            hideShortNotice()
        }
        val allowed = store.selectedPackages +
            appsRepository.alwaysAllowedPackages() +
            packageName

        if (foregroundPackage in allowed) {
            if (store.blockShortVideos && ShortVideoDetector.isSupportedPackage(foregroundPackage)) {
                val nowElapsed = SystemClock.elapsedRealtime()
                // Missing window data is unknown, never evidence of a successful exit.
                val appRoot = resolveApplicationRoot(foregroundPackage)
                if (appRoot == null) {
                    shortSafeSince = 0L
                    shortVideoExitCandidateAt = 0L
                    return
                }

                val earlyEntryAction = ShortVideoDetector.isShortEntryAction(
                    packageName = foregroundPackage,
                    event = event,
                )

                if (earlyEntryAction) {
                    shortNoticeArmed = true
                    shortSafeSince = 0L
                    if (!shortVideoNavigating && nowElapsed >= shortVideoCooldownUntil) {
                        beginShortVideoRedirect(
                            foregroundPackage = foregroundPackage,
                            appRoot = appRoot,
                            detectedPlayer = false,
                        )
                    }
                    return
                }

                val shouldScanShortVideo =
                    ShortVideoDetector.usesBackgroundScreenDetection(foregroundPackage) &&
                        (
                            shortVideoNavigating ||
                                event?.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                                nowElapsed - lastShortContentScanAt >= SHORT_CONTENT_SCAN_THROTTLE_MS
                        )

                // A throttled scan must not advance the exit-confirmation timer.
                if (!shouldScanShortVideo) return

                if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                    lastShortContentScanAt = nowElapsed
                }
                val shortVideoDetected = ShortVideoDetector.isShortVideoScreen(
                    packageName = foregroundPackage,
                    root = appRoot,
                    event = event,
                )

                if (shortVideoDetected) {
                    shortVideoExitCandidateAt = 0L
                    shortSafeSince = 0L
                    // Keep an existing notice until its original deadline.
                    // Entry interception may have run before the player opened.
                    shortVideoUsesDetectedPlayerEscape = true

                    if (!shortVideoNavigating && nowElapsed >= shortVideoCooldownUntil) {
                        beginShortVideoRedirect(
                            foregroundPackage = foregroundPackage,
                            appRoot = appRoot,
                            detectedPlayer = true,
                        )
                    } else if (
                        shortVideoNavigating &&
                        nowElapsed >= shortVideoRetryAt &&
                        foregroundPackage == shortVideoBlockedPackage &&
                        shortVideoRetryAttempts < SHORT_VIDEO_MAX_RETRIES
                    ) {
                        shortVideoRetryAttempts += 1
                        shortVideoRetryAt = nowElapsed + SHORT_VIDEO_RETRY_DELAY_MS
                        navigateShortVideoEscape(
                            foregroundPackage = foregroundPackage,
                            appRoot = appRoot,
                        )
                    }
                    return
                }

                if (!shortVideoNavigating) {
                    if (ShortVideoDetector.isConfirmedSafeSurface(foregroundPackage, appRoot)) {
                        if (shortSafeSince == 0L) shortSafeSince = nowElapsed
                        if (nowElapsed - shortSafeSince >= SHORT_VIDEO_NOTICE_REARM_MS) {
                            shortNoticeArmed = true
                        }
                    } else {
                        shortSafeSince = 0L
                    }
                }

                if (
                    shortVideoNavigating &&
                    foregroundPackage == shortVideoBlockedPackage
                ) {
                    val safeSurfaceConfirmed =
                        ShortVideoDetector.isConfirmedSafeSurface(
                            packageName = foregroundPackage,
                            root = appRoot,
                        )

                    if (!safeSurfaceConfirmed) {
                        shortVideoExitCandidateAt = 0L

                        if (
                            nowElapsed >= shortVideoRetryAt &&
                            shortVideoRetryAttempts < SHORT_VIDEO_MAX_RETRIES
                        ) {
                            shortVideoRetryAttempts += 1
                            shortVideoRetryAt = nowElapsed + SHORT_VIDEO_RETRY_DELAY_MS
                            navigateShortVideoEscape(
                                foregroundPackage = foregroundPackage,
                                appRoot = appRoot,
                            )
                        }
                        return
                    }

                    if (shortVideoExitCandidateAt == 0L) {
                        shortVideoExitCandidateAt = nowElapsed
                        return
                    }

                    if (nowElapsed - shortVideoExitCandidateAt < SHORT_VIDEO_EXIT_CONFIRM_MS) {
                        return
                    }

                    shortVideoNavigating = false
                    shortVideoRetryAttempts = 0
                    shortVideoBlockedPackage = null
                    shortVideoUsesDetectedPlayerEscape = false
                    shortVideoExitCandidateAt = 0L
                    // Rate-limit notices, not protection against a new player entry.
                    shortVideoCooldownUntil = 0L

                    if (pendingShortVideoNotice) {
                        pendingShortVideoNotice = false
                        if (shortNoticeArmed && nowElapsed - lastShortNoticeShownAt >= SHORT_VIDEO_NOTICE_MIN_GAP_MS) {
                            showShortVideoOverlay(foregroundPackage)
                        }
                    }
                }
            } else if (shortVideoNavigating) {
                resetShortVideoNavigation()
            }

            return
        }

        returnToPause()
    }


    private fun beginShortVideoRedirect(
        foregroundPackage: String,
        appRoot: android.view.accessibility.AccessibilityNodeInfo?,
        detectedPlayer: Boolean,
    ) {
        val now = SystemClock.elapsedRealtime()

        shortVideoUsesDetectedPlayerEscape = detectedPlayer
        // Keep the episode even when a click cannot be dispatched. Retry from fresh
        // window data instead of restarting at attempt zero on every event.
        shortVideoNavigating = true
        shortVideoBlockedPackage = foregroundPackage
        shortVideoRetryAt = now + SHORT_VIDEO_RETRY_DELAY_MS
        shortVideoRetryAttempts = 0
        shortVideoExitCandidateAt = 0L
        shortVideoCooldownUntil = now + SHORT_VIDEO_NAVIGATION_COOLDOWN_MS
        pendingShortVideoNotice = true
        navigateShortVideoEscape(foregroundPackage, appRoot)
    }

    private fun navigateShortVideoEscape(
        foregroundPackage: String,
        appRoot: android.view.accessibility.AccessibilityNodeInfo?,
    ): Boolean =
        if (shortVideoUsesDetectedPlayerEscape) {
            ShortVideoSafeNavigator.escapeDetectedPlayer(
                service = this,
                packageName = foregroundPackage,
                root = appRoot,
                attempt = shortVideoRetryAttempts,
            )
        } else {
            ShortVideoSafeNavigator.navigateToSafeSurface(
                service = this,
                packageName = foregroundPackage,
                root = appRoot,
            )
        }

    private fun resetShortVideoNavigation() {
        shortVideoNavigating = false
        shortVideoBlockedPackage = null
        shortVideoRetryAt = 0L
        shortVideoRetryAttempts = 0
        pendingShortVideoNotice = false
        shortVideoCooldownUntil = 0L
        shortVideoUsesDetectedPlayerEscape = false
        shortVideoExitCandidateAt = 0L
        lastShortContentScanAt = 0L
        shortSafeSince = 0L
        shortNoticeArmed = true
    }

    private fun showShortVideoOverlay(ownerPackage: String) {
        if (shortNoticeOverlay != null) return

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(12), dp(12))
            background = roundedBackground(
                color = Color.WHITE,
                radiusDp = 22f
            )
            elevation = dp(8).toFloat()
        }

        val icon = TextView(this).apply {
            text = "Ⅱ"
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(Color.rgb(57, 103, 70))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = roundedBackground(
                color = Color.rgb(232, 241, 226),
                radiusDp = 999f
            )
        }
        card.addView(
            icon,
            LinearLayout.LayoutParams(dp(46), dp(46)).apply {
                marginEnd = dp(12)
            }
        )

        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(this).apply {
            text = "Короткие видео заблокированы"
            textSize = 14f
            setTextColor(Color.rgb(25, 27, 26))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 1
        }
        val body = TextView(this).apply {
            text = "Во время Паузы Shorts и Reels недоступны."
            textSize = 11.5f
            setTextColor(Color.rgb(118, 121, 119))
            maxLines = 2
        }
        copy.addView(title)
        copy.addView(
            body,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(3)
            }
        )

        card.addView(
            copy,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        val container = FrameLayout(this).apply {
            setPadding(dp(18), 0, dp(18), 0)
            addView(
                card,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            x = 0
            y = dp(24)
        }

        runCatching {
            getSystemService(WindowManager::class.java).addView(container, params)
            shortNoticeOverlay = container
            shortNoticePackage = ownerPackage
            shortNoticeArmed = false
            lastShortNoticeShownAt = SystemClock.elapsedRealtime()
            shortNoticeHideAt = lastShortNoticeShownAt + SHORT_VIDEO_NOTICE_DURATION_MS

            shortNoticeDismissRunnable?.let(handler::removeCallbacks)
            shortNoticeDismissRunnable = Runnable {
                expireShortNoticeIfNeeded()
            }.also { runnable ->
                handler.postDelayed(runnable, SHORT_VIDEO_NOTICE_DURATION_MS)
            }
        }
    }

    private fun resolveApplicationRoot(targetPackage: String) =
        windows
            .asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { it.isFocused }
            .mapNotNull { it.root }
            .firstOrNull { it.packageName?.toString() == targetPackage }
            ?: rootInActiveWindow?.takeIf { it.packageName?.toString() == targetPackage }

    private fun roundedBackground(color: Int, radiusDp: Float) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun isSystemEscapeEvent(event: AccessibilityEvent?): Boolean {
        event ?: return false

        val eventPackage = event.packageName?.toString().orEmpty()
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
        // A video app can keep sending events while Home is already visible,
        // especially in PiP. Current full-size windows take precedence.
        val currentApplication = windows.asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && !it.isInPictureInPictureMode }
            .filter { it.isActive || it.isFocused }
            .sortedByDescending { it.isActive }
            .mapNotNull { it.root?.packageName?.toString() }
            .firstOrNull()
        if (!currentApplication.isNullOrBlank()) return currentApplication

        val rootPackage = rootInActiveWindow?.packageName?.toString()
        val pipPackages = windows.filter { it.isInPictureInPictureMode }
            .mapNotNull { it.root?.packageName?.toString() }.toSet()
        if (!rootPackage.isNullOrBlank() && rootPackage !in pipPackages) return rootPackage

        // A launcher event still matters when Android temporarily provides no roots.
        val eventPackage = event?.packageName?.toString()
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
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
            }
        )
    }

    private fun expireShortNoticeIfNeeded() {
        if (
            shortNoticeOverlay != null &&
            shortNoticeHideAt > 0L &&
            SystemClock.elapsedRealtime() >= shortNoticeHideAt
        ) {
            hideShortNotice()
        }
    }

    private fun hideShortNotice() {
        shortNoticeDismissRunnable?.let(handler::removeCallbacks)
        shortNoticeDismissRunnable = null
        shortNoticeHideAt = 0L
        shortNoticePackage = null

        val current = shortNoticeOverlay ?: return
        runCatching {
            getSystemService(WindowManager::class.java).removeView(current)
        }
        shortNoticeOverlay = null
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        hideShortNotice()
        super.onDestroy()
    }

    companion object {
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val RETURN_DEBOUNCE_MS = 250L
        private const val WATCHDOG_INTERVAL_MS = 300L
        private const val UNLOCK_GRACE_MS = 1_000L
        private const val SHORT_VIDEO_RETRY_DELAY_MS = 420L
        private const val SHORT_VIDEO_MAX_RETRIES = 4
        private const val SHORT_VIDEO_NAVIGATION_COOLDOWN_MS = 2_800L
        private const val SHORT_VIDEO_EXIT_CONFIRM_MS = 450L
        private const val SHORT_VIDEO_NOTICE_REARM_MS = 2_000L
        private const val SHORT_VIDEO_NOTICE_DURATION_MS = 1_600L
        private const val SHORT_VIDEO_NOTICE_MIN_GAP_MS = 3_000L
        private const val SHORT_CONTENT_SCAN_THROTTLE_MS = 300L
    }
}

