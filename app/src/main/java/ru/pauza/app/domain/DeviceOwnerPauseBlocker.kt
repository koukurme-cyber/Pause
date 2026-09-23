package ru.pauza.app.domain

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import ru.pauza.app.admin.PauseDeviceAdminReceiver

class DeviceOwnerPauseBlocker(private val context: Context) : PauseBlocker {
    private val dpm by lazy { context.getSystemService(DevicePolicyManager::class.java) }
    private val admin by lazy { PauseDeviceAdminReceiver.component(context) }

    override fun start(allowedPackages: Set<String>, untilEpochMs: Long) {
        if (!isDeviceOwner(context)) return

        val packages = (allowedPackages + context.packageName)
            .filter { it.isNotBlank() }
            .distinct()
            .toTypedArray()

        dpm.setLockTaskPackages(admin, packages)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dpm.setLockTaskFeatures(
                admin,
                DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or
                    DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD
            )
        }

        val activity = context as? Activity ?: return
        if (dpm.isLockTaskPermitted(context.packageName)) {
            activity.startLockTask()
        }
    }

    override fun stop() {
        val activity = context as? Activity
        runCatching { activity?.stopLockTask() }
        releasePolicy(context)
    }

    companion object {
        fun isDeviceOwner(context: Context): Boolean {
            val dpm = context.getSystemService(DevicePolicyManager::class.java)
            return dpm.isDeviceOwnerApp(context.packageName)
        }

        fun releasePolicy(context: Context) {
            val dpm = context.getSystemService(DevicePolicyManager::class.java)
            if (!dpm.isDeviceOwnerApp(context.packageName)) return
            runCatching {
                dpm.setLockTaskPackages(
                    PauseDeviceAdminReceiver.component(context),
                    emptyArray()
                )
            }
        }
    }
}
