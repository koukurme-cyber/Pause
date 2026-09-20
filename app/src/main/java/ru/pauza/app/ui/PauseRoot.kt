package ru.pauza.app.ui

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
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
import ru.pauza.app.domain.AccessibilityPauseBlocker
import ru.pauza.app.domain.PauseBlocker
import ru.pauza.app.model.InstalledApp
import ru.pauza.app.ui.theme.PauseGreen
import ru.pauza.app.ui.theme.PauseGreenSoft
import ru.pauza.app.ui.theme.PauseMuted
import ru.pauza.app.ui.theme.PauseWarning
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
    var alwaysApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf(store.selectedPackages) }
    var duration by remember { mutableStateOf(durations[1]) }
    val now = System.currentTimeMillis()
    var sessionEnd by remember { mutableLongStateOf(store.sessionEndEpochMs.takeIf { it > now } ?: 0L) }
    var screen by remember { mutableStateOf(if (sessionEnd > now) Screen.ACTIVE else Screen.SETUP) }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            appsRepository.loadLaunchableApps() to appsRepository.loadAlwaysAllowedApps()
        }
        apps = loaded.first
        alwaysApps = loaded.second
        loading = false
    }

    when (screen) {
        Screen.SETUP -> SetupScreen(
            apps = apps,
            loading = loading,
            selected = selected,
            duration = duration,
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
            alwaysApps = alwaysApps,
            selected = selected,
            sessionEnd = sessionEnd,
            onLaunch = appsRepository::launch,
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
        Column(
            Modifier
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 18.dp)
        ) {
            Text("Пауза", fontSize = 32.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Оставьте доступным только то, что действительно может понадобиться.",
                color = PauseMuted,
                lineHeight = 21.sp
            )
            Spacer(Modifier.height(16.dp))

            SectionLabel("ВСЕГДА ДОСТУПНО")
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FixedCompact("Телефон", "Т", Modifier.weight(1f))
                FixedCompact("Сообщения", "С", Modifier.weight(1f))
            }

            Spacer(Modifier.height(14.dp))
            SectionLabel("ДЛИТЕЛЬНОСТЬ")
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                durations.forEach {
                    FilterChip(
                        selected = duration == it,
                        onClick = { onDuration(it) },
                        label = { Text(it.label) }
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            SectionLabel("РАЗРЕШЁННЫЕ ПРИЛОЖЕНИЯ")
            Spacer(Modifier.height(6.dp))

            if (loading) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 10.dp)
                ) {
                    items(apps, key = { it.packageName }) { app ->
                        AppRow(app, app.packageName in selected) { onToggle(app.packageName, it) }
                    }
                }
            }

            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(18.dp)
            ) {
                Text("Проверить и начать", fontWeight = FontWeight.SemiBold)
            }
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
    val context = LocalContext.current
    val selectedApps = apps.filter { it.packageName in selected }
    var protectionEnabled by remember {
        mutableStateOf(AccessibilityPauseBlocker.isEnabled(context))
    }

    LaunchedEffect(Unit) {
        while (true) {
            protectionEnabled = AccessibilityPauseBlocker.isEnabled(context)
            delay(600)
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(20.dp)
        ) {
            Text("Проверьте всё ещё раз", fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                "После начала Паузы изменить список приложений или закончить её раньше будет нельзя.",
                color = PauseMuted,
                lineHeight = 22.sp
            )
            Spacer(Modifier.height(16.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = PauseWarning),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Проверьте важное", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                    Text(
                        "Банк · карты · транспорт · такси · домофон/пропуск · парковка · билеты · документы",
                        color = PauseMuted,
                        lineHeight = 20.sp
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (protectionEnabled) PauseGreenSoft else MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(20.dp)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(15.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Защита Паузы", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (protectionEnabled) "Включена" else "Нужно разрешить один раз",
                            color = if (protectionEnabled) PauseGreen else PauseMuted,
                            fontSize = 13.sp
                        )
                    }
                    if (!protectionEnabled) {
                        TextButton(onClick = { AccessibilityPauseBlocker.openSettings(context) }) {
                            Text("Разрешить")
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Text("Пауза: " + duration.label, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text("Телефон · Сообщения", fontWeight = FontWeight.Medium)
            if (selectedApps.isNotEmpty()) {
                Spacer(Modifier.height(5.dp))
                Text(
                    selectedApps.joinToString(" · ") { it.label },
                    color = PauseMuted,
                    lineHeight = 20.sp
                )
            }

            Spacer(Modifier.weight(1f))
            HoldButton(enabled = protectionEnabled, onConfirmed = onStart)
            if (!protectionEnabled) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Без защиты Пауза не запускается: иначе можно было бы просто уйти на рабочий стол.",
                    color = PauseMuted,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("Вернуться и изменить")
            }
        }
    }
}

@Composable
private fun ActiveScreen(
    apps: List<InstalledApp>,
    alwaysApps: List<InstalledApp>,
    selected: Set<String>,
    sessionEnd: Long,
    onLaunch: (InstalledApp) -> Boolean,
    onFinished: () -> Unit,
    onDevStop: () -> Unit,
) {
    ActiveImmersiveMode()
    BackHandler(enabled = true) { }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var devTapCount by remember { mutableIntStateOf(0) }
    var lastDevTapAt by remember { mutableLongStateOf(0L) }
    val remaining = max(0L, sessionEnd - now)

    LaunchedEffect(sessionEnd) {
        while (true) {
            now = System.currentTimeMillis()
            if (now >= sessionEnd) {
                onFinished()
                break
            }
            delay(1000)
        }
    }

    val shortcuts = remember(apps, alwaysApps, selected) {
        alwaysApps + apps.filter { it.packageName in selected }
    }

    Surface(
        Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(104.dp))

            Text(
                text = formatRemaining(remaining),
                fontSize = 58.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.pointerInput(BuildConfig.DEBUG) {
                    detectTapGestures(
                        onTap = {
                            if (!BuildConfig.DEBUG) return@detectTapGestures
                            val tapAt = SystemClock.elapsedRealtime()
                            if (tapAt - lastDevTapAt > 3_000L) devTapCount = 0
                            lastDevTapAt = tapAt
                            devTapCount += 1
                            if (devTapCount >= 7) {
                                devTapCount = 0
                                onDevStop()
                            }
                        }
                    )
                }
            )

            Spacer(Modifier.height(72.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(26.dp),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                items(
                    items = shortcuts,
                    key = { it.launchType.name + ":" + it.packageName }
                ) { app ->
                    ActiveAppIcon(
                        app = app,
                        onClick = { onLaunch(app) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveAppIcon(
    app: InstalledApp,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        val bitmap = app.icon
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = app.label,
                modifier = Modifier.fillMaxSize(0.78f)
            )
        } else {
            Box(
                Modifier.fillMaxSize(0.78f).clip(CircleShape).background(PauseGreenSoft),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    app.label.take(1).uppercase(Locale.getDefault()),
                    color = PauseGreen,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp
                )
            }
        }
    }
}

@Composable
private fun ActiveImmersiveMode() {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val window = activity?.window
        val decor = window?.decorView

        if (window != null && decor != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.let { controller ->
                    controller.hide(WindowInsets.Type.systemBars())
                    controller.systemBarsBehavior =
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                run {
                    decor.systemUiVisibility =
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                }
            }
        }

        onDispose {
            if (window != null && decor != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    window.insetsController?.show(WindowInsets.Type.systemBars())
                } else {
                    @Suppress("DEPRECATION")
                    run { decor.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE }
                }
            }
        }
    }
}

@Composable
private fun FixedCompact(
    title: String,
    initial: String,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier, shape = RoundedCornerShape(18.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            InitialIcon(initial)
            Spacer(Modifier.width(9.dp))
            Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
        }
    }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Card(shape = RoundedCornerShape(18.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIconSmall(app)
            Spacer(Modifier.width(10.dp))
            Text(app.label, Modifier.weight(1f), fontWeight = FontWeight.Medium)
            Switch(
                checked = checked,
                onCheckedChange = onChecked,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = PauseGreen,
                    checkedBorderColor = PauseGreen,
                    uncheckedThumbColor = PauseMuted,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surface,
                    uncheckedBorderColor = PauseMuted,
                )
            )
        }
    }
}

