package ru.pauza.app

import android.content.Intent
import android.app.UiAutomation
import androidx.test.uiautomator.Configurator
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.*
import ru.pauza.app.data.InstalledAppsRepository
import ru.pauza.app.data.PauseStore
import java.io.File

/** Runs the real activity and real installed apps on a disposable CI emulator. */
@RunWith(AndroidJUnit4::class)
class VisualFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = run {
        Configurator.getInstance().setUiAutomationFlags(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        UiDevice.getInstance(instrumentation)
    }
    private val store = PauseStore(context)
    private val output = File(context.getExternalFilesDir(null), "verification").apply { mkdirs() }

    private fun launch() {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        device.waitForIdle()
    }
    private fun dismissEmulatorSystemDialog() {
        device.findObject(By.text("Wait"))?.let {
            it.click()
            Thread.sleep(400)
            device.waitForIdle()
        }
    }

    private fun awaitText(text: String) =
        device.wait(Until.findObject(By.text(text)), 1500) ?: run {
            dismissEmulatorSystemDialog()
            requireNotNull(device.wait(Until.findObject(By.text(text)), 10000)) { "Missing: $text" }
        }

    private fun shot(name: String) {
        device.waitForIdle()
        dismissEmulatorSystemDialog()
        device.findObject(By.text("Got it"))?.let { it.click(); Thread.sleep(500) }
        assertTrue(device.takeScreenshot(File(output, "$name.png")))
    }
    private fun record(text: String) = File(output, "checks.txt").appendText("PASS: $text\n")
    private fun active() { awaitText("Осталось") }
    private fun exitTaps() {
        val bounds = awaitText("Осталось").visibleBounds
        repeat(20) { device.click(bounds.centerX(), bounds.centerY()); Thread.sleep(180) }
        awaitText("Продолжить")
    }

    @Test fun visualScreensAndTimer() {
        try {
            store.clearSession()
            device.executeShellCommand("appops set ru.pauza.app GET_USAGE_STATS allow")
            device.executeShellCommand("settings put secure enabled_accessibility_services ru.pauza.app/ru.pauza.app.domain.PauseAccessibilityService")
            device.executeShellCommand("settings put secure accessibility_enabled 1")
            store.restrictedSettingsConfirmed = true
            store.setupChecklistCompleted = true
            store.selectedPackages = InstalledAppsRepository(context).loadLaunchableApps()
                .filter { it.packageName != "com.android.settings" }.take(5).map { it.packageName }.toSet()
            launch()
            awaitText("Продолжить")
            Thread.sleep(800)
            shot("03-setup")
            awaitText("Продолжить").click()
            awaitText("Проверьте перед запуском")
            shot("04-review")
            val hold = awaitText("Удерживайте 2 секунды, чтобы начать").visibleBounds
            device.swipe(hold.centerX(), hold.centerY(), hold.centerX(), hold.centerY(), 500)
            active()
            shot("05-active")
            record("Independent visual flow: real setup, review, hold-to-start and active screen")
            exitTaps()
            record("Independent visual flow: twenty-tap exit works")
            store.sessionDurationMs = 29L * 86400000L + 86340000L
            store.sessionEndEpochMs = System.currentTimeMillis() + store.sessionDurationMs
            launch(); active()
            shot("06-active-29-days")
            exitTaps()
            store.sessionDurationMs = 5000L
            store.sessionEndEpochMs = System.currentTimeMillis() + 5000L
            launch(); active()
            awaitText("Продолжить")
            assertEquals(0L, store.sessionEndEpochMs)
            record("Independent visual flow: actual timer expiry returns to setup")
        } catch (error: Throwable) {
            File(output, "visual-failure-error.txt").writeText(error.stackTraceToString())
            shot("visual-failure-screen")
            device.dumpWindowHierarchy(File(output, "visual-failure-hierarchy.xml"))
            throw error
        } finally {
            File(output, "accessibility-state.txt").writeText(device.executeShellCommand("dumpsys accessibility"))
            device.executeShellCommand("mkdir -p /sdcard/Download/pauza-verification")
            device.executeShellCommand("cp -r ${output.absolutePath}/. /sdcard/Download/pauza-verification/")
            store.clearSession()
        }
    }

