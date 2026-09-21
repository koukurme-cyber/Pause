package ru.pauza.app.ui

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
private data class PauseDuration(
    val days: Int = 0,
    val hours: Int = 1,
    val minutes: Int = 0,
) {
    val totalMinutes: Long
        get() = days * 24L * 60L + hours * 60L + minutes

    val isValid: Boolean
        get() = totalMinutes >= 1L
}

@Composable
fun PauseRoot(
    store: PauseStore,
    appsRepository: InstalledAppsRepository,
    blocker: PauseBlocker,
) {
    val context = LocalContext.current
    var accessibilityEnabled by remember {
        mutableStateOf(AccessibilityPauseBlocker.isEnabled(context))
    }
    var firstSetupCompleted by remember {
        mutableStateOf(store.firstSetupCompleted)
    }

    LaunchedEffect(Unit) {
        while (true) {
            accessibilityEnabled = AccessibilityPauseBlocker.isEnabled(context)
            delay(700)
        }
    }

    if (!firstSetupCompleted || !accessibilityEnabled) {
        FirstSetupScreen(
            accessEnabled = accessibilityEnabled,
            onOpenAppSettings = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + context.packageName)
                    )
                )
            },
            onOpenAccessibility = {
                AccessibilityPauseBlocker.openSettings(context)
            },
            onContinue = {
                if (AccessibilityPauseBlocker.isEnabled(context)) {
                    store.firstSetupCompleted = true
                    firstSetupCompleted = true
                    accessibilityEnabled = true
                }
            },
        )
        return
    }

    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var alwaysApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selected by remember { mutableStateOf(store.selectedPackages) }
    var duration by remember { mutableStateOf(PauseDuration()) }

    val now = System.currentTimeMillis()
    var sessionEnd by remember {
        mutableLongStateOf(store.sessionEndEpochMs.takeIf { it > now } ?: 0L)
    }
    var screen by remember {
        mutableStateOf(if (sessionEnd > now) Screen.ACTIVE else Screen.SETUP)
    }

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
            alwaysApps = alwaysApps,
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
            alwaysApps = alwaysApps,
            selected = selected,
            duration = duration,
            onBack = { screen = Screen.SETUP },
            onStart = {
                sessionEnd = System.currentTimeMillis() + duration.totalMinutes * 60_000L
                store.selectedPackages = selected
                store.sessionEndEpochMs = sessionEnd
                blocker.start(
                    selected + appsRepository.alwaysAllowedPackages(),
                    sessionEnd
                )
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
            onTapExit = {
                blocker.stop()
                store.clearSession()
                sessionEnd = 0L
                screen = Screen.SETUP
            }
        )
    }
}

