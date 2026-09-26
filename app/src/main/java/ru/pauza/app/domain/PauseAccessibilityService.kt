package ru.pauza.app.domain

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import ru.pauza.app.MainActivity
import ru.pauza.app.R
import ru.pauza.app.data.InstalledAppsRepository
import ru.pauza.app.data.PauseStore
import ru.pauza.app.model.InstalledApp
import java.util.Locale
import kotlin.math.max

class PauseAccessibilityService : AccessibilityService() {
    private val store by lazy { PauseStore(this) }
    private val appsRepository by lazy { InstalledAppsRepository(this) }
    private val windowManager by lazy { getSystemService(WindowManager::class.java) }
    private val keyguardManager by lazy { getSystemService(KeyguardManager::class.java) }
    private val powerManager by lazy { getSystemService(PowerManager::class.java) }
    private val handler = Handler(Looper.getMainLooper())
    private val launcherPackages by lazy {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        packageManager.queryIntentActivities(homeIntent, PackageManager.MATCH_ALL)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet() - packageName
    }

    private var overlay: View? = null
    private var overlayTimer: TextView? = null
    private var overlaySessionEnd = 0L
    private var suppressOverlayUntil = 0L
    private var pendingAllowedPackage: String? = null
    private var trustedAllowedPackage: String? = null
    private var trustedAllowedUntil = 0L
    private var tapCount = 0
    private var lastTapAt = 0L

