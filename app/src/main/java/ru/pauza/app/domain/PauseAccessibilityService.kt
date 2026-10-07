package ru.pauza.app.domain

import android.accessibilityservice.AccessibilityService
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
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
    private var launcherCandidatePackage: String? = null
    private var launcherCandidateSince = 0L

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

        val eventPackage = event?.packageName?.toString()
        val focusedApplication = currentApplicationPackage()

        // Recents is an escape route during an active Pause and must never remain open.
        if (isSystemEscapeEvent(event)) {
            clearLauncherCandidate()
            ShortVideoDiagnostics.log(
                this,
                "PauseEscape",
                "recents blocked eventPackage=$eventPackage focused=$focusedApplication"
            )
            resetShortVideoNavigation()
            hideShortNotice()
            hideOverlay()
            returnToPause(force = true)
            return
        }

        // A launcher that owns the focused application window is a real Home escape.
        // This is the strongest signal and can be blocked immediately.
        if (focusedApplication in launcherPackages) {
            clearLauncherCandidate()
            ShortVideoDiagnostics.log(
                this,
                "PauseEscape",
                "home blocked from focused launcher=$focusedApplication eventPackage=$eventPackage"
            )
            resetShortVideoNavigation()
            hideShortNotice()
            hideOverlay()
            returnToPause(force = true)
            return
        }

        // Pixel/Android transitions can briefly report the launcher through Usage
        // Access while no application window is focused (notably on Reels entry).
        // Require that weaker signal to persist before treating it as Home.
        if (shortNoticeDialog?.isShowing == true) {
            // Our modal notice temporarily owns system focus. During that time
            // Usage Access may keep reporting a stale launcher for several seconds.
            // Never treat that weak signal as Home while the notice is visible.
            clearLauncherCandidate()
        } else if (focusedApplication.isNullOrBlank()) {
            val usageForeground = UsageAccessMonitor.foregroundPackage(this)
            val launcherCandidate = usageForeground?.takeIf { it in launcherPackages }
            if (launcherCandidate != null) {
                if (launcherEscapeConfirmed(launcherCandidate)) {
                    ShortVideoDiagnostics.log(
                        this,
                        "PauseEscape",
                        "home blocked after launcher confirmation=$launcherCandidate"
                    )
                    resetShortVideoNavigation()
                    hideShortNotice()
                    hideOverlay()
                    returnToPause(force = true)
                }
                return
            }
        } else {
            clearLauncherCandidate()
        }

        // Other transient Android system surfaces (power menu, notification shade,
        // permission/system dialogs) remain available.
        if (hasActiveSystemUiSurface(event)) {
            hideShortNotice()
            hideOverlay()
            return
        }

        val foregroundPackage = resolveForegroundPackage(event) ?: return

        if (foregroundPackage in launcherPackages) {
            if (launcherEscapeConfirmed(foregroundPackage)) {
                ShortVideoDiagnostics.log(
                    this,
                    "PauseEscape",
                    "launcher foreground blocked package=$foregroundPackage"
                )
                resetShortVideoNavigation()
                hideShortNotice()
                hideOverlay()
                returnToPause(force = true)
            }
            return
        } else {
            clearLauncherCandidate()
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
        showOverlay(end)
        returnToPause()
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

    private fun currentApplicationPackage(): String? =
        windows.asSequence()
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

    private fun launcherEscapeConfirmed(candidate: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (launcherCandidatePackage != candidate) {
            launcherCandidatePackage = candidate
            launcherCandidateSince = now
            return false
        }
        return now - launcherCandidateSince >= HOME_CONFIRM_MS
    }

    private fun clearLauncherCandidate() {
        launcherCandidatePackage = null
        launcherCandidateSince = 0L
    }

    private fun isSystemEscapeEvent(event: AccessibilityEvent?): Boolean {
        if (event?.packageName?.toString() != SYSTEM_UI_PACKAGE) return false

        val signature = buildString {
            append(event.className?.toString().orEmpty())
            append(' ')
            append(event.contentDescription?.toString().orEmpty())
            append(' ')
            event.text.forEach {
                append(it?.toString().orEmpty())
                append(' ')
            }
        }.lowercase()

        return listOf(
            "recent",
            "recents",
            "overview",
            "quickstep",
            "taskview",
            "task_view",
        ).any(signature::contains)
    }

    private fun resolveForegroundPackage(event: AccessibilityEvent?): String? {
        val currentApplication = currentApplicationPackage()
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

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        hideShortNotice()
        hideOverlay()
        super.onDestroy()
    }

    companion object {
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val RETURN_DEBOUNCE_MS = 180L
        private const val HOME_CONFIRM_MS = 450L
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