@Composable
private fun FirstSetupScreen(
    accessEnabled: Boolean,
    onOpenAppSettings: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onContinue: () -> Unit,
) {
    Surface(
        Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        LazyColumn(
            Modifier
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp),
            contentPadding = PaddingValues(top = 26.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item {
                Text(
                    "Сначала настроим доступ",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Это нужно один раз. Без этого Android позволит выйти из Паузы на рабочий стол или открыть закрытое приложение.",
                    color = PauseMuted,
                    lineHeight = 22.sp
                )
            }

            item {
                SetupStep(
                    number = "1",
                    title = "Разрешите настройки приложения",
                    text = "Откройте страницу «Пауза» в настройках телефона. Если в меню ⋮ есть пункт «Разрешить настройки с ограниченным доступом», нажмите его."
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onOpenAppSettings,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Открыть настройки приложения")
                }
            }

            item {
                SetupStep(
                    number = "2",
                    title = "Включите доступ в специальных возможностях",
                    text = "Откройте «Специальные возможности» → «Скачанные приложения» → «Пауза» и включите доступ."
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onOpenAccessibility,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Открыть специальные возможности")
                }
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (accessEnabled) PauseGreenSoft else PauseWarning
                    ),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (accessEnabled) "Доступ включён" else "Доступ ещё не включён",
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                if (accessEnabled) {
                                    "Можно переходить к приложению."
                                } else {
                                    "Вернитесь сюда после включения доступа."
                                },
                                color = PauseMuted,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = onContinue,
                    enabled = accessEnabled,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Text("Готово, открыть Паузу", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun SetupStep(
    number: String,
    title: String,
    text: String,
) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(PauseGreenSoft),
            contentAlignment = Alignment.Center
        ) {
            Text(number, color = PauseGreen, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Spacer(Modifier.height(4.dp))
            Text(text, color = PauseMuted, lineHeight = 20.sp)
        }
    }
}

@Composable
private fun SetupScreen(
    apps: List<InstalledApp>,
    alwaysApps: List<InstalledApp>,
    loading: Boolean,
    selected: Set<String>,
    duration: PauseDuration,
    onToggle: (String, Boolean) -> Unit,
    onDuration: (PauseDuration) -> Unit,
    onContinue: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    val allApps = remember(apps, alwaysApps) { alwaysApps + apps }
    val filteredApps = remember(allApps, searchQuery) {
        val query = searchQuery.trim()
        if (query.isBlank()) {
            allApps
        } else {
            allApps.filter { it.label.contains(query, ignoreCase = true) }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 10.dp)
        ) {
            Text("Пауза", fontSize = 29.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Выберите длительность и доступные приложения.",
                color = PauseMuted,
                fontSize = 14.sp,
                lineHeight = 18.sp
            )

            Spacer(Modifier.height(9.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = PauseGreenSoft),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(
                        "Длительность паузы",
                        color = PauseGreen,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                    DurationPicker(
                        duration = duration,
                        onChange = onDuration
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = PauseWarning),
                shape = RoundedCornerShape(18.dp)
            ) {
                Text(
                    "Проверьте, что оставили доступными нужные приложения: банковские приложения, карты и навигацию, транспорт и проездные, такси, домофон или пропуск, парковку, билеты и документы.",
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 10.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Найти приложение") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        TextButton(onClick = { searchQuery = "" }) {
                            Text("×", fontSize = 21.sp)
                        }
                    }
                }
            )

            Row(
                Modifier.fillMaxWidth().height(34.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (searchQuery.isBlank()) "Приложения" else "Найдено: " + filteredApps.size,
                    color = PauseMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                if (searchQuery.isNotBlank()) {
                    TextButton(onClick = { searchQuery = "" }) {
                        Text("Показать все", fontSize = 12.sp)
                    }
                }
            }

            if (loading) {
                Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (filteredApps.isEmpty()) {
                Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Ничего не найдено", color = PauseMuted)
                }
            } else {
                LazyColumn(
                    Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 6.dp)
                ) {
                    items(
                        items = filteredApps,
                        key = { it.launchType.name + ":" + it.packageName }
                    ) { app ->
                        val locked = app in alwaysApps
                        AppRow(
                            app = app,
                            checked = locked || app.packageName in selected,
                            locked = locked,
                            onChecked = { enabled ->
                                if (!locked) onToggle(app.packageName, enabled)
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }

            Button(
                onClick = onContinue,
                enabled = duration.isValid,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(17.dp)
            ) {
                Text("Продолжить", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun DurationPicker(
    duration: PauseDuration,
    onChange: (PauseDuration) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        DurationCounter(
            label = "Дни",
            value = duration.days,
            min = 0,
            max = 29,
            modifier = Modifier.weight(1f),
            onChange = { onChange(duration.copy(days = it)) }
        )
        DurationCounter(
            label = "Часы",
            value = duration.hours,
            min = 0,
            max = 23,
            modifier = Modifier.weight(1f),
            onChange = { onChange(duration.copy(hours = it)) }
        )
        DurationCounter(
            label = "Минуты",
            value = duration.minutes,
            min = 0,
            max = 59,
            modifier = Modifier.weight(1f),
            onChange = { onChange(duration.copy(minutes = it)) }
        )
    }
    Spacer(Modifier.height(5.dp))
    Text(
        "От 1 минуты до 29 дней 23 часов 59 минут",
        color = PauseMuted,
        fontSize = 10.sp
    )
}

@Composable
private fun DurationCounter(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    modifier: Modifier = Modifier,
    onChange: (Int) -> Unit,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(15.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(22.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = PauseMuted,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(
                Modifier.fillMaxWidth().height(34.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clickable(enabled = value > min) {
                            if (value > min) onChange(value - 1)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "−",
                        fontSize = 20.sp,
                        color = if (value > min) PauseMuted else PauseMuted.copy(alpha = 0.35f)
                    )
                }
                Box(
                    Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        value.toString(),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                }
                Box(
                    Modifier
                        .size(32.dp)
                        .clickable(enabled = value < max) {
                            if (value < max) onChange(value + 1)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "+",
                        fontSize = 20.sp,
                        color = if (value < max) PauseGreen else PauseMuted.copy(alpha = 0.35f)
                    )
                }
            }
        }
    }
}

@Composable
private fun ReviewScreen(
    apps: List<InstalledApp>,
    alwaysApps: List<InstalledApp>,
    selected: Set<String>,
    duration: PauseDuration,
    onBack: () -> Unit,
    onStart: () -> Unit,
) {
    val selectedApps = apps.filter { it.packageName in selected }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(20.dp)
        ) {
            Text(
                "Проверьте перед запуском",
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "После запуска список приложений изменить нельзя до окончания таймера.",
                color = PauseMuted,
                lineHeight = 22.sp
            )

            Spacer(Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = PauseWarning),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(
                    Modifier.padding(17.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "Точно всё нужное оставили?",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 17.sp
                    )
                    Text(
                        "Проверьте банковские приложения, карты и навигацию, транспорт и проездные, такси, домофон или пропуск, парковку, билеты и документы.",
                        color = PauseMuted,
                        lineHeight = 20.sp
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "Пауза: " + formatDuration(duration),
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(10.dp))

            val names = (alwaysApps.map { it.label } + selectedApps.map { it.label })
                .distinct()
            Text(
                names.joinToString(" · "),
                color = PauseMuted,
                lineHeight = 21.sp
            )

            Spacer(Modifier.weight(1f))
            HoldButton(onConfirmed = onStart)
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
    onTapExit: () -> Unit,
) {
    ActiveImmersiveMode()
    BackHandler(enabled = true) { }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var tapCount by remember { mutableIntStateOf(0) }
    var lastTapAt by remember { mutableLongStateOf(0L) }

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
            Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp)
        ) {
            Spacer(Modifier.height(34.dp))
            Text(
                "Пауза",
                fontSize = 32.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "Доступны только выбранные приложения",
                color = PauseMuted,
                fontSize = 14.sp
            )

            Spacer(Modifier.height(18.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                val tapAt = SystemClock.elapsedRealtime()
                                if (tapAt - lastTapAt > 3_000L) tapCount = 0
                                lastTapAt = tapAt
                                tapCount += 1
                                if (tapCount >= 7) {
                                    tapCount = 0
                                    onTapExit()
                                }
                            }
                        )
                    },
                colors = CardDefaults.cardColors(containerColor = PauseGreenSoft),
                shape = RoundedCornerShape(24.dp)
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val context = LocalContext.current
                    val clockBitmap = remember {
                        context.assets.open("ic_pause_clock.png").use { stream ->
                            BitmapFactory.decodeStream(stream)?.asImageBitmap()
                        }
                    }
                    if (clockBitmap != null) {
                        Image(
                            bitmap = clockBitmap,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp)
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(
                            "Осталось",
                            color = PauseMuted,
                            fontSize = 13.sp
                        )
                        Text(
                            text = formatRemainingForLauncher(remaining),
                            color = PauseGreen,
                            fontSize = if (remaining >= 24L * 60L * 60L * 1000L) 31.sp else 38.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(Modifier.height(26.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(
                    items = shortcuts,
                    key = { it.launchType.name + ":" + it.packageName }
                ) { app ->
                    LauncherAppIcon(
                        app = app,
                        onClick = { onLaunch(app) }
                    )
                }
            }
        }
    }
}

@Composable
private fun LauncherAppIcon(
    app: InstalledApp,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val bitmap = app.icon
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = app.label,
                modifier = Modifier.size(62.dp)
            )
        } else {
            Box(
                Modifier
                    .size(62.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(PauseGreenSoft),
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

        Spacer(Modifier.height(6.dp))
        Text(
            text = app.label,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
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
                    run {
                        decor.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    checked: Boolean,
    locked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIconSmall(app)
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, fontWeight = FontWeight.Medium)
            if (locked) {
                Text(
                    "Всегда доступно",
                    color = PauseMuted,
                    fontSize = 12.sp
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = if (locked) null else onChecked,
            enabled = !locked,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = PauseGreen,
                checkedBorderColor = PauseGreen,
                uncheckedThumbColor = PauseMuted,
                uncheckedTrackColor = MaterialTheme.colorScheme.surface,
                uncheckedBorderColor = PauseMuted,
                disabledCheckedThumbColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.55f),
                disabledCheckedTrackColor = PauseGreen.copy(alpha = 0.38f),
                disabledCheckedBorderColor = PauseGreen.copy(alpha = 0.38f),
            )
        )
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
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(PauseGreenSoft),
            contentAlignment = Alignment.Center
        ) {
            Text(
                app.label.take(1).uppercase(Locale.getDefault()),
                color = PauseGreen,
                fontWeight = FontWeight.Bold
            )
        }
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
    onConfirmed: () -> Unit,
) {
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
        Modifier
            .fillMaxWidth()
            .height(54.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(PauseGreen)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressing = true
                        tryAwaitRelease()
                        pressing = false
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            if (pressing) {
                "Продолжайте удерживать…"
            } else {
                "Удерживайте 2 секунды, чтобы начать"
            },
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

private fun formatDuration(duration: PauseDuration): String {
    val parts = mutableListOf<String>()
    if (duration.days > 0) parts += duration.days.toString() + " дн."
    if (duration.hours > 0) parts += duration.hours.toString() + " ч."
    if (duration.minutes > 0) parts += duration.minutes.toString() + " мин."
    return if (parts.isEmpty()) "0 мин." else parts.joinToString(" ")
}

private fun formatRemainingForLauncher(ms: Long): String {
    val total = ms / 1000
    val days = total / 86_400
    val hours = (total % 86_400) / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60

    return if (days > 0) {
        String.format(Locale.US, "%d дн %02d:%02d:%02d", days, hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }
}

private fun formatRemaining(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
}
