package ru.pauza.app.domain

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import ru.pauza.app.data.PauseStore
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SavedSetScheduleEngineTest {
    private lateinit var context: Context
    private lateinit var store: PauseStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("pause_store", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        store = PauseStore(context)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("pause_store", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun dueRuleStartsSavedSetOnceWithScheduledEndTime() {
        val now = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 5, 8, 30, 20)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        store.savedAppSets = listOf(
            PauseStore.SavedAppSet(
                name = "Работа",
                packages = setOf("com.example.mail", "com.example.maps"),
                schedules = listOf(
                    PauseStore.SavedSetSchedule(
                        id = "work-morning",
                        enabled = true,
                        daysOfWeek = setOf(1, 2, 3, 4, 5),
                        startMinuteOfDay = 8 * 60 + 30,
                        durationMinutes = 90,
                    )
                ),
            )
        )

        val started = SavedSetScheduleEngine.maybeStart(
            context = context,
            store = store,
            nowEpochMs = now,
        )

        assertTrue(started)
        assertEquals("Работа", store.activeSavedSetName)
        assertEquals(
            setOf("com.example.mail", "com.example.maps"),
            store.selectedPackages
        )

        val scheduledAt = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 8)
            set(Calendar.MINUTE, 30)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        assertEquals(
            scheduledAt + 90 * 60_000L,
            store.sessionEndEpochMs
        )

        // Simulate a session being cleared during the same scheduled occurrence.
        store.clearSession()

        val startedAgain = SavedSetScheduleEngine.maybeStart(
            context = context,
            store = store,
            nowEpochMs = now + 10_000L,
        )

        assertFalse(startedAgain)
    }

    @Test
    fun savedSetSelectionDoesNotOverwriteManualSelection() {
        store.selectedPackages = setOf("com.example.manual")
        assertEquals(
            setOf("com.example.manual"),
            store.manualSelectedPackages
        )

        store.manualSelectedPackages = setOf("com.example.manual")
        store.selectedPackages = setOf("com.example.fromset")
        store.activeSavedSetName = "Работа"

        assertEquals(
            setOf("com.example.manual"),
            store.manualSelectedPackages
        )

        val restoredManual = store.manualSelectedPackages
        store.activeSavedSetName = null
        store.selectedPackages = restoredManual

        assertEquals(
            setOf("com.example.manual"),
            store.selectedPackages
        )
    }

    @Test
    fun disabledOrWrongDayRuleDoesNotStart() {
        val now = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 5, 8, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        store.savedAppSets = listOf(
            PauseStore.SavedAppSet(
                name = "Выходной",
                packages = setOf("com.example.player"),
                schedules = listOf(
                    PauseStore.SavedSetSchedule(
                        id = "weekend",
                        enabled = true,
                        daysOfWeek = setOf(6, 7),
                        startMinuteOfDay = 8 * 60 + 30,
                        durationMinutes = 60,
                    ),
                    PauseStore.SavedSetSchedule(
                        id = "disabled",
                        enabled = false,
                        daysOfWeek = setOf(1),
                        startMinuteOfDay = 8 * 60 + 30,
                        durationMinutes = 60,
                    )
                )
            )
        )

        assertFalse(
            SavedSetScheduleEngine.maybeStart(
                context = context,
                store = store,
                nowEpochMs = now,
            )
        )
        assertEquals(0L, store.sessionEndEpochMs)
    }
    @Test
    fun occurrenceDuringActivePauseIsSkippedAndNotReplayed() {
        val now = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 5, 8, 30, 10)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        store.savedAppSets = listOf(
            PauseStore.SavedAppSet(
                name = "Работа",
                packages = setOf("com.example.mail"),
                schedules = listOf(
                    PauseStore.SavedSetSchedule(
                        id = "work-overlap",
                        enabled = true,
                        daysOfWeek = setOf(1),
                        startMinuteOfDay = 8 * 60 + 30,
                        durationMinutes = 60,
                    )
                )
            )
        )
        store.sessionEndEpochMs = now + 30_000L

        assertFalse(
            SavedSetScheduleEngine.maybeStart(
                context = context,
                store = store,
                nowEpochMs = now,
            )
        )

        store.clearSession()

        assertFalse(
            SavedSetScheduleEngine.maybeStart(
                context = context,
                store = store,
                nowEpochMs = now + 40_000L,
            )
        )
        assertEquals(emptySet<String>(), store.selectedPackages)
    }

}
