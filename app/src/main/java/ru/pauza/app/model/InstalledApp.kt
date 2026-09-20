package ru.pauza.app.model

import android.graphics.Bitmap

enum class AppLaunchType {
    PACKAGE,
    PHONE,
    MESSAGES,
}

data class InstalledApp(
    val label: String,
    val packageName: String,
    val icon: Bitmap?,
    val launchType: AppLaunchType = AppLaunchType.PACKAGE,
)