@Composable
private fun AppIconSmall(app: InstalledApp) {
    val bitmap = app.icon
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(40.dp)
        )
    } else {
        InitialIcon(app.label.take(1).uppercase(Locale.getDefault()))
    }
}

@Composable
private fun InitialIcon(initial: String) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(PauseGreenSoft),
        contentAlignment = Alignment.Center
    ) {
        Text(initial, color = PauseGreen, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = PauseMuted,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = .8.sp
    )
}

@Composable
private fun HoldButton(
    enabled: Boolean,
    onConfirmed: () -> Unit,
) {
    var pressing by remember { mutableStateOf(false) }

    LaunchedEffect(pressing, enabled) {
        if (pressing && enabled) {
            delay(2000)
            if (pressing) {
                pressing = false
                onConfirmed()
            }
        }
    }

    val background = if (enabled) PauseGreen else PauseMuted.copy(alpha = 0.35f)

    Box(
        Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .pointerInput(enabled) {
                if (enabled) {
                    detectTapGestures(
                        onPress = {
                            pressing = true
                            tryAwaitRelease()
                            pressing = false
                        }
                    )
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            when {
                !enabled -> "Сначала включите защиту"
                pressing -> "Продолжайте удерживать…"
                else -> "Удерживайте 2 секунды, чтобы начать"
            },
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
