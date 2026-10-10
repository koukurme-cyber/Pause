package ru.pauza.app.domain

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Process

/**
 * Android 13+ may gate accessibility behind the restricted-settings app-op.
 *
 * Some OEMs forbid third-party apps from reading this app-op. Treat that
 * case as UNKNOWN, not as proof that the permission was granted.
 */
enum class RestrictedSettingsStatus {
    ALLOWED,
    DENIED,
    UNKNOWN,
}

object RestrictedSettingsMonitor {
    private const val RESTRICTED_SETTINGS_OP = "android:access_restricted_settings"

    fun check(context: Context): RestrictedSettingsStatus {
        if (Build.VERSION.SDK_INT < 33) return RestrictedSettingsStatus.ALLOWED

        return try {
            val manager = context.getSystemService(AppOpsManager::class.java)
                ?: return RestrictedSettingsStatus.UNKNOWN
            when (manager.unsafeCheckOpNoThrow(
                RESTRICTED_SETTINGS_OP,
                Process.myUid(),
                context.packageName,
            )) {
                AppOpsManager.MODE_ALLOWED -> RestrictedSettingsStatus.ALLOWED
                AppOpsManager.MODE_IGNORED,
                AppOpsManager.MODE_ERRORED -> RestrictedSettingsStatus.DENIED
                else -> RestrictedSettingsStatus.UNKNOWN
            }
        } catch (_: SecurityException) {
            RestrictedSettingsStatus.UNKNOWN
        } catch (_: IllegalArgumentException) {
            RestrictedSettingsStatus.UNKNOWN
        }
    }
}
