package ru.pauza.app.domain

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import ru.pauza.app.MainActivity
import ru.pauza.app.data.InstalledAppsRepository
import ru.pauza.app.data.PauseStore

class PauseAccessibilityService : AccessibilityService() {
    private val store by lazy { PauseStore(this) }
    private val appsRepository by lazy { InstalledAppsRepository(this) }
    private var lastReturnAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val end = store.sessionEndEpochMs
        if (end <= 0L || System.currentTimeMillis() >= end) return

        val foregroundPackage = event?.packageName?.toString() ?: return
        val allowed = store.selectedPackages +
            appsRepository.alwaysAllowedPackages() +
            packageName +
            SYSTEM_UI_PACKAGE

        if (foregroundPackage in allowed) return

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

    override fun onInterrupt() = Unit

    companion object {
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val RETURN_DEBOUNCE_MS = 350L
    }
}
