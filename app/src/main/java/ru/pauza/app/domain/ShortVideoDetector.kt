package ru.pauza.app.domain

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import java.util.Locale

object ShortVideoDetector {
    private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
    private const val INSTAGRAM_PACKAGE = "com.instagram.android"

    private val RUTUBE_PACKAGES = setOf(
        "rtb.mobile.android",
        "ru.rutube.app",
    )

    private val youtubePlayerIds = setOf(
        "reel_watch_fragment_root",
        "reel_progress_bar",
        "shorts_video_header",
        "shorts_container",
        "shorts_vertical_feed_container",
        "reel_player_page_container",
    )

    private val instagramStrongPlayerIds = setOf(
        "clips_viewer_view_pager",
        "reel_pager",
        "clips_swipe_container",
        "reels_viewer",
        "reel_feed_recycler_view",
        "ig_reels_player_container",
        "clips_video_container",
    )

    private val instagramAuthClassHints = setOf(
        "login",
        "signup",
        "sign_up",
        "registration",
        "onboarding",
        "accountcreation",
        "account_creation",
    )

    private val rutubeStrongIdHints = setOf(
        "shorts_player",
        "shorts_feed",
        "shorts_pager",
        "shorts_viewer",
        "shorts_container",
        "short_video",
        "shortvideo",
        "vertical_video",
        "vertical_feed",
        "vertical_player",
    )

    fun isSupportedPackage(packageName: String): Boolean =
        packageName == YOUTUBE_PACKAGE ||
            packageName == INSTAGRAM_PACKAGE ||
            packageName in RUTUBE_PACKAGES

    fun isShortEntryAction(
        packageName: String,
        event: AccessibilityEvent?,
    ): Boolean {
        event ?: return false
        if (
            event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_SELECTED
        ) return false

        val labels = when {
            packageName == YOUTUBE_PACKAGE -> setOf("shorts")
            packageName == INSTAGRAM_PACKAGE -> setOf("reels", "рилс")
            packageName in RUTUBE_PACKAGES -> setOf("shorts")
            else -> return false
        }

        val source = event.source
        val sourceId = source?.viewIdResourceName?.lowercase(Locale.ROOT).orEmpty()
        val sourceText = source?.text?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val sourceDescription =
            source?.contentDescription?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val eventTexts = event.text
            .mapNotNull { it?.toString()?.trim()?.lowercase(Locale.ROOT) }

        val exactLabelMatch =
            sourceText in labels ||
                sourceDescription in labels ||
                eventTexts.any { it in labels }

        if (!exactLabelMatch) return false

        val looksLikeNavigation =
            sourceId.contains("tab") ||
                sourceId.contains("pivot") ||
                sourceId.contains("nav") ||
                sourceId.contains("menu") ||
                sourceId.contains("short") ||
                sourceId.contains("reel")

        return looksLikeNavigation || source?.isClickable == true
    }

    fun isShortVideoScreen(
        packageName: String,
        root: AccessibilityNodeInfo?,
        event: AccessibilityEvent?,
    ): Boolean {
        if (!isSupportedPackage(packageName) || root == null) return false

        val snapshot = collectSnapshot(root)
        val className = event?.className?.toString().orEmpty()

        return when {
            packageName == YOUTUBE_PACKAGE ->
                isYouTubeShorts(snapshot.resourceIds, className)

            packageName == INSTAGRAM_PACKAGE ->
                isInstagramReels(snapshot.resourceIds, className)

            packageName in RUTUBE_PACKAGES ->
                isRutubeShorts(snapshot.resourceIds, className)

            else -> false
        }
    }

    private fun isYouTubeShorts(
        resourceIds: Set<String>,
        className: String,
    ): Boolean {
        if (
            className.contains("Shorts", ignoreCase = true) ||
            className.contains("ReelWatch", ignoreCase = true)
        ) {
            return true
        }

        return resourceIds.any { id ->
            youtubePlayerIds.any { hint -> id.contains(hint) }
        }
    }

    private fun isInstagramReels(
        resourceIds: Set<String>,
        className: String,
    ): Boolean {
        val normalizedClass = className.lowercase(Locale.ROOT)

        if (instagramAuthClassHints.any { normalizedClass.contains(it) }) {
            return false
        }

        val hasStrongPlayerId = resourceIds.any { id ->
            instagramStrongPlayerIds.any { hint -> id.contains(hint) }
        }
        if (hasStrongPlayerId) return true

        val classLooksLikeReels =
            normalizedClass.contains("reelviewer") ||
                normalizedClass.contains("clipsviewer") ||
                normalizedClass.contains("verticalstream")

        if (!classLooksLikeReels) return false

        return resourceIds.any { id ->
            val reelish = id.contains("reel") || id.contains("clip")
            val playerish =
                id.contains("viewer") ||
                    id.contains("pager") ||
                    id.contains("player") ||
                    id.contains("swipe") ||
                    id.contains("video")
            reelish && playerish
        }
    }

    private fun isRutubeShorts(
        resourceIds: Set<String>,
        className: String,
    ): Boolean {
        if (
            className.contains("Shorts", ignoreCase = true) ||
            className.contains("ShortVideo", ignoreCase = true) ||
            className.contains("VerticalVideo", ignoreCase = true)
        ) {
            return true
        }

        if (resourceIds.any { id ->
                rutubeStrongIdHints.any { hint -> id.contains(hint) }
            }) {
            return true
        }

        // Conservative fallback for RUTUBE versions whose ids change:
        // require a "short/shorts" id tied to an actual player/feed container.
        return resourceIds.any { id ->
            val mentionsShort = id.contains("short")
            val looksLikePlayer =
                id.contains("player") ||
                    id.contains("viewer") ||
                    id.contains("pager") ||
                    id.contains("feed") ||
                    id.contains("video") ||
                    id.contains("container")
            val looksLikeNavigation =
                id.contains("tab") ||
                    id.contains("nav") ||
                    id.contains("menu") ||
                    id.contains("button") ||
                    id.contains("icon")
            mentionsShort && looksLikePlayer && !looksLikeNavigation
        }
    }

    private fun collectSnapshot(root: AccessibilityNodeInfo): Snapshot {
        val resourceIds = LinkedHashSet<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited += 1

            node.viewIdResourceName
                ?.lowercase(Locale.ROOT)
                ?.let(resourceIds::add)

            val childCount = node.childCount.coerceAtMost(MAX_CHILDREN_PER_NODE)
            for (index in 0 until childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
        }

        return Snapshot(resourceIds = resourceIds)
    }

    private data class Snapshot(
        val resourceIds: Set<String>,
    )

    private const val MAX_NODES = 200
    private const val MAX_CHILDREN_PER_NODE = 50
}
