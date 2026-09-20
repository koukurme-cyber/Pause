package ru.pauza.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import ru.pauza.app.BuildConfig
import ru.pauza.app.data.InstalledAppsRepository
import ru.pauza.app.data.PauseStore
import ru.pauza.app.domain.PauseBlocker
import ru.pauza.app.model.InstalledApp
import ru.pauza.app.ui.theme.PauseGreen
import ru.pauza.app.ui.theme.PauseGreenSoft
import ru.pauza.app.ui.theme.PauseMuted
import ru.pauza.app.ui.theme.PauseWarning
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

private enum class Screen { SETUP, REVIEW, ACTIVE }
private data class DurationChoice(val minutes: Int, val label: String)
private val durations = listOf(
    DurationChoice(30, "30 мин"),
    DurationChoice(60, "1 час"),
    DurationChoice(120, "2 часа"),
    DurationChoice(240, "4 часа"),
)

@Composable
fun PauseRoot(
    store: PauseStore,
    appsRepository: InstalledAppsRepository,
    blocker: PauseBlocker,
) {
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf(store.selectedPackages) }
    var duration by remember { mutableStateOf(durations[1]) }
    val now = System.currentTimeMillis()
    var sessionEnd by remember { mutableLongStateOf(store.sessionEndEpochMs.takeIf { it > now } ?: 0L) }
    var screen by remember { mutableStateOf(if (sessionEnd > now) Screen.ACTIVE else Screen.SETUP) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { appsRepository.loadLaunchableApps() }
        loading = false
    }

    when (screen) {
        Screen.SETUP -> SetupScreen(
            apps, loading, selected, duration,
            onToggle = { pkg, enabled ->
                selected = if (enabled) selected + pkg else selected - pkg
                store.selectedPackages = selected
            },
            onDuration = { duration = it },
            onContinue = { screen = Screen.REVIEW },
        )

        Screen.REVIEW -> ReviewScreen(
            apps = apps,
            selected = selected,
            duration = duration,
            onBack = { screen = Screen.SETUP },
            onStart = {
                val requested = duration.minutes * 60_000L
                val effective = if (BuildConfig.DEBUG) minOf(requested, 10 * 60_000L) else requested
                sessionEnd = System.currentTimeMillis() + effective
                store.selectedPackages = selected
                store.sessionEndEpochMs = sessionEnd
                blocker.start(selected + appsRepository.alwaysAllowedPackages(), sessionEnd)
                screen = Screen.ACTIVE
            }
        )

        Screen.ACTIVE -> ActiveScreen(
            apps = apps,
            selected = selected,
            sessionEnd = sessionEnd,
            onFinished = {
                blocker.stop()
                store.clearSession()
                sessionEnd = 0L
                screen = Screen.SETUP
            },
            onDevStop = {
                blocker.stop()
                store.clearSession()
                sessionEnd = 0L
                screen = Screen.SETUP
            }
        )
    }
}

@Composable
private fun SetupScreen(
    apps: List<InstalledApp>,
    loading: Boolean,
    selected: Set<String>,
    duration: DurationChoice,
    onToggle: (String, Boolean) -> Unit,
    onDuration: (DurationChoice) -> Unit,
    onContinue: () -> Unit,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 18.dp)) {
            Text("Пауза", fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("Оставьте доступным только то, что действительно может понадобиться.", color = PauseMuted, lineHeight = 21.sp)
            Spacer(Modifier.height(20.dp))

            SectionLabel("ВСЕГДА ДОСТУПНО")
            Spacer(Modifier.height(8.dp))
            FixedRow("Телефон", "Т")
            Spacer(Modifier.height(6.dp))
            FixedRow("Сообщения", "С")

            Spacer(Modifier.height(20.dp))
            SectionLabel("ДЛИТЕЛЬНОСТЬ")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                durations.forEach {
                    FilterChip(
                        selected = duration == it,
                        onClick = { onDuration(it) },
                        label = { Text(it.label) }
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionLabel("РАЗРЕШЁННЫЕ ПРИЛОЖЕНИЯ")
            Spacer(Modifier.height(8.dp))

            if (loading) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 12.dp)
                ) {
                    items(apps, key = { it.packageName }) { app ->
                        AppRow(app, app.packageName in selected) { onToggle(app.packageName, it) }
                    }
                }
            }

            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                shape = RoundedCornerShape(18.dp)
            ) { Text("Проверить и начать", fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun ReviewScreen(
    apps: List<InstalledApp>,
    selected: Set<String>,
    duration: DurationChoice,
    onBack: () -> Unit,
    onStart: () -> Unit,
) {
    val selectedApps = apps.filter { it.packageName in selected }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.statusBarsPadding().navigationBarsPadding().padding(20.dp)) {
            Text("Проверьте всё ещё раз", fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text("После начала Паузы изменить список приложений или закончить её раньше будет нельзя.", color = PauseMuted, lineHeight = 22.sp)
            Spacer(Modifier.height(18.dp))

            Card(colors = CardDefaults.cardColors(containerColor = PauseWarning), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Что может понадобиться", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    Text("• банковские приложения", color = PauseMuted)
                    Text("• карты и навигация", color = PauseMuted)
                    Text("• проездные и транспорт", color = PauseMuted)
                    Text("• такси", color = PauseMuted)
                    Text("• домофон, пропуск и парковка", color = PauseMuted)
                    Text("• билеты и документы", color = PauseMuted)
                }
            }

            Spacer(Modifier.height(18.dp))
            Text("Пауза: " + duration.label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            SectionLabel("ОСТАНУТСЯ ДОСТУПНЫ")
            Spacer(Modifier.height(8.dp))
            Text("Телефон · Сообщения", fontWeight = FontWeight.Medium)
            if (selectedApps.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(selectedApps.joinToString(" · ") { it.label }, color = PauseMuted, lineHeight = 21.sp)
            }

            Spacer(Modifier.weight(1f))
            if (BuildConfig.DEBUG) {
                Text("DEV: тестовая Пауза автоматически ограничена 10 минутами.", color = PauseMuted, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
            }
            HoldButton(onStart)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp)) {
                Text("Вернуться и изменить")
            }
        }
    }
}

