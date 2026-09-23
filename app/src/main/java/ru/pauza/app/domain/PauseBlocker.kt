package ru.pauza.app.domain

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

interface PauseBlocker {
    fun start(allowedPackages: Set<String>, untilEpochMs: Long)
    fun stop()
}

class AccessibilityPauseBlocker(private val context: Context) : PauseBlocker {
    override fun start(allowedPackages: Set<String>, untilEpochMs: Long) {
        (context as? Activity)?.let { activity ->
            runCatching { activity.startLockTask() }
        }
    }

    override fun stop() {
        (context as? Activity)?.let { activity ->
            runCatching { activity.stopLockTask() }
        }
    }

    companion object {
        fun isEnabled(context: Context): Boolean {
            val target = ComponentName(context, PauseAccessibilityService::class.java)

            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()

            val enabledInSecureSettings = enabledServices
                .split(':')
                .asSequence()
                .mapNotNull { ComponentName.unflattenFromString(it) }
                .any {
                    it.packageName == target.packageName &&
                        sameServiceClass(it.className, target.className, target.packageName)
                }

            if (enabledInSecureSettings) return true

            val manager = context.getSystemService(AccessibilityManager::class.java)
            return manager
                .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { service ->
                    val info = service.resolveInfo.serviceInfo
                    info.packageName == target.packageName &&
                        sameServiceClass(info.name, target.className, target.packageName)
                }
        }

        private fun sameServiceClass(
            actual: String?,
            expected: String,
            packageName: String,
        ): Boolean {
            if (actual.isNullOrBlank()) return false
            val normalizedActual = when {
                actual.startsWith(".") -> packageName + actual
                '.' !in actual -> "$packageName.$actual"
                else -> actual
            }
            return normalizedActual == expected
        }

        fun openSettings(context: Context) {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
