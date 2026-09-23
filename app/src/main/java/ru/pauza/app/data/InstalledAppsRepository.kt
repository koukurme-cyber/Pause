package ru.pauza.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import ru.pauza.app.model.AppLaunchType
import ru.pauza.app.model.InstalledApp

class InstalledAppsRepository(private val context: Context) {
    private val pm: PackageManager = context.packageManager

    fun alwaysAllowedPackages(): Set<String> = buildSet {
        add(context.packageName)
        resolvePackage(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")))?.let(::add)
        resolvePackage(Intent(Intent.ACTION_SENDTO, Uri.parse("sms:112")))?.let(::add)
    }

    fun loadAlwaysAllowedApps(): List<InstalledApp> = listOfNotNull(
        resolveShortcut(
            intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:")),
            label = "Телефон",
            launchType = AppLaunchType.PHONE,
        ),
        resolveShortcut(
            intent = Intent(Intent.ACTION_SENDTO, Uri.parse("sms:")),
            label = "Сообщения",
            launchType = AppLaunchType.MESSAGES,
        ),
    )

    fun loadLaunchableApps(): List<InstalledApp> {
        val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val excluded = alwaysAllowedPackages()

        return pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_ALL)
            .asSequence()
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg in excluded) return@mapNotNull null
                val label = info.loadLabel(pm)?.toString()?.trim().orEmpty()
                if (label.isBlank()) return@mapNotNull null
                InstalledApp(
                    label = label,
                    packageName = pkg,
                    icon = runCatching { info.loadIcon(pm).toBitmapSafe(96, 96) }.getOrNull(),
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    fun launch(app: InstalledApp): Boolean {
        val intent = when (app.launchType) {
            AppLaunchType.PHONE -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:"))
            AppLaunchType.MESSAGES -> Intent(Intent.ACTION_SENDTO, Uri.parse("sms:"))
            AppLaunchType.PACKAGE -> pm.getLaunchIntentForPackage(app.packageName)
        } ?: return false

        return runCatching {
            // Keep allowed apps inside Pause's pinned task. Launcher intents
            // normally carry NEW_TASK, which would escape the pinned task.
            intent.removeFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun resolveShortcut(
        intent: Intent,
        label: String,
        launchType: AppLaunchType,
    ): InstalledApp? {
        val info = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) ?: return null
        val pkg = info.activityInfo?.packageName ?: return null
        return InstalledApp(
            label = label,
            packageName = pkg,
            icon = runCatching { info.loadIcon(pm).toBitmapSafe(96, 96) }.getOrNull(),
            launchType = launchType,
        )
    }

    private fun resolvePackage(intent: Intent): String? =
        pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
}

private fun Drawable.toBitmapSafe(width: Int, height: Int): Bitmap {
    if (this is BitmapDrawable && bitmap != null) return bitmap
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bitmap
}