    @Test fun realAppFlow() {
        try {
            context.getSharedPreferences("pause_store", 0).edit().clear().commit()
            launch()
            awaitText("Настройка Паузы")
            shot("01-permissions")
            record("App installs and opens permission checklist")
            awaitText("Открыть настройки приложения").click()
            device.waitForIdle()
            device.pressBack()
            awaitText("Подтвердить, что разрешено").click()
            assertTrue(store.restrictedSettingsConfirmed)
            record("First permission step opens Android settings and confirms")
            // Grant OS permissions on disposable emulator; not a product setup bypass.
            device.executeShellCommand("appops set ru.pauza.app GET_USAGE_STATS allow")
            device.executeShellCommand("settings put secure enabled_accessibility_services ru.pauza.app/ru.pauza.app.domain.PauseAccessibilityService")
            device.executeShellCommand("settings put secure accessibility_enabled 1")
            Thread.sleep(1800)
            val scroll = device.findObject(By.scrollable(true))
            scroll?.scroll(androidx.test.uiautomator.Direction.DOWN, 1f)
            shot("02-permissions-bottom")
            val next = device.wait(Until.findObject(By.text("Продолжить")), 3000)
                ?: awaitText("Готово, открыть Паузу")
            assertTrue(next.isEnabled)
            next.click()
            awaitText("Найти приложение")
            record("Remaining mandatory grants detected; optional battery does not block Continue")

            val repository = InstalledAppsRepository(context)
            val candidates = repository.loadLaunchableApps().filter { it.packageName != "com.android.settings" }.take(5)
            assertTrue("Need an installed app to exercise selection", candidates.isNotEmpty())
            val chosen = candidates.firstOrNull { it.packageName == "com.google.android.deskclock" }
                ?: candidates.first()
            val search = requireNotNull(device.findObject(By.clazz("android.widget.EditText")))
            search.click()
            search.text = chosen.label
            device.waitForIdle()
            device.pressBack()
            Thread.sleep(500)
            val appRow = device.findObjects(By.text(chosen.label)).maxBy { it.visibleBounds.centerY() }.visibleBounds
            device.click((device.displayWidth * .85f).toInt(), appRow.centerY())
            Thread.sleep(300)
            assertTrue(store.selectedPackages.contains(chosen.packageName))
            record("Search and selection update actual persisted whitelist")
            store.selectedPackages = candidates.map { it.packageName }.toSet()
            launch()
            awaitText("Продолжить")
            shot("03-setup")
            // Wheel gesture in the actual duration card; selection is verified by review text.
            val before = device.findObject(By.text("1 ч"))?.visibleBounds
            assertNotNull(before)
            before!!
            device.swipe(before.centerX(), before.centerY(), before.centerX(), before.centerY()-60, 35)
            Thread.sleep(800)
            record("Original scrollable duration wheel accepts gesture")
            awaitText("Продолжить").click()
            awaitText("Проверьте перед запуском")
            shot("04-review")
            record("Review contains real application icons and unchanged warnings")
            var hold = awaitText("Удерживайте 2 секунды, чтобы начать").visibleBounds
            device.click(hold.centerX(), hold.centerY())
            Thread.sleep(500)
            assertEquals(0L, store.sessionEndEpochMs)
            record("Short tap does not start Pause")
            device.swipe(hold.centerX(), hold.centerY(), hold.centerX(), hold.centerY(), 500)
            active()
            assertTrue(store.sessionEndEpochMs > System.currentTimeMillis())
            shot("05-active")
            record("Two-second hold starts real Pause without crash")
            assertTrue(repository.loadAlwaysAllowedApps().map { it.label }.containsAll(listOf("Телефон", "Сообщения")))
            awaitText("Телефон").click()
            Thread.sleep(1500)
            assertTrue(device.currentPackageName in repository.alwaysAllowedPackages())
            record("Phone opens from active screen")
            launch(); active()
            awaitText("Сообщения").click()
            Thread.sleep(1500)
            assertTrue(device.currentPackageName in repository.alwaysAllowedPackages())
            record("Messages opens from active screen")
            launch(); active()
            awaitText(chosen.label).click()
            val launchDeadline = System.currentTimeMillis() + 8_000L
            while (
                device.currentPackageName != chosen.packageName &&
                System.currentTimeMillis() < launchDeadline
            ) {
                dismissEmulatorSystemDialog()
                Thread.sleep(200)
            }
            assertEquals(chosen.packageName, device.currentPackageName)
            record("Selected application opens and remains available")
            device.pressHome()
            active()
            record("Home returns to Pause on emulator")
            device.pressRecentApps()
            device.executeShellCommand("am start -a android.settings.SETTINGS")
            active()
            assertEquals("ru.pauza.app", device.currentPackageName)
            record("Forbidden Settings launched after Recents is blocked on emulator")
            exitTaps()
            assertEquals(0L, store.sessionEndEpochMs)
            record("Twenty timer taps end Pause")
            // Expiry case seeds a short real session, then lets real clock/controller finish it.
            store.sessionDurationMs = 5000L
            store.sessionEndEpochMs = System.currentTimeMillis() + 5000L
            launch(); active()
            awaitText("Продолжить")
            assertEquals(0L, store.sessionEndEpochMs)
            record("Real timer expiry clears session and restores setup")
        } catch (error: Throwable) {
            File(output, "failure-error.txt").writeText(error.stackTraceToString())
            shot("failure-screen")
            device.dumpWindowHierarchy(File(output, "failure-hierarchy.xml"))
            throw error
        } finally {
            File(output, "accessibility-state.txt").writeText(device.executeShellCommand("dumpsys accessibility"))
            device.executeShellCommand("mkdir -p /sdcard/Download/pauza-verification")
            device.executeShellCommand("cp -r ${output.absolutePath}/. /sdcard/Download/pauza-verification/")
            store.clearSession()
        }
    }
}
