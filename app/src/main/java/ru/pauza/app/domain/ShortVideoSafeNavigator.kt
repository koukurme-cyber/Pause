package ru.pauza.app.domain

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

object ShortVideoSafeNavigator {
    private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    private const val INSTAGRAM_PACKAGE = "com.instagram.android"

    private val RUTUBE_PACKAGES = setOf(
        "rtb.mobile.android",
        "ru.rutube.app",
    )

    private val youtubeHomeIds = listOf(
        "com.google.android.youtube:id/home_button",
        "com.google.android.youtube:id/pivot_bar_item_home",
        "com.google.android.youtube:id/pivot_bar_item_index_0",
    )

    private val instagramHomeIds = listOf(
        "com.instagram.android:id/feed_tab",
        "com.instagram.android:id/tab_feed",
        "com.instagram.android:id/home_tab",
        "com.instagram.android:id/navigation_home",
        "com.instagram.android:id/tab_bar_home_button",
        "com.instagram.android:id/tab_icon_0",
        "com.instagram.android:id/tab_home",
        "com.instagram.android:id/ig_nav_tab_home",
    )

    private val homeLabels = listOf(
        "Главная",
        "Home",
    )

    fun navigateToSafeSurface(
        service: AccessibilityService,
        packageName: String,
        root: AccessibilityNodeInfo?,
    ): Boolean {
        if (root != null) {
            val knownIds = when {
                packageName == YOUTUBE_PACKAGE -> youtubeHomeIds
                packageName == INSTAGRAM_PACKAGE -> instagramHomeIds
                packageName in RUTUBE_PACKAGES -> emptyList()
                else -> emptyList()
            }

            if (clickFirstKnownId(root, knownIds)) return true
            if (clickFirstHomeLabel(root)) return true
        }

        return when {
            packageName == INSTAGRAM_PACKAGE ->
                reopenPackageRoot(service, packageName)

            packageName == YOUTUBE_PACKAGE ||
                packageName in RUTUBE_PACKAGES ->
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

            else -> false
        }
    }

    private fun reopenPackageRoot(
        service: AccessibilityService,
        packageName: String,
    ): Boolean {
        val launchIntent = service.packageManager.getLaunchIntentForPackage(packageName)
            ?: return false

        return runCatching {
            service.startActivity(
                launchIntent.apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                }
            )
            true
        }.getOrDefault(false)
    }

    private fun clickFirstKnownId(
        root: AccessibilityNodeInfo,
        ids: List<String>,
    ): Boolean {
        for (id in ids) {
            val nodes = runCatching {
                root.findAccessibilityNodeInfosByViewId(id)
            }.getOrDefault(emptyList())

            for (node in nodes) {
                if (clickNodeOrAncestor(node)) return true
            }
        }
        return false
    }

    private fun clickFirstHomeLabel(root: AccessibilityNodeInfo): Boolean {
        for (label in homeLabels) {
            val direct = runCatching {
                root.findAccessibilityNodeInfosByText(label)
            }.getOrDefault(emptyList())

            for (node in direct) {
                val textMatches = node.text?.toString()?.equals(label, ignoreCase = true) == true
                val descriptionMatches =
                    node.contentDescription?.toString()?.equals(label, ignoreCase = true) == true
                if ((textMatches || descriptionMatches) && clickNodeOrAncestor(node)) {
                    return true
                }
            }

            val described = findByExactDescription(root, label)
            if (described != null && clickNodeOrAncestor(described)) {
                return true
            }
        }
        return false
    }

    private fun clickNodeOrAncestor(start: AccessibilityNodeInfo): Boolean {
        var node: AccessibilityNodeInfo? = start
        var depth = 0
        while (node != null && depth <= 5) {
            if (node.isClickable && node.isEnabled) {
                if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
            }
            node = node.parent
            depth += 1
        }
        return false
    }

    private fun findByExactDescription(
        root: AccessibilityNodeInfo,
        label: String,
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited += 1

            if (node.contentDescription?.toString()?.equals(label, ignoreCase = true) == true) {
                return node
            }

            val childCount = node.childCount.coerceAtMost(MAX_CHILDREN_PER_NODE)
            for (index in 0 until childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
        }

        return null
    }

    private const val MAX_NODES = 300
    private const val MAX_CHILDREN_PER_NODE = 50
}