    private val watchdog = object : Runnable {
        override fun run() {
            try {
                enforceCurrentState()
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
        if (isRecentsSurface(event)) {
            suppressOverlayUntil = 0L
            pendingAllowedPackage = null
            performGlobalAction(GLOBAL_ACTION_BACK)
            handler.postDelayed({ enforceCurrentState() }, RECENTS_RETURN_DELAY_MS)
            return
        }

        val eventPackage = event?.packageName?.toString()
        if (eventPackage == SYSTEM_UI_PACKAGE) {
            updateOverlayTimer()
            return
        }

        // Only real window transitions may change the foreground decision.
        // Content/click/selection events from a previous or background app are
        // deliberately ignored because they were causing false re-blocks.
        if (
            event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            enforceCurrentState(event)
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (
            event.keyCode == KeyEvent.KEYCODE_BACK &&
            event.action == KeyEvent.ACTION_UP &&
            isSessionActive()
        ) {
            suppressOverlayUntil = 0L
            pendingAllowedPackage = null
            handler.postDelayed({ enforceCurrentState() }, BACK_RETURN_DELAY_MS)
        }

        // Never consume Back. Allowed apps keep normal in-app navigation.
        return false
    }

    private fun enforceCurrentState(event: AccessibilityEvent? = null) {
        val sessionEnd = store.sessionEndEpochMs
        if (sessionEnd <= 0L || System.currentTimeMillis() >= sessionEnd) {
            hideOverlay()
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            hideOverlay()
            return
        }

        if (!powerManager.isInteractive || keyguardManager.isKeyguardLocked || keyguardManager.isDeviceLocked) {
            hideOverlay()
            return
        }

        updateOverlayTimer(sessionEnd)

        val allowedPackages = store.selectedPackages +
            appsRepository.alwaysAllowedPackages() +
            packageName

        val eventPackage = event?.packageName?.toString()
        val nowElapsed = SystemClock.elapsedRealtime()
        val isWindowTransition =
            event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event?.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED

        // Explicit launcher/disallowed transitions are authoritative even while
        // an allowed app is settling after launch.
        if (
            isWindowTransition &&
            !eventPackage.isNullOrBlank() &&
            eventPackage in launcherPackages
        ) {
            pendingAllowedPackage = null
            trustedAllowedPackage = null
            suppressOverlayUntil = 0L
            trustedAllowedUntil = 0L
            showOverlay(sessionEnd)
            return
        }

        if (
            isWindowTransition &&
            !eventPackage.isNullOrBlank() &&
            eventPackage != SYSTEM_UI_PACKAGE &&
            eventPackage !in allowedPackages
        ) {
            pendingAllowedPackage = null
            trustedAllowedPackage = null
            suppressOverlayUntil = 0L
            trustedAllowedUntil = 0L
            showOverlay(sessionEnd)
            return
        }

        val launchPending = pendingAllowedPackage
        val launchGraceActive =
            launchPending != null && nowElapsed < suppressOverlayUntil

        if (launchGraceActive) {
            if (eventPackage == launchPending) {
                pendingAllowedPackage = null
                suppressOverlayUntil = 0L
                trustedAllowedPackage = launchPending
                trustedAllowedUntil = nowElapsed + ALLOWED_SETTLE_MS
                hideOverlay()
                return
            }

            // Watchdog/transition noise from the surface underneath must not
            // recreate the blocker while Android is opening the requested app.
            return
        } else if (launchPending != null) {
            pendingAllowedPackage = null
            suppressOverlayUntil = 0L
        }

        if (
            isWindowTransition &&
            !eventPackage.isNullOrBlank() &&
            eventPackage in allowedPackages
        ) {
            trustedAllowedPackage = eventPackage
            trustedAllowedUntil = nowElapsed + ALLOWED_SETTLE_MS
            hideOverlay()
            return
        }

        // After Android reports an allowed app, trust that fresh transition for
        // a short settle window. Watchdog UsageStats can lag behind app startup
        // and previously recreated the overlay over the allowed app.
        if (
            event == null &&
            trustedAllowedPackage != null &&
            nowElapsed < trustedAllowedUntil
        ) {
            hideOverlay()
            return
        }

        if (nowElapsed >= trustedAllowedUntil) {
            trustedAllowedPackage = null
            trustedAllowedUntil = 0L
        }

        val foregroundPackage = resolveForegroundPackage(event) ?: return

        if (foregroundPackage in allowedPackages) {
            hideOverlay()
            return
        }

        showOverlay(sessionEnd)
    }

    private fun isSessionActive(): Boolean {
        val end = store.sessionEndEpochMs
        return end > 0L && System.currentTimeMillis() < end
    }

    private fun resolveForegroundPackage(event: AccessibilityEvent?): String? {
        val eventPackage = event?.packageName?.toString()
        if (
            !eventPackage.isNullOrBlank() &&
            eventPackage != SYSTEM_UI_PACKAGE &&
            (
                event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                    event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
                )
        ) {
            return eventPackage
        }

        val applicationWindow = windows.asSequence()
            .filter {
                it.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                    (it.isActive || it.isFocused)
            }
            .sortedByDescending { it.isActive }
            .mapNotNull { it.root?.packageName?.toString() }
            .firstOrNull { it != SYSTEM_UI_PACKAGE }

        if (!applicationWindow.isNullOrBlank()) return applicationWindow

        val rootPackage = rootInActiveWindow?.packageName?.toString()
        if (!rootPackage.isNullOrBlank() && rootPackage != SYSTEM_UI_PACKAGE) {
            return rootPackage
        }

        // UsageStats is only a fallback when Accessibility cannot identify an
        // active application window. It is never allowed to override fresh UI.
        return UsageAccessMonitor.foregroundPackage(this)
    }

    private fun isRecentsSurface(event: AccessibilityEvent?): Boolean {
        event ?: return false

        val eventPackage = event.packageName?.toString().orEmpty()
        if (
            eventPackage != SYSTEM_UI_PACKAGE &&
            eventPackage !in launcherPackages
        ) {
            return false
        }

        val className = event.className?.toString()?.lowercase(Locale.ROOT).orEmpty()
        val sourceId = event.source?.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
        val eventText = event.text.joinToString(" ").lowercase(Locale.ROOT)

        return RECENTS_HINTS.any { hint ->
            className.contains(hint) ||
                sourceId.contains(hint) ||
                eventText.contains(hint)
        }
    }

    private fun showOverlay(sessionEnd: Long) {
        if (overlay != null) {
            if (overlaySessionEnd != sessionEnd) {
                overlaySessionEnd = sessionEnd
            }
            updateOverlayTimer(sessionEnd)
            return
        }

        val root = buildOverlay(sessionEnd)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        runCatching {
            windowManager.addView(root, params)
            overlay = root
            overlaySessionEnd = sessionEnd
            updateOverlayTimer(sessionEnd)
        }
    }

    private fun buildOverlay(sessionEnd: Long): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(247, 248, 244))
        }

        val background = ImageView(this).apply {
            setImageResource(R.drawable.pauza_active_concept_bg)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        root.addView(
            background,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(36), dp(22), dp(24))
        }
        root.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val title = TextView(this).apply {
            text = "●  Пауза"
            setTextColor(Color.rgb(23, 33, 25))
            textSize = 19f
            gravity = Gravity.CENTER
        }
        content.addView(
            title,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val subtitle = TextView(this).apply {
            text = "Доступны только выбранные приложения"
            setTextColor(Color.rgb(89, 99, 89))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(24))
        }
        content.addView(
            subtitle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val timerCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(16), dp(18), dp(16))
            setBackground(
                roundedBackground(
                    fill = Color.rgb(255, 254, 250),
                    stroke = Color.rgb(220, 227, 217),
                    radiusDp = 26f
                )
            )
            setOnClickListener { registerExitTap() }
        }
        content.addView(
            timerCard,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val timerLabel = TextView(this).apply {
            text = "Осталось"
            setTextColor(Color.rgb(89, 99, 89))
            textSize = 13f
            gravity = Gravity.CENTER
        }
        timerCard.addView(timerLabel)

        val timer = TextView(this).apply {
            setTextColor(Color.rgb(30, 104, 66))
            textSize = 38f
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, 0)
            text = formatRemaining(max(0L, sessionEnd - System.currentTimeMillis()))
        }
        overlayTimer = timer
        timerCard.addView(
            timer,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val appsTitle = TextView(this).apply {
            text = "Доступные приложения"
            setTextColor(Color.rgb(89, 99, 89))
            textSize = 12f
            setPadding(0, dp(26), 0, dp(14))
        }
        content.addView(
            appsTitle,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val grid = GridLayout(this).apply {
            columnCount = 4
            alignmentMode = GridLayout.ALIGN_BOUNDS
            useDefaultMargins = false
        }
        content.addView(
            grid,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        currentShortcuts().forEach { app ->
            grid.addView(
                buildShortcut(app),
                GridLayout.LayoutParams().apply {
                    width = 0
                    height = GridLayout.LayoutParams.WRAP_CONTENT
                    columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    setMargins(dp(4), dp(6), dp(4), dp(12))
                }
            )
        }

        return root
    }

    private fun currentShortcuts(): List<InstalledApp> {
        val selected = store.selectedPackages
        val always = appsRepository.loadAlwaysAllowedApps()
        val chosen = appsRepository.loadLaunchableApps().filter { it.packageName in selected }

        return (always + chosen)
            .distinctBy { it.launchType.name + ":" + it.packageName }
    }

    private fun buildShortcut(app: InstalledApp): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            isClickable = true
            setPadding(dp(2), dp(2), dp(2), dp(4))
            setOnClickListener { launchAllowed(app) }
        }

        val icon = ImageView(this).apply {
            app.icon?.let(::setImageBitmap)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        column.addView(
            icon,
            LinearLayout.LayoutParams(dp(58), dp(58))
        )

        val label = TextView(this).apply {
            text = app.label
            setTextColor(Color.rgb(29, 37, 31))
            textSize = 11f
            gravity = Gravity.CENTER
            maxLines = 2
            setPadding(0, dp(5), 0, 0)
        }
        column.addView(
            label,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        return column
    }

    private fun launchAllowed(app: InstalledApp) {
        pendingAllowedPackage = app.packageName
        trustedAllowedPackage = null
        trustedAllowedUntil = 0L
        suppressOverlayUntil = SystemClock.elapsedRealtime() + LAUNCH_GRACE_MS
        hideOverlay()

        if (!appsRepository.launch(app)) {
            pendingAllowedPackage = null
            trustedAllowedPackage = null
            trustedAllowedUntil = 0L
            suppressOverlayUntil = 0L
            enforceCurrentState()
        }
    }

    private fun registerExitTap() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTapAt > EXIT_TAP_WINDOW_MS) {
            tapCount = 0
        }
        lastTapAt = now
        tapCount += 1

        if (tapCount >= EXIT_TAP_COUNT) {
            tapCount = 0
            store.clearSession()
            hideOverlay()
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
    }

    private fun updateOverlayTimer(sessionEnd: Long = overlaySessionEnd) {
        if (sessionEnd <= 0L) return
        overlayTimer?.text =
            formatRemaining(max(0L, sessionEnd - System.currentTimeMillis()))
    }

    private fun hideOverlay() {
        val current = overlay ?: return
        runCatching { windowManager.removeView(current) }
        overlay = null
        overlayTimer = null
        overlaySessionEnd = 0L
    }

    private fun roundedBackground(
        fill: Int,
        stroke: Int,
        radiusDp: Float,
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(radiusDp).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(watchdog)
        hideOverlay()
        super.onDestroy()
    }

    companion object {
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val WATCHDOG_INTERVAL_MS = 200L
        private const val LAUNCH_GRACE_MS = 1_200L
        private const val ALLOWED_SETTLE_MS = 1_800L
        private const val RECENTS_RETURN_DELAY_MS = 120L
        private const val BACK_RETURN_DELAY_MS = 180L
        private val RECENTS_HINTS = listOf(
            "recents",
            "recent_apps",
            "recentapps",
            "overview",
            "quickstep",
            "taskview",
            "task_view"
        )
        private const val EXIT_TAP_COUNT = 7
        private const val EXIT_TAP_WINDOW_MS = 3_000L
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
