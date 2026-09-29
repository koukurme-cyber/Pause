package ru.pauza.app.domain

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Detects Instagram Reels conservatively.
 *
 * Important: the permanent bottom navigation item "Reels" is not enough.
 * We only accept a Reels marker when it belongs to the main screen area,
 * is selected/focused, or comes from a resource id that explicitly mentions reels.
 */
object ShortVideoDetector {
    private const val INSTAGRAM_PACKAGE = "com.instagram.android"

    fun isInstagramReels(
        root: AccessibilityNodeInfo?,
        packageName: String?,
    ): Boolean {
        if (root == null || packageName != INSTAGRAM_PACKAGE) return false

        val rootBounds = Rect().also(root::getBoundsInScreen)
        val screenBottom = rootBounds.bottom.takeIf { it > 0 } ?: Int.MAX_VALUE

        return containsStrongReelsMarker(root, screenBottom)
    }

    private fun containsStrongReelsMarker(
        node: AccessibilityNodeInfo,
        screenBottom: Int,
    ): Boolean {
        val text = buildString {
            node.text?.let { append(it) }
            node.contentDescription?.let {
                if (isNotEmpty()) append(' ')
                append(it)
            }
        }.lowercase()

        val viewId = node.viewIdResourceName?.lowercase().orEmpty()
        val mentionsReels =
            text.contains("reels") ||
                text.contains("рилс") ||
                text.contains("рилсы") ||
                viewId.contains("reel")

        if (mentionsReels) {
            val bounds = Rect().also(node::getBoundsInScreen)
            val bottomNavigationThreshold =
                if (screenBottom == Int.MAX_VALUE) Int.MAX_VALUE
                else (screenBottom * 82L / 100L).toInt()

            val outsideBottomNavigation =
                bounds.bottom <= 0 ||
                    bounds.bottom < bottomNavigationThreshold

            if (
                viewId.contains("reel") ||
                node.isSelected ||
                node.isFocused ||
                node.isAccessibilityFocused ||
                outsideBottomNavigation
            ) {
                return true
            }
        }

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            if (containsStrongReelsMarker(child, screenBottom)) return true
        }

        return false
    }
}
