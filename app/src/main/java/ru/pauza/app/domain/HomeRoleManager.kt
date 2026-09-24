package ru.pauza.app.domain

import android.app.Activity
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

object HomeRoleManager {
    fun isHeld(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val manager = context.getSystemService(RoleManager::class.java)
            manager.isRoleAvailable(RoleManager.ROLE_HOME) &&
                manager.isRoleHeld(RoleManager.ROLE_HOME)
        } else {
            resolveDefaultHome(context)?.packageName == context.packageName
        }
    }

    fun requestIntent(context: Context): Intent {
        rememberOriginalHome(context)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(RoleManager::class.java)
                .createRequestRoleIntent(RoleManager.ROLE_HOME)
        } else {
            Intent(Settings.ACTION_HOME_SETTINGS)
        }
    }

    fun rememberOriginalHome(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_ORIGINAL_HOME)) return

        val original = resolveDefaultHome(context)
            ?.takeIf { it.packageName != context.packageName }
            ?: queryHomeCandidates(context)
                .firstOrNull { it.packageName != context.packageName }

        if (original != null) {
            prefs.edit().putString(KEY_ORIGINAL_HOME, original.flattenToString()).apply()
        }
    }

    fun launchOriginalHome(context: Context): Boolean {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ORIGINAL_HOME, null)
            ?.let(ComponentName::unflattenFromString)
            ?.takeIf { it.packageName != context.packageName }

        val candidates = buildList {
            if (stored != null) add(stored)
            addAll(queryHomeCandidates(context).filter { it.packageName != context.packageName })
        }.distinct()

        for (component in candidates) {
            val launched = runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        this.component = component
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                        )
                    }
                )
            }.isSuccess

            if (launched) return true
        }

        return false
    }

    private fun resolveDefaultHome(context: Context): ComponentName? {
        val info = context.packageManager.resolveActivity(
            homeIntent(),
            PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo ?: return null

        return ComponentName(info.packageName, info.name)
    }

    private fun queryHomeCandidates(context: Context): List<ComponentName> =
        context.packageManager
            .queryIntentActivities(homeIntent(), PackageManager.MATCH_ALL)
            .mapNotNull { it.activityInfo }
            .map { ComponentName(it.packageName, it.name) }
            .distinct()

    private fun homeIntent() =
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    private const val PREFS = "pause_home_role"
    private const val KEY_ORIGINAL_HOME = "original_home_component"
}
