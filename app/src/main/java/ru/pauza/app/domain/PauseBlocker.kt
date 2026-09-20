package ru.pauza.app.domain

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
    override fun start(allowedPackages: Set<String>, untilEpochMs: Long) = Unit
    override fun stop() = Unit

    companion object {
        fun isEnabled(context: Context): Boolean {
            val manager = context.getSystemService(AccessibilityManager::class.java)
            val component = ComponentName(context, PauseAccessibilityService::class.java)
            return manager.getEnabledAccessibilityServiceList(AccessibilityManager.FEEDBACK_ALL_MASK)
                .any {
                    val info = it.resolveInfo.serviceInfo
                    info.packageName == component.packageName &&
                        info.name == component.className
                }
        }

        fun openSettings(context: Context) {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
