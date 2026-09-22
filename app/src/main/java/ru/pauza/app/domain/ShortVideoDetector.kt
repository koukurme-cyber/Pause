package ru.pauza.app.domain

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Minimal short-video detector.
 * Deliberately does not navigate inside apps.
 * It only identifies probable short-video surfaces so the blocker layer can act.
 */
object ShortVideoDetector {
    private val shortVideoWords = setOf(
        "reels",
        "shorts",
        "short video",
        "clips",
        "клипы",
        "короткие видео"
    )

    fun isShortVideo(root: AccessibilityNodeInfo?, packageName: String?): Boolean {
        if (root == null || packageName == null) return false

        val pkgAllowed = packageName.contains("youtube") ||
            packageName.contains("instagram") ||
            packageName.contains("tiktok") ||
            packageName.contains("rutube")

        if (!pkgAllowed) return false

        return containsText(root)
    }

    private fun containsText(node: AccessibilityNodeInfo): Boolean {
        val text = buildString {
            node.text?.let { append(it) }
            node.contentDescription?.let { append(' ').append(it) }
        }.lowercase()

        if (shortVideoWords.any { text.contains(it) }) return true

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (containsText(child)) return true
        }
        return false
    }
}
