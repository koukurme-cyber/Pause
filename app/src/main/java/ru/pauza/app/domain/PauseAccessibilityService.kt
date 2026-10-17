package ru.pauza.app.domain

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.app.AlertDialog
import android.app.PendingIntent
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ru.pauza.app.MainActivity
import ru.pauza.app.R
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

    private val launcherPackages: Set<String> by lazy {
        // Resolve the chosen HOME handler, not every HOME candidate.
        // MATCH_ALL included Settings/FallbackHome on Pixel emulators.
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val chosen = runCatching {
            packageManager.resolveActivity(
                homeIntent, PackageManager.MATCH_DEFAULT_ONLY
            )?.activityInfo?.packageName
        }.getOrNull()

        // Settings contains FallbackHome; never classify the Settings package
        // itself as navigation. Without a chosen Home, fail closed instead
        // of exempting all potential launchers.
        listOfNotNull(chosen)
            .filter { it != packageName && it != "com.android.settings" }
            .toSet()
    }

    private var lastReturnAt = 0L
    private var lastTraceAt = 0L
    private val confirmHomeNavigation = Runnable {
        // Only a real launcher window-state change starts this check. A
        // transient launcher appearance during app switching does not.
        val foreground = resolveForegroundPackage(null)
        val focusedLauncher = windows.any { window ->
            window.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                window.isFocused &&
                window.root?.packageName?.toString() in launcherPackages
        }
        val usageLauncher = UsageAccessMonitor.foregroundPackage(this) in launcherPackages
        if (foreground in launcherPackages && focusedLauncher && usageLauncher) {
            ShortVideoDiagnostics.log(this, "PauseNavigation", "HOME confirmed")
            hideShortNotice()
            hideOverlay()
            returnToPause()
        } else {
            ShortVideoDiagnostics.log(
                this, "PauseNavigation",
                "HOME ignored: foreground=$foreground focused=$focusedLauncher usage=$usageLauncher"
            )
        }
    }

    private var shortVideoNavigating = false
    private var shortVideoBlockedPackage: String? = null
    private var shortVideoRetryAt = 0L
    private var shortVideoRetryAttempts = 0
    private var pendingShortVideoNotice = false
    private var shortVideoBackFallbackUsed = false
    private var shortVideoCooldownUntil = 0L
    private var shortVideoUsesDetectedPlayerEscape = false
    private var shortVideoNavigationSucceeded = false
    private var shortVideoExitCandidateAt = 0L
    private var lastShortContentScanAt = 0L
    private var lastShortNoticeShownAt = 0L
    private var lastDiagnosticsForeground: String? = null
    private var shortNoticeDialog: AlertDialog? = null
    private var shortNoticePackage: String? = null
    private var shortNoticeArmed = true
    private var shortSafeSince = 0L

    private var wasUnavailableForUnlock = false
    private var resumeProtectionAt = 0L
    private var overlay: View? = null
    private var overlayTimer: TextView? = null
    private var lastAllowedForegroundPackage: String? = null
    private var blockedOriginPackage: String? = null
    private var returnAttemptId = 0

    private val watchdog = object : Runnable {
        override fun run() {
            // Liveness signal only: proves the service main loop is executing.
            serviceHeartbeatAtMs = SystemClock.elapsedRealtime()
            try {
                enforceSafely()
            } finally {
                handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceHeartbeatAtMs = SystemClock.elapsedRealtime()
        ShortVideoDiagnostics.log(this, "PauseShortVideo", "Accessibility service connected")
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
            ShortVideoDiagnostics.log(
                this,
                "PauseProtection",
                "enforce failed: ${error::class.java.simpleName}: ${error.message.orEmpty()}"
            )
        }
    }

    private fun enforceCurrentWindow(event: AccessibilityEvent? = null) {

        SavedSetScheduleEngine.maybeStart(this, store)

        val end = store.sessionEndEpochMs
        if (end <= 0L || System.currentTimeMillis() >= end) {
            resetShortVideoNavigation()
            hideShortNotice()
            NotificationSilencer.restoreAfterPause(this, store)
            hideOverlay()
            return
        }

        val screenInteractive = powerManager.isInteractive
        val deviceLocked = keyguardManager.isKeyguardLocked || keyguardManager.isDeviceLocked

        if (!screenInteractive || deviceLocked) {
            resetShortVideoNavigation()
            hideShortNotice()
            wasUnavailableForUnlock = true
            resumeProtectionAt = 0L
            hideOverlay()
            return
        }

        if (wasUnavailableForUnlock) {
            wasUnavailableForUnlock = false
            resumeProtectionAt = SystemClock.elapsedRealtime() + UNLOCK_GRACE_MS
            hideShortNotice()
            hideOverlay()
            return
        }

        if (SystemClock.elapsedRealtime() < resumeProtectionAt) {
            hideShortNotice()
            hideOverlay()
            return
        }

        // HOME and the launcher-owned Recents screen are navigation,
        // not an attempt to open a blocked application. Return to Pause
        // without displaying the gentle blocked-app notice.
        val foregroundPackage = resolveForegroundPackage(event) ?: return
        if (foregroundPackage in launcherPackages) {
            traceForeground("LAUNCHER", event, foregroundPackage)
            if (
                event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                event.packageName?.toString() in launcherPackages
            ) {
                handler.removeCallbacks(confirmHomeNavigation)
                handler.postDelayed(confirmHomeNavigation, 1100L)
            }
            return
        }
        // Any foreground app that is no longer the launcher cancels a
        // candidate HOME event before it can relaunch Pause.
        handler.removeCallbacks(confirmHomeNavigation)

        // Keep emergency controls, notification shade and power menu
        // accessible. Only a confirmed Recents/Overview surface returns to
        // Pause directly; do not suppress ordinary SystemUI interaction.
        if (hasActiveSystemUiSurface(event)) {
            traceForeground("SYSTEMUI_SKIP", event, foregroundPackage)
            hideShortNotice()
            hideOverlay()
            if (isRecentsNavigationEvent(event)) {
                ShortVideoDiagnostics.log(this, "PauseNavigation", "confirmed RECENTS/Overview")
                returnToPause()
            }
            return
        }

        if (
            store.blockShortVideos &&
            foregroundPackage != lastDiagnosticsForeground &&
            (ShortVideoDetector.isSupportedPackage(foregroundPackage) || shortVideoNavigating)
        ) {
            lastDiagnosticsForeground = foregroundPackage
            ShortVideoDiagnostics.log(
                this,
                "PauseForeground",
                "foreground=$foregroundPackage eventType=${event?.eventType} eventPackage=${event?.packageName} navigating=$shortVideoNavigating blockedPackage=$shortVideoBlockedPackage"
            )
        }

        if (shortNoticePackage != null && foregroundPackage != shortNoticePackage) {
            hideShortNotice()
            shortNoticeArmed = true
            shortSafeSince = 0L
        }
        if (shortVideoBlockedPackage != null && foregroundPackage != shortVideoBlockedPackage) {
            ShortVideoDiagnostics.log(
                this,
                "PauseShortVideo",
                "navigation package changed: blocked=$shortVideoBlockedPackage foreground=$foregroundPackage"
            )
            resetShortVideoNavigation()
            hideShortNotice()
        }

        // System UI itself is allowed, but a launcher is not: pressing Home may
        // briefly show the normal desktop, then Usage Access detects that launcher
        // as foreground and returns the user to the active Pause.
        if (foregroundPackage == packageName) {
            traceForeground("SELF", event, foregroundPackage)
            lastAllowedForegroundPackage = null
            hideOverlay()
            return
        }
        if (foregroundPackage == SYSTEM_UI_PACKAGE) {
            hideOverlay()
            return
        }

        val allowed = store.selectedPackages +
            appsRepository.alwaysAllowedPackages() +
            packageName

        if (foregroundPackage in allowed) {
            traceForeground("ALLOWED", event, foregroundPackage)
            lastAllowedForegroundPackage = foregroundPackage
            hideOverlay()

            if (store.blockShortVideos && ShortVideoDetector.isSupportedPackage(foregroundPackage)) {
                val nowElapsed = SystemClock.elapsedRealtime()

                // Accessibility window data can disappear briefly during navigation.
                // Missing data is treated as unknown, never as proof that the user
                // successfully left a short-video player.
                val appRoot = resolveApplicationRoot(foregroundPackage)
                if (appRoot == null) {
                    if (shortVideoNavigating) {
                        ShortVideoDiagnostics.log(
                            this,
                            "PauseShortVideo",
                            "application root missing during navigation package=$foregroundPackage"
                        )
                    }
                    shortSafeSince = 0L
                    shortVideoExitCandidateAt = 0L
                    return
                }

                val earlyEntryAction = ShortVideoDetector.isShortEntryAction(
                    packageName = foregroundPackage,
                    event = event,
                )

                if (earlyEntryAction && nowElapsed >= shortVideoCooldownUntil) {
                    ShortVideoDiagnostics.log(
                        this,
                        "PauseShortVideo",
                        "entry action detected package=$foregroundPackage eventType=${event?.eventType}"
                    )
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
                    shortVideoUsesDetectedPlayerEscape = true

                    if (!shortVideoNavigating) {
                        ShortVideoDiagnostics.log(
                            this,
                            "PauseShortVideo",
                            "short-video screen detected package=$foregroundPackage"
                        )
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
                        ShortVideoDiagnostics.log(
                            this,
                            "PauseShortVideo",
                            "retry detected-player escape package=$foregroundPackage attempt=$shortVideoRetryAttempts"
                        )
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
                            ShortVideoDiagnostics.log(
                                this,
                                "PauseShortVideo",
                                "retry safe-surface escape package=$foregroundPackage attempt=$shortVideoRetryAttempts"
                            )
                            navigateShortVideoEscape(
                                foregroundPackage = foregroundPackage,
                                appRoot = appRoot,
                            )
                        }
                        return
                    }

                    if (shortVideoExitCandidateAt == 0L) {
                        shortVideoExitCandidateAt = nowElapsed
                        ShortVideoDiagnostics.log(
                            this,
                            "PauseShortVideo",
                            "safe surface candidate package=$foregroundPackage"
                        )
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
                    shortVideoCooldownUntil = nowElapsed + SHORT_VIDEO_NAVIGATION_COOLDOWN_MS
                    ShortVideoDiagnostics.log(
                        this,
                        "PauseShortVideo",
                        "exit confirmed package=$foregroundPackage pendingNotice=$pendingShortVideoNotice"
                    )

                    if (pendingShortVideoNotice) {
                        pendingShortVideoNotice = false
                        if (
                            shortVideoNavigationSucceeded &&
                            shortNoticeArmed &&
                            nowElapsed - lastShortNoticeShownAt >= SHORT_VIDEO_NOTICE_MIN_GAP_MS
                        ) {
                            showShortVideoOverlay(foregroundPackage)
                        } else if (!shortVideoNavigationSucceeded) {
                            ShortVideoDiagnostics.log(
                                this,
                                "PauseShortVideo",
                                "notice suppressed: exit was not caused by successful navigation"
                            )
                        }
                    }
                }
            } else if (shortVideoNavigating) {
                resetShortVideoNavigation()
            }

            return
        }

        if (store.blockShortVideos) {
            ShortVideoDiagnostics.log(
                this,
                "PauseShortVideo",
                "general blocker package=$foregroundPackage navigating=$shortVideoNavigating blockedPackage=$shortVideoBlockedPackage"
            )
        }
        // A disallowed foreground reached from an app or external link is
        // covered by the calm Pause screen. HOME/RECENTS were handled above.
        // Keep it visible until the user chooses to return; automatic
        // relaunching would dismiss the message before it can be read.
        // If an OEM rejects the accessibility overlay, preserve enforcement.
        traceForeground("BLOCK", event, foregroundPackage)
        if (!showOverlay(end)) returnToPause()
    }

    private fun beginShortVideoRedirect(
        foregroundPackage: String,
        appRoot: android.view.accessibility.AccessibilityNodeInfo?,
        detectedPlayer: Boolean,
    ) {
        val now = SystemClock.elapsedRealtime()

        shortVideoUsesDetectedPlayerEscape = detectedPlayer
        shortVideoNavigationSucceeded = false
        shortVideoBackFallbackUsed = false
        shortVideoNavigating = true
        shortVideoBlockedPackage = foregroundPackage
        shortVideoRetryAt = now + SHORT_VIDEO_RETRY_DELAY_MS
        shortVideoRetryAttempts = 0
        shortVideoExitCandidateAt = 0L
        shortVideoCooldownUntil = now + SHORT_VIDEO_NAVIGATION_COOLDOWN_MS
        pendingShortVideoNotice = true
        ShortVideoDiagnostics.log(
            this,
            "PauseShortVideo",
            "begin redirect package=$foregroundPackage detectedPlayer=$detectedPlayer"
        )

        navigateShortVideoEscape(
            foregroundPackage = foregroundPackage,
            appRoot = appRoot,
        )
    }

    private fun navigateShortVideoEscape(
        foregroundPackage: String,
        appRoot: android.view.accessibility.AccessibilityNodeInfo?,
    ): Boolean {
        val result =
            if (shortVideoUsesDetectedPlayerEscape) {
                ShortVideoSafeNavigator.escapeDetectedPlayer(
                    service = this,
                    packageName = foregroundPackage,
                    root = appRoot,
                    attempt = shortVideoRetryAttempts,
                    allowBackFallback = !shortVideoNavigationSucceeded && !shortVideoBackFallbackUsed,
                )
            } else {
                ShortVideoSafeNavigator.navigateToSafeSurface(
                    service = this,
                    packageName = foregroundPackage,
                    root = appRoot,
                )
            }

        if (shortVideoUsesDetectedPlayerEscape && foregroundPackage == "com.instagram.android") {
            // Conservatively consume the fallback allowance after the first attempt,
            // even if Android rejects Back. Never repeatedly back out of Instagram.
            shortVideoBackFallbackUsed = true
        }
        if (result) {
            shortVideoNavigationSucceeded = true
            if (pendingShortVideoNotice && shortNoticeArmed &&
                SystemClock.elapsedRealtime() - lastShortNoticeShownAt >= SHORT_VIDEO_NOTICE_MIN_GAP_MS) {
                pendingShortVideoNotice = false
                showShortVideoOverlay(foregroundPackage)
            }
            // Give the accepted navigation time to settle before another action.
            shortVideoRetryAt = SystemClock.elapsedRealtime() + 900L
        }
        ShortVideoDiagnostics.log(
            this,
            "PauseShortVideo",
            "navigate package=$foregroundPackage detectedPlayer=$shortVideoUsesDetectedPlayerEscape attempt=$shortVideoRetryAttempts result=$result"
        )
        return result
    }

    private fun resetShortVideoNavigation() {
        if (shortVideoNavigating) {
            ShortVideoDiagnostics.log(
                this,
                "PauseShortVideo",
                "reset navigation blockedPackage=$shortVideoBlockedPackage attempts=$shortVideoRetryAttempts pendingNotice=$pendingShortVideoNotice"
            )
        }
        shortVideoNavigating = false
        shortVideoBlockedPackage = null
        shortVideoRetryAt = 0L
        shortVideoRetryAttempts = 0
        pendingShortVideoNotice = false
        shortVideoCooldownUntil = 0L
        shortVideoUsesDetectedPlayerEscape = false
        shortVideoNavigationSucceeded = false
        shortVideoExitCandidateAt = 0L
        lastShortContentScanAt = 0L
        shortSafeSince = 0L
        shortNoticeArmed = true
    }

    private fun showShortVideoOverlay(ownerPackage: String) {
        if (shortNoticeDialog?.isShowing == true) return

        var acknowledged = false
        val dialog = AlertDialog.Builder(
            this,
            android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
        )
            .setTitle("Короткие видео заблокированы")
            .setMessage("Во время Паузы Shorts, Reels и короткие видео RUTUBE недоступны.")
            .setPositiveButton(android.R.string.ok) { currentDialog, _ ->
                if (!acknowledged) {
                    acknowledged = true

                    val visibleDialog = shortNoticeDialog
                    visibleDialog
                        ?.getButton(AlertDialog.BUTTON_POSITIVE)
                        ?.isEnabled = false
                    visibleDialog?.hide()

                    pendingShortVideoNotice = false
                    shortNoticeArmed = false
                    shortSafeSince = 0L
                    ShortVideoDiagnostics.log(
                        this@PauseAccessibilityService,
                        "PauseShortVideo",
                        "notice acknowledged package=$ownerPackage"
                    )

                    shortNoticeDialog = null
                    shortNoticePackage = null
                    currentDialog.dismiss()

                    ShortVideoDiagnostics.log(
                        this@PauseAccessibilityService,
                        "PauseShortVideo",
                        "notice removed"
                    )
                }
            }
            .create()

        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.setTitle("PauzaShortVideoNotice")
        dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        dialog.setOnDismissListener {
            if (shortNoticeDialog === dialog) {
                shortNoticeDialog = null
                shortNoticePackage = null
                ShortVideoDiagnostics.log(
                    this@PauseAccessibilityService,
                    "PauseShortVideo",
                    "notice removed"
                )
            }
        }

        runCatching {
            shortNoticeDialog = dialog
            shortNoticePackage = ownerPackage
            shortNoticeArmed = false
            lastShortNoticeShownAt = SystemClock.elapsedRealtime()
            dialog.show()

            // Keep the OEM/system button completely native, but make it much
            // easier to hit. The enlarged touch target is invisible and does
            // not change the button's size, shape, colors or pressed state.
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            val decor = dialog.window?.decorView as? ViewGroup
            if (positiveButton != null) {
                // Keep the actual AlertDialog button, but give it Android's own
                // default button background instead of the borderless dialog style.
                positiveButton.setBackgroundResource(android.R.drawable.btn_default)
            }
            if (positiveButton != null && decor != null) {
                positiveButton.post {
                    if (
                        positiveButton.isAttachedToWindow &&
                        positiveButton.width > 0 &&
                        positiveButton.height > 0
                    ) {
                        val hitRect = Rect(0, 0, positiveButton.width, positiveButton.height)
                        decor.offsetDescendantRectToMyCoords(positiveButton, hitRect)

                        val extraHorizontal =
                            (48f * resources.displayMetrics.density).toInt()
                        val extraVertical =
                            (24f * resources.displayMetrics.density).toInt()

                        hitRect.left = (hitRect.left - extraHorizontal).coerceAtLeast(0)
                        hitRect.right = (hitRect.right + extraHorizontal)
                            .coerceAtMost(decor.width)
                        hitRect.top = (hitRect.top - extraVertical).coerceAtLeast(0)
                        hitRect.bottom = (hitRect.bottom + extraVertical)
                            .coerceAtMost(decor.height)

                        decor.touchDelegate = TouchDelegate(hitRect, positiveButton)
                    }
                }
            }

            ShortVideoDiagnostics.log(
                this,
                "PauseShortVideo",
                "notice shown package=$ownerPackage"
            )
        }.onFailure { error ->
            shortNoticeDialog = null
            shortNoticePackage = null
            ShortVideoDiagnostics.log(
                this,
                "PauseShortVideo",
                "notice show failed: ${error.javaClass.simpleName}: ${error.message}"
            )
        }
    }

    private fun resolveApplicationRoot(targetPackage: String) =
        windows
            .asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { it.isFocused }
            .mapNotNull { it.root }
            .firstOrNull { it.packageName?.toString() == targetPackage }
            ?: rootInActiveWindow?.takeIf {
                it.packageName?.toString() == targetPackage
            }

    private fun traceForeground(reason: String, event: AccessibilityEvent?, foreground: String?) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTraceAt < 850L) return
        lastTraceAt = now
        val visible = windows.take(12).joinToString(separator = ";") { win ->
            "${win.type}:${win.isFocused}:${win.isActive}:${win.root?.packageName?.toString()}"
        }
        Log.i(
            "PauseTrace",
            "reason=$reason foreground=$foreground usage=${UsageAccessMonitor.foregroundPackage(this)} " +
                "event=${event?.packageName}/${event?.eventType} " +
                "overlay=${overlay != null} windows=[$visible]"
        )
    }

    private fun isRecentsNavigationEvent(event: AccessibilityEvent?): Boolean {
        if (event == null) return false
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) return false
        val eventPackage = event.packageName?.toString() ?: return false
        if (eventPackage != SYSTEM_UI_PACKAGE && eventPackage !in launcherPackages) return false
        val screen = event.className?.toString()?.lowercase(Locale.ROOT).orEmpty()
        return "recents" in screen || "overview" in screen
    }

    private fun hasActiveSystemUiSurface(event: AccessibilityEvent?): Boolean {
        val eventPackage = event?.packageName?.toString()
        if (
            eventPackage == SYSTEM_UI_PACKAGE &&
            (
                event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                    event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                )
        ) {
            return true
        }

        return windows.asSequence()
            .filter { it.isActive || it.isFocused }
            .mapNotNull { it.root?.packageName?.toString() }
            .any { it == SYSTEM_UI_PACKAGE }
    }

    private fun resolveForegroundPackage(event: AccessibilityEvent?): String? {
        val currentApplication = windows.asSequence()
            .filter {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    !it.isInPictureInPictureMode
            }
            .filter { it.isActive || it.isFocused }
            .sortedWith(
                compareByDescending<AccessibilityWindowInfo> { it.isFocused }
                    .thenByDescending { it.isActive }
            )
            .mapNotNull { it.root?.packageName?.toString() }
            .firstOrNull()
            ?: shortNoticePackage?.takeIf { owner ->
                // A focusable notice owns focus; its visible underlying app is
                // still the foreground app even when Usage Access lags behind.
                shortNoticeDialog?.isShowing == true && windows.any { window ->
                    window.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                        window.root?.let { root ->
                            root.packageName?.toString() == owner && root.isVisibleToUser
                        } == true
                }
            }

        // Usage Access is normally the strongest foreground signal, but Pixel
        // launcher/Recents animations can leave a stale launcher ACTIVITY_RESUMED
        // event while another application's window is already focused again.
        val usagePackage = UsageAccessMonitor.foregroundPackage(this)
        if (!usagePackage.isNullOrBlank()) {
            // Some Android 13/OEM usage-event streams lag behind the visible
            // app. A genuinely focused different application must win over a
            // stale "Pause is foreground" report, or blocking can be bypassed.
            if (
                usagePackage == packageName &&
                !currentApplication.isNullOrBlank() &&
                currentApplication != packageName &&
                currentApplication != SYSTEM_UI_PACKAGE
            ) {
                return currentApplication
            }
            if (
                usagePackage in launcherPackages &&
                !currentApplication.isNullOrBlank() &&
                currentApplication !in launcherPackages &&
                currentApplication != SYSTEM_UI_PACKAGE
            ) {
                ShortVideoDiagnostics.log(
                    this,
                    "PauseForeground",
                    "prefer focused window=$currentApplication over transient launcher=$usagePackage"
                )
                return currentApplication
            }
            return usagePackage
        }

        // Fallback: accessibility windows/events for OEMs that delay usage events.
        if (!currentApplication.isNullOrBlank()) return currentApplication

        val rootPackage = rootInActiveWindow?.packageName?.toString()
        if (!rootPackage.isNullOrBlank()) return rootPackage

        return event?.packageName?.toString()
    }

    private fun returnToPause(fromOverlayTap: Boolean = false) {
        Log.i("PauseTrace", "returnToPause called tap=$fromOverlayTap overlay=${overlay != null} current=${resolveForegroundPackage(null)}")
        val now = SystemClock.elapsedRealtime()
        // A user tap must never be discarded by the watchdog debounce.
        if (!fromOverlayTap && now - lastReturnAt < RETURN_DEBOUNCE_MS) return
        lastReturnAt = now

        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }

        if (!fromOverlayTap) {
            // The automatic return remains unchanged. Android may deny a
            // background activity launch without throwing an exception.
            runCatching { startActivity(intent) }.onFailure {
                Log.w("PauseProtection", "Automatic return to Pause failed", it)
            }
            return
        }

        // Accessibility services may relaunch an activity directly. Try this
        // first for the tap; Android can silently ignore PendingIntent.send().
        runCatching { startActivity(intent) }.onFailure {
            Log.w("PauseProtection", "Direct overlay-tap return failed", it)
        }

        // Some Android 14/15 builds refuse or delay the direct background
        // launch. Retry via PendingIntent only if the notice remains visible.
        handler.postDelayed({
            if (overlay == null || store.sessionEndEpochMs <= System.currentTimeMillis()) {
                return@postDelayed
            }
            val creatorOptions = if (Build.VERSION.SDK_INT >= 35) {
                ActivityOptions.makeBasic().apply {
                    pendingIntentCreatorBackgroundActivityStartMode =
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                }.toBundle()
            } else null
            val senderOptions = if (Build.VERSION.SDK_INT >= 34) {
                ActivityOptions.makeBasic().apply {
                    pendingIntentBackgroundActivityStartMode =
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                }.toBundle()
            } else null
            runCatching {
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    creatorOptions
                ).send(senderOptions)
            }.onFailure {
                Log.w("PauseProtection", "PendingIntent overlay-tap retry failed", it)
            }
        }, 420L)
        handler.postDelayed({
            if (overlay != null && store.sessionEndEpochMs > System.currentTimeMillis()) {
                runCatching { startActivity(intent) }.onFailure {
                    Log.e("PauseProtection", "Final overlay-tap return retry failed", it)
                }
            }
        }, 1_500L)
    }

    /**
     * Go back to the previously allowed app; re-open it only if Back failed.
     * Fall back to Pause after a bounded attempt to preserve an escape route.
     */
    private fun returnFromBlockedApp() {
        val target = blockedOriginPackage?.takeIf {
            it in store.selectedPackages + appsRepository.alwaysAllowedPackages()
        }
        if (target == null) {
            ShortVideoDiagnostics.log(this, "PauseReturn", "no origin; opening Pause")
            returnToPause(fromOverlayTap = true)
            return
        }

        val attempt = ++returnAttemptId
        ShortVideoDiagnostics.log(this, "PauseReturn", "return tapped target=$target")
        performGlobalAction(GLOBAL_ACTION_BACK)

        handler.postDelayed({
            if (attempt != returnAttemptId || overlay == null) return@postDelayed
            if (resolveForegroundPackage(null) == target) {
                hideOverlay()
                return@postDelayed
            }

            val intent = packageManager.getLaunchIntentForPackage(target)
                ?: if (target in appsRepository.alwaysAllowedPackages()) {
                    Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:"))
                } else null
            if (intent != null) {
                intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                runCatching { startActivity(intent) }.onFailure {
                    ShortVideoDiagnostics.log(
                        this, "PauseReturn",
                        "direct launch failed target=$target error=${it.javaClass.simpleName}"
                    )
                }
            }
        }, 700L)

        handler.postDelayed({
            if (attempt != returnAttemptId || overlay == null) return@postDelayed
            if (resolveForegroundPackage(null) == target) {
                hideOverlay()
                return@postDelayed
            }

            val intent = packageManager.getLaunchIntentForPackage(target)
            if (intent != null) {
                intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                val creatorOptions = if (Build.VERSION.SDK_INT >= 35) {
                    ActivityOptions.makeBasic().apply {
                        pendingIntentCreatorBackgroundActivityStartMode =
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    }.toBundle()
                } else null
                val senderOptions = if (Build.VERSION.SDK_INT >= 34) {
                    ActivityOptions.makeBasic().apply {
                        pendingIntentBackgroundActivityStartMode =
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    }.toBundle()
                } else null
                runCatching {
                    PendingIntent.getActivity(
                        this,
                        target.hashCode(),
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        creatorOptions
                    ).send(senderOptions)
                }.onFailure {
                    ShortVideoDiagnostics.log(
                        this, "PauseReturn",
                        "pending launch failed target=$target error=${it.javaClass.simpleName}"
                    )
                }
            }
        }, 1650L)

        handler.postDelayed({
            if (attempt != returnAttemptId || overlay == null) return@postDelayed
            if (resolveForegroundPackage(null) == target) {
                hideOverlay()
                return@postDelayed
            }
            ShortVideoDiagnostics.log(this, "PauseReturn", "fallback to Pause target=$target")
            returnToPause(fromOverlayTap = true)
        }, 3200L)
    }

    private fun showOverlay(sessionEnd: Long): Boolean {
        updateOverlayTimer(sessionEnd)
        if (overlay != null) return true

        blockedOriginPackage = lastAllowedForegroundPackage?.takeIf { target ->
            target in store.selectedPackages + appsRepository.alwaysAllowedPackages()
        }
        ShortVideoDiagnostics.log(
            this, "PauseReturn",
            "blocked foreground=${resolveForegroundPackage(null)} target=$blockedOriginPackage"
        )

        fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
        fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radius).toFloat()
            setColor(color)
        }
        fun text(
            value: String,
            size: Float,
            color: Int,
            bold: Boolean = false,
        ) = TextView(this).apply {
            this.text = value
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(247, 248, 244))
            isClickable = true  // Consume touches: nothing reaches the blocked app.
            isFocusable = true
        }

        val scroller = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
        }
        root.addView(
            scroller,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(32), dp(28), dp(32))
        }
        scroller.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val leafBackground = FrameLayout(this).apply {
            background = rounded(Color.rgb(229, 237, 227), 22)
        }
        content.addView(
            leafBackground,
            LinearLayout.LayoutParams(dp(76), dp(76)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        )
        leafBackground.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_pauza_leaf)
                contentDescription = null
            },
            FrameLayout.LayoutParams(dp(43), dp(43), Gravity.CENTER)
        )

        val title = text("Это может подождать", 26f, Color.rgb(38, 56, 42), true)
        content.addView(
            title,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(25) }
        )

        val message = text(
            "Вы решили сделать паузу. Она продолжается, даже если вы случайно открыли что-то лишнее.",
            15f,
            Color.rgb(100, 114, 104)
        ).apply {
            setLineSpacing(dp(4).toFloat(), 1f)
        }
        content.addView(
            message,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        )

        val caption = text("До окончания паузы", 13f, Color.rgb(100, 114, 104))
        content.addView(
            caption,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(30) }
        )

        val timer = text("", 38f, Color.rgb(38, 56, 42), true).apply {
            setSingleLine(true)
        }
        overlayTimer = timer
        content.addView(
            timer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        )

        val previousAppLabel = blockedOriginPackage?.let { target ->
            runCatching {
                val info = packageManager.getApplicationInfo(target, 0)
                packageManager.getApplicationLabel(info).toString()
            }.getOrNull()
        }?.take(24)
        val returnButton = text(
            if (blockedOriginPackage == null) "Вернуться в «Паузу»"
            else "Вернуться в ${previousAppLabel ?: "приложение"}",
            16f,
            Color.rgb(52, 91, 61),
            true
        ).apply {
            background = rounded(Color.rgb(229, 237, 227), 16)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                Log.i("PauseTrace", "blocked notice BUTTON_CLICK target=$blockedOriginPackage")
                returnFromBlockedApp()
            }
        }
        content.addView(
            returnButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(56)
            ).apply { topMargin = dp(29) }
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        )

        return runCatching {
            getSystemService(WindowManager::class.java).addView(root, params)
            overlay = root
            updateOverlayTimer(sessionEnd)
            true
        }.onFailure {
            Log.e("PauseProtection", "Cannot display blocked-app notice", it)
            overlayTimer = null
        }.getOrDefault(false)
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
        blockedOriginPackage = null
        returnAttemptId++
    }

    private fun hideShortNotice() {
        val current = shortNoticeDialog ?: return
        runCatching {
            current.dismiss()
        }.onFailure { error ->
            ShortVideoDiagnostics.log(
                this,
                "PauseShortVideo",
                "notice removal failed: ${error.javaClass.simpleName}: ${error.message}"
            )
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        serviceHeartbeatAtMs = 0L
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(confirmHomeNavigation)
        ShortVideoDiagnostics.log(this, "PauseHealth", "Accessibility service unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        serviceHeartbeatAtMs = 0L
        handler.removeCallbacks(watchdog)
        handler.removeCallbacks(confirmHomeNavigation)
        hideShortNotice()
        hideOverlay()
        super.onDestroy()
    }

    companion object {
        @Volatile private var serviceHeartbeatAtMs = 0L

        fun isServiceResponding(): Boolean {
            val last = serviceHeartbeatAtMs
            return last > 0L && SystemClock.elapsedRealtime() - last < 2_500L
        }

        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val RETURN_DEBOUNCE_MS = 180L
        private const val WATCHDOG_INTERVAL_MS = 200L
        private const val UNLOCK_GRACE_MS = 1_000L

        private const val SHORT_VIDEO_RETRY_DELAY_MS = 420L
        private const val SHORT_VIDEO_MAX_RETRIES = 4
        private const val SHORT_VIDEO_NAVIGATION_COOLDOWN_MS = 2_800L
        private const val SHORT_VIDEO_EXIT_CONFIRM_MS = 450L
        private const val SHORT_VIDEO_NOTICE_REARM_MS = 2_000L
        private const val SHORT_VIDEO_NOTICE_MIN_GAP_MS = 3_000L
        private const val SHORT_CONTENT_SCAN_THROTTLE_MS = 300L
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
