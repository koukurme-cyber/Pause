package ru.pauza.app.domain

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.os.Looper
import android.graphics.Rect
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import ru.pauza.app.data.PauseStore
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class ShortVideoRegressionTest {
    private val instagram = "com.instagram.android"
    private lateinit var service: PauseAccessibilityService
    private var currentRoot: AccessibilityNodeInfo? = null

    private fun node(
        id: String = "root", visible: Boolean = true, selected: Boolean = false,
        bounds: Rect = Rect(0, 0, 1080, 1920),
        children: List<AccessibilityNodeInfo> = emptyList(),
    ): AccessibilityNodeInfo = mock(AccessibilityNodeInfo::class.java).also { n ->
        `when`(n.packageName).thenReturn(instagram)
        `when`(n.viewIdResourceName).thenReturn("$instagram:id/$id")
        `when`(n.isVisibleToUser).thenReturn(visible)
        `when`(n.isSelected).thenReturn(selected)
        `when`(n.isEnabled).thenReturn(true)
        `when`(n.childCount).thenReturn(children.size)
        children.forEachIndexed { index, child -> `when`(n.getChild(index)).thenReturn(child) }
        doAnswer { call -> call.getArgument<Rect>(0).set(bounds); null }.`when`(n).getBoundsInScreen(any(Rect::class.java))
        `when`(n.findAccessibilityNodeInfosByViewId(anyString())).thenReturn(emptyList())
        `when`(n.findAccessibilityNodeInfosByText(anyString())).thenReturn(emptyList())
    }

    @Before fun setUp() {
        service = spy(Robolectric.buildService(PauseAccessibilityService::class.java).create().get())
        // Mockito copies the service, but its preconstructed inner Runnable still
        // references the original. Bind that callback to the instrumented instance.
        val watchdog = field("watchdog")!!
        watchdog.javaClass.declaredFields.first { it.type == PauseAccessibilityService::class.java }
            .apply { isAccessible = true }.set(watchdog, service)
        val window = mock(AccessibilityWindowInfo::class.java)
        `when`(window.type).thenReturn(AccessibilityWindowInfo.TYPE_APPLICATION)
        `when`(window.isFocused).thenReturn(true)
        `when`(window.root).thenAnswer { currentRoot }
        doReturn(listOf(window)).`when`(service).windows
        doAnswer { currentRoot }.`when`(service).rootInActiveWindow
        doReturn(true).`when`(service).performGlobalAction(anyInt())
        shadowOf(service.getSystemService(Context.POWER_SERVICE) as PowerManager).setIsInteractive(true)
        PauseStore(service).apply {
            sessionEndEpochMs = System.currentTimeMillis() + 600_000
            selectedPackages = setOf(instagram)
            blockShortVideos = true
        }
        ShadowSystemClock.advanceBy(Duration.ofSeconds(10))
    }

    private fun enforce(eventPackage: String = instagram, type: Int = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
        val event = AccessibilityEvent.obtain(type)
        event.packageName = eventPackage
        try {
            PauseAccessibilityService::class.java
                .getDeclaredMethod("enforceCurrentWindow", AccessibilityEvent::class.java)
                .apply { isAccessible = true }.invoke(service, event)
        } finally { event.recycle() }
    }

    private fun field(name: String): Any? = PauseAccessibilityService::class.java
        .getDeclaredField(name).apply { isAccessible = true }.get(service)

    private fun advance(ms: Long) = ShadowSystemClock.advanceBy(Duration.ofMillis(ms))
    private fun player() = node("clips_viewer_view_pager")
    private fun safeTab(selected: Boolean = true) = node("search_tab", selected = selected)

    @Test fun visibleSearchTabIsNotProofOfExit() {
        assertFalse(ShortVideoDetector.isConfirmedSafeSurface(instagram, node(children = listOf(safeTab(false)))))
        assertFalse(ShortVideoDetector.isConfirmedSafeSurface(instagram, node(children = listOf(safeTab(), player()))))
        assertTrue(ShortVideoDetector.isConfirmedSafeSurface(instagram, node(children = listOf(safeTab()))))
        assertFalse(ShortVideoDetector.isConfirmedSafeSurface(instagram, null))
    }

    @Test fun cachedAndSmallPlayersDoNotBlockSearch() {
        for (candidate in listOf(
            node("clips_viewer_view_pager", visible = false),
            node("clips_viewer_view_pager", bounds = Rect(1080, 0, 2160, 1920)),
            node("clips_viewer_view_pager", bounds = Rect(0, 0, 300, 300)),
        )) {
            val root = node(children = listOf(safeTab(), candidate))
            assertFalse(ShortVideoDetector.isShortVideoScreen(instagram, root, null))
            assertTrue(ShortVideoDetector.isConfirmedSafeSurface(instagram, root))
        }
        assertTrue(ShortVideoDetector.isShortVideoScreen(instagram, node(children = listOf(player())), null))
    }

    @Test fun standaloneSearchViewerGetsOnlyOneBack() {
        val root = node(children = listOf(player()))
        for (attempt in 0..4) ShortVideoSafeNavigator.escapeDetectedPlayer(service, instagram, root, attempt)
        verify(service, times(1)).performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        ShortVideoSafeNavigator.escapeDetectedPlayer(service, instagram, node(children = listOf(safeTab())), 1)
        verify(service, times(1)).performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    @Test fun contentDescriptionOnlySearchButtonCanBeUsed() {
        val tab = safeTab()
        `when`(tab.contentDescription).thenReturn("Поиск")
        `when`(tab.isClickable).thenReturn(true)
        `when`(tab.performAction(AccessibilityNodeInfo.ACTION_CLICK)).thenReturn(true)
        assertTrue(ShortVideoSafeNavigator.escapeDetectedPlayer(service, instagram, node(children = listOf(tab, player()))))
        verify(tab).performAction(AccessibilityNodeInfo.ACTION_CLICK)
        verify(service, never()).performGlobalAction(anyInt())
    }

    @Test fun rapidEventsDoNotFakeExitAndRetriesRemainBounded() {
        currentRoot = node(children = listOf(safeTab(), player()))
        enforce()
        repeat(100) { advance(50); enforce() }
        assertEquals(true, field("shortVideoNavigating"))
        assertEquals(0L, field("lastShortNoticeShownAt"))
        assertEquals(4, field("shortVideoRetryAttempts"))
        verify(service, times(1)).performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    @Test fun missingRootBreaksConfirmationAndNoticeAppearsOnceAfterExit() {
        currentRoot = node(children = listOf(player()))
        enforce()
        currentRoot = node(children = listOf(safeTab()))
        advance(300); enforce()
        currentRoot = null
        advance(300); enforce()
        assertEquals(true, field("shortVideoNavigating"))
        assertEquals(0L, field("shortVideoExitCandidateAt"))
        currentRoot = node(children = listOf(safeTab()))
        advance(300); enforce()
        advance(300); enforce()
        assertEquals(true, field("shortVideoNavigating"))
        advance(300); enforce()
        assertEquals(false, field("shortVideoNavigating"))
        val noticeTime = field("lastShortNoticeShownAt") as Long
        assertTrue(noticeTime > 0)
        repeat(20) { advance(300); enforce() }
        assertEquals(noticeTime, field("lastShortNoticeShownAt"))
    }

    @Test fun immediateReentryIsBlockedWithoutRepeatingNotice() {
        currentRoot = node(children = listOf(player()))
        enforce()
        currentRoot = node(children = listOf(safeTab()))
        advance(300); enforce()
        advance(500); enforce()
        assertEquals(false, field("shortVideoNavigating"))
        val noticeTime = field("lastShortNoticeShownAt")
        currentRoot = node(children = listOf(player()))
        advance(300); enforce()
        assertEquals(true, field("shortVideoNavigating"))
        assertEquals(noticeTime, field("lastShortNoticeShownAt"))
    }
    @Test fun noticeKeepsSameViewAndDeadlineDuringDetectionBounce() {
        currentRoot = node(children = listOf(player()))
        enforce()
        currentRoot = node(children = listOf(safeTab()))
        advance(300); enforce()
        advance(500); enforce()
        val overlay = field("shortNoticeOverlay")
        val deadline = field("shortNoticeHideAt")
        assertNotNull(overlay)
        currentRoot = node(children = listOf(player()))
        advance(300); enforce()
        assertSame(overlay, field("shortNoticeOverlay"))
        assertEquals(deadline, field("shortNoticeHideAt"))
        currentRoot = node(children = listOf(safeTab()))
        advance(300); enforce()
        advance(500); enforce()
        assertSame(overlay, field("shortNoticeOverlay"))
        advance(600); enforce()
        assertNull(field("shortNoticeOverlay"))
    }

    @Test fun unstableScreenCannotRearmNoticeAfterCooldown() {
        currentRoot = node(children = listOf(player()))
        enforce()
        currentRoot = node(children = listOf(safeTab()))
        advance(300); enforce(); advance(500); enforce()
        val shown = field("lastShortNoticeShownAt")
        repeat(8) {
            currentRoot = node(children = listOf(player()))
            advance(300); enforce()
            currentRoot = node(children = listOf(safeTab()))
            advance(300); enforce(); advance(500); enforce()
        }
        assertEquals(shown, field("lastShortNoticeShownAt"))
    }

    private fun homeRoot(): AccessibilityNodeInfo {
        val homePackage = "test.launcher"
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = homePackage
                name = "HomeActivity"
            }
        }
        shadowOf(service.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), info,
        )
        return node().also { `when`(it.packageName).thenReturn(homePackage) }
    }

    @Test fun homeOverridesAllowlistAndStaleRutubeWindowEvent() {
        currentRoot = homeRoot()
        PauseStore(service).selectedPackages = setOf("test.launcher", "rtb.mobile.android")
        enforce("rtb.mobile.android", AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        verify(service).startActivity(any(Intent::class.java))
        verify(service, never()).performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    }

    @Test fun pipVideoDoesNotMaskActiveHomeOnWatchdogTick() {
        val home = homeRoot()
        val video = node().also { `when`(it.packageName).thenReturn("rtb.mobile.android") }
        currentRoot = video
        val pip = mock(AccessibilityWindowInfo::class.java)
        `when`(pip.type).thenReturn(AccessibilityWindowInfo.TYPE_APPLICATION)
        `when`(pip.isFocused).thenReturn(true)
        `when`(pip.isInPictureInPictureMode).thenReturn(true)
        `when`(pip.root).thenReturn(video)
        val desktop = mock(AccessibilityWindowInfo::class.java)
        `when`(desktop.type).thenReturn(AccessibilityWindowInfo.TYPE_APPLICATION)
        `when`(desktop.isActive).thenReturn(true)
        `when`(desktop.root).thenReturn(home)
        doReturn(listOf(pip, desktop)).`when`(service).windows
        PauseStore(service).selectedPackages = setOf("rtb.mobile.android", "test.launcher")
        (field("watchdog") as Runnable).run()
        verify(service).startActivity(any(Intent::class.java))
    }

    @Test fun watchdogSurvivesTransientWindowException() {
        currentRoot = homeRoot()
        val window = mock(AccessibilityWindowInfo::class.java)
        `when`(window.type).thenReturn(AccessibilityWindowInfo.TYPE_APPLICATION)
        `when`(window.isFocused).thenReturn(true)
        `when`(window.root).thenAnswer { currentRoot }
        doThrow(IllegalStateException("window disappeared")).doReturn(listOf(window)).`when`(service).windows
        (field("watchdog") as Runnable).run()
        verify(service, never()).startActivity(any(Intent::class.java))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        verify(service).startActivity(any(Intent::class.java))
    }

}
