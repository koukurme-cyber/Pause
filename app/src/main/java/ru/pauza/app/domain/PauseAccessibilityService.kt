package ru.pauza.app.domain

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.view.accessibility.AccessibilityWindowInfo
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
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
import kotlin.math.max

class PauseAccessibilityService : AccessibilityService() {
    private val store by lazy { PauseStore(this) }
    private val appsRepository by lazy { InstalledAppsRepository(this) }
    private val handler = Handler(Looper.getMainLooper())
    private val keyguardManager by lazy { getSystemService(KeyguardManager::class.java) }
    private val powerManager by lazy { getSystemService(PowerManager::class.java) }

    private var lastReturnAt = 0L
    private var shortVideoNavigating = false
    private var shortVideoBlockedPackage: String? = null
    private var shortVideoRetryAt = 0L
    private var shortVideoRetryAvailable = false
    private var pendingShortVideoNotice = false
    private var shortVideoCooldownUntil = 0L
    private var wasUnavailableForUnlock = false
    private var resumeProtectionAt = 0L
    private var blockingOverlay: View? = null
    private var blockingOverlayTimer: TextView? = null
    private var shortNoticeOverlay: View? = null
    private var shortNoticeDismissRunnable: Runnable? = null
    private var forbiddenOverlayRunnable: Runnable? = null

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
            resetShortVideoNavigation()
            cancelForbiddenOverlay()
            hideBlockingOverlay()
            hideShortNotice()
            return
        }

        val screenInteractive = powerManager.isInteractive
        val deviceLocked = keyguardManager.isKeyguardLocked || keyguardManager.isDeviceLocked

        if (!screenInteractive || deviceLocked) {
            wasUnavailableForUnlock = true
            resumeProtectionAt = 0L
            cancelForbiddenOverlay()
            hideBlockingOverlay()
            hideShortNotice()
            return
        }

        if (wasUnavailableForUnlock) {
            wasUnavailableForUnlock = false
            resumeProtectionAt = SystemClock.elapsedRealtime() + UNLOCK_GRACE_MS
            cancelForbiddenOverlay()
            hideBlockingOverlay()
            hideShortNotice()
            return
        }

        if (SystemClock.elapsedRealtime() < resumeProtectionAt) {
            cancelForbiddenOverlay()
            hideBlockingOverlay()
            hideShortNotice()
            return
        }

        val foregroundPackage = resolveForegroundPackage(event) ?: return
        val allowed = store.selectedPackages +
            appsRepository.alwaysAllowedPackages() +
            packageName

        if (foregroundPackage in allowed) {
            if (store.blockShortVideos) {
                val nowElapsed = SystemClock.elapsedRealtime()
                val appRoot = resolveApplicationRoot(foregroundPackage)
                val earlyEntryAction = ShortVideoDetector.isShortEntryAction(
                    packageName = foregroundPackage,
                    event = event,
                )
                val shortVideoDetected = ShortVideoDetector.isShortVideoScreen(
                    packageName = foregroundPackage,
                    root = appRoot,
                    event = event,
                )

                if (earlyEntryAction) {
                    beginShortVideoRedirect(
                        foregroundPackage = foregroundPackage,
                        appRoot = appRoot,
                    )
                    return
                }

                if (shortVideoDetected) {
                    if (!shortVideoNavigating || nowElapsed >= shortVideoCooldownUntil) {
                        beginShortVideoRedirect(
                            foregroundPackage = foregroundPackage,
                            appRoot = appRoot,
                        )
                    } else if (
                        shortVideoRetryAvailable &&
                        nowElapsed >= shortVideoRetryAt &&
                        foregroundPackage == shortVideoBlockedPackage
                    ) {
                        shortVideoRetryAvailable = false
                        ShortVideoSafeNavigator.navigateToSafeSurface(
                            service = this,
                            packageName = foregroundPackage,
                            root = appRoot,
                        )
                    }
                    return
                }

                if (
                    shortVideoNavigating &&
                    foregroundPackage == shortVideoBlockedPackage
                ) {
                    shortVideoNavigating = false
                    shortVideoRetryAvailable = false
                    shortVideoBlockedPackage = null
                    if (pendingShortVideoNotice) {
                        pendingShortVideoNotice = false
                        showShortVideoOverlay()
                    }
                }
            } else {
                resetShortVideoNavigation()
            }

            cancelForbiddenOverlay()
            hideBlockingOverlay()
            return
        }

        returnToPause()
        scheduleForbiddenOverlay(end)
    }


    private fun beginShortVideoRedirect(
        foregroundPackage: String,
        appRoot: android.view.accessibility.AccessibilityNodeInfo?,
    ) {
        val now = SystemClock.elapsedRealtime()

        shortVideoNavigating = true
        shortVideoBlockedPackage = foregroundPackage
        shortVideoRetryAt = now + SHORT_VIDEO_RETRY_DELAY_MS
        shortVideoRetryAvailable = true
        shortVideoCooldownUntil = now + SHORT_VIDEO_NAVIGATION_COOLDOWN_MS
        pendingShortVideoNotice = true

        hideShortNotice()

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
        shortVideoRetryAvailable = false
        pendingShortVideoNotice = false
        shortVideoCooldownUntil = 0L
    }

    private fun showShortVideoOverlay() {
        if (shortNoticeOverlay != null) return

        cancelForbiddenOverlay()
        hideBlockingOverlay()

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

        val action = TextView(this).apply {
            text = "Понятно"
            gravity = Gravity.CENTER
            textSize = 12.5f
            setTextColor(Color.rgb(57, 103, 70))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            isClickable = true
            setPadding(dp(8), dp(10), dp(4), dp(10))
            setOnClickListener { hideShortNotice() }
        }
        card.addView(
            action,
            LinearLayout.LayoutParams(dp(78), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(6)
            }
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

            shortNoticeDismissRunnable?.let(handler::removeCallbacks)
            shortNoticeDismissRunnable = Runnable {
                hideShortNotice()
            }.also { runnable ->
                handler.postDelayed(runnable, SHORT_VIDEO_NOTICE_DURATION_MS)
            }
        }
    }

    private fun resolveApplicationRoot(targetPackage: String) =
        windows
            .asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .mapNotNull { it.root }
            .firstOrNull { it.packageName?.toString() == targetPackage }

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

    private fun resolveForegroundPackage(event: AccessibilityEvent?): String? {
        val focusedApplication = windows
            .asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedByDescending { it.isFocused }
            .mapNotNull { it.root?.packageName?.toString() }
            .firstOrNull { it != SYSTEM_UI_PACKAGE }

        if (focusedApplication != null) return focusedApplication

        val rootPackage = rootInActiveWindow?.packageName?.toString()
        if (!rootPackage.isNullOrBlank() && rootPackage != SYSTEM_UI_PACKAGE) {
            return rootPackage
        }

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

    private fun scheduleForbiddenOverlay(sessionEnd: Long) {
        if (blockingOverlay != null || forbiddenOverlayRunnable != null) return

        forbiddenOverlayRunnable = Runnable {
            forbiddenOverlayRunnable = null

            val foregroundPackage = resolveForegroundPackage(null) ?: return@Runnable
            val allowed = store.selectedPackages +
                appsRepository.alwaysAllowedPackages() +
                packageName

            if (
                store.sessionEndEpochMs > System.currentTimeMillis() &&
                foregroundPackage !in allowed
            ) {
                showBlockingOverlay(sessionEnd)
            }
        }.also { runnable ->
            handler.postDelayed(runnable, FORBIDDEN_OVERLAY_DELAY_MS)
        }
    }

    private fun cancelForbiddenOverlay() {
        forbiddenOverlayRunnable?.let(handler::removeCallbacks)
        forbiddenOverlayRunnable = null
    }

    private fun showBlockingOverlay(sessionEnd: Long) {
        if (blockingOverlay != null) {
            updateBlockingOverlayTimer(sessionEnd)
            return
        }

        hideShortNotice()

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
        blockingOverlayTimer = timer
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
            blockingOverlay = root
            updateBlockingOverlayTimer(sessionEnd)
        }
    }

    private fun updateBlockingOverlayTimer(sessionEnd: Long) {
        blockingOverlayTimer?.text =
            formatRemaining(max(0L, sessionEnd - System.currentTimeMillis()))
    }

    private fun hideBlockingOverlay() {
        val current = blockingOverlay ?: return
        runCatching {
            getSystemService(WindowManager::class.java).removeView(current)
        }
        blockingOverlay = null
        blockingOverlayTimer = null
    }

    private fun hideShortNotice() {
        shortNoticeDismissRunnable?.let(handler::removeCallbacks)
        shortNoticeDismissRunnable = null

        val current = shortNoticeOverlay ?: return
        runCatching {
            getSystemService(WindowManager::class.java).removeView(current)
        }
        shortNoticeOverlay = null
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        cancelForbiddenOverlay()
        hideBlockingOverlay()
        hideShortNotice()
        super.onDestroy()
    }

    companion object {
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val RETURN_DEBOUNCE_MS = 250L
        private const val WATCHDOG_INTERVAL_MS = 300L
        private const val UNLOCK_GRACE_MS = 1_000L
        private const val SHORT_VIDEO_RETRY_DELAY_MS = 450L
        private const val SHORT_VIDEO_NAVIGATION_COOLDOWN_MS = 2_800L
        private const val SHORT_VIDEO_NOTICE_DURATION_MS = 3_500L
        private const val FORBIDDEN_OVERLAY_DELAY_MS = 220L
    }
}

private fun formatRemaining(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
}