@Composable
private fun ActiveScreen(
    apps: List<InstalledApp>,
    selected: Set<String>,
    sessionEnd: Long,
    onFinished: () -> Unit,
    onDevStop: () -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val remaining = max(0L, sessionEnd - now)
    LaunchedEffect(sessionEnd) {
        while (true) {
            now = System.currentTimeMillis()
            if (now >= sessionEnd) { onFinished(); break }
            delay(1000)
        }
    }
    val finishTime = remember(sessionEnd) { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(sessionEnd)) }
    val selectedApps = apps.filter { it.packageName in selected }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier.statusBarsPadding().navigationBarsPadding().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Пауза", fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(58.dp))
            Text(formatRemaining(remaining), fontSize = 54.sp, fontWeight = FontWeight.Medium)
            Text("осталось", color = PauseMuted)
            Spacer(Modifier.height(22.dp))
            Text("До " + finishTime + " ничего менять нельзя", textAlign = TextAlign.Center)
            Spacer(Modifier.height(42.dp))

            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(18.dp)) {
                    SectionLabel("ДОСТУПНО СЕЙЧАС")
                    Spacer(Modifier.height(12.dp))
                    Text("Телефон · Сообщения", fontWeight = FontWeight.SemiBold)
                    if (selectedApps.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(selectedApps.joinToString(" · ") { it.label }, color = PauseMuted, lineHeight = 21.sp)
                    }
                }
            }

            Spacer(Modifier.weight(1f))
            if (BuildConfig.DEBUG) {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = PauseGreenSoft),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("DEV-предохранитель", color = PauseGreen, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text("В тестовой сборке блокировку всегда можно снять вручную.", color = PauseMuted, fontSize = 13.sp)
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = onDevStop, modifier = Modifier.fillMaxWidth()) { Text("DEV: завершить тестовую Паузу") }
                    }
                }
            }
        }
    }
}

@Composable
private fun FixedRow(title: String, initial: String) {
    Card(shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            InitialIcon(initial)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text("Всегда доступно", color = PauseMuted, fontSize = 12.sp)
            }
            Text("Всегда", color = PauseGreen, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun AppRow(app: InstalledApp, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Card(shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            InitialIcon(app.label.take(1).uppercase(Locale.getDefault()))
            Spacer(Modifier.width(12.dp))
            Text(app.label, Modifier.weight(1f), fontWeight = FontWeight.Medium)
            Switch(checked = checked, onCheckedChange = onChecked)
        }
    }
}

@Composable
private fun InitialIcon(initial: String) {
    Box(
        Modifier.size(42.dp).clip(CircleShape).background(PauseGreenSoft),
        contentAlignment = Alignment.Center
    ) {
        Text(initial, color = PauseGreen, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = PauseMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = .8.sp)
}

@Composable
private fun HoldButton(onConfirmed: () -> Unit) {
    var pressing by remember { mutableStateOf(false) }
    LaunchedEffect(pressing) {
        if (pressing) {
            delay(2000)
            if (pressing) {
                pressing = false
                onConfirmed()
            }
        }
    }
    Box(
        Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(18.dp)).background(PauseGreen)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    pressing = true
                    tryAwaitRelease()
                    pressing = false
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (pressing) "Продолжайте удерживать…" else "Удерживайте 2 секунды, чтобы начать",
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

private fun formatRemaining(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
}
