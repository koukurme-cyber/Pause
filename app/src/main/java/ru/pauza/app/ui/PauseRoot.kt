package ru.pauza.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import ru.pauza.app.R
import ru.pauza.app.data.InstalledAppsRepository
import ru.pauza.app.data.PauseStore
import ru.pauza.app.domain.AccessibilityPauseBlocker
import ru.pauza.app.domain.BatteryOptimizationHelper
import ru.pauza.app.domain.PauseBlocker
import ru.pauza.app.domain.UsageAccessMonitor
import ru.pauza.app.model.InstalledApp
import ru.pauza.app.ui.theme.PauseGreen
import ru.pauza.app.ui.theme.PauseGreenSoft
import ru.pauza.app.ui.theme.PauseMuted
import ru.pauza.app.ui.theme.PauseWarning
import java.util.Locale
import kotlin.math.abs
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
    var usageAccessEnabled by remember {
        mutableStateOf(UsageAccessMonitor.isGranted(context))
    }
    var batteryUnrestricted by remember {
        mutableStateOf(BatteryOptimizationHelper.isUnrestricted(context))
    }
    var restrictedSettingsConfirmed by remember {
        mutableStateOf(store.restrictedSettingsConfirmed)
    }
    var setupChecklistCompleted by remember {
        mutableStateOf(store.setupChecklistCompleted)
    }

    LaunchedEffect(Unit) {
        while (true) {
            accessibilityEnabled = AccessibilityPauseBlocker.isEnabled(context)
            usageAccessEnabled = UsageAccessMonitor.isGranted(context)
            batteryUnrestricted = BatteryOptimizationHelper.isUnrestricted(context)
            delay(700)
        }
    }

    if (
        !setupChecklistCompleted ||
        !restrictedSettingsConfirmed ||
        !usageAccessEnabled ||
        !accessibilityEnabled
    ) {
        SetupChecklistScreen(
            restrictedSettingsConfirmed = restrictedSettingsConfirmed,
            usageAccessEnabled = usageAccessEnabled,
            accessibilityEnabled = accessibilityEnabled,
            batteryUnrestricted = batteryUnrestricted,
            onOpenAppSettings = {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + context.packageName)
                    )
                )
            },
            onConfirmRestrictedSettings = {
                store.restrictedSettingsConfirmed = true
                restrictedSettingsConfirmed = true
            },
            onOpenUsageAccess = {
                UsageAccessMonitor.openSettings(context)
            },
            onOpenAccessibility = {
                AccessibilityPauseBlocker.openSettings(context)
            },
            onOpenBatterySettings = {
                BatteryOptimizationHelper.openSettings(context)
            },
            onContinue = {
                if (
                    restrictedSettingsConfirmed &&
                    UsageAccessMonitor.isGranted(context) &&
                    AccessibilityPauseBlocker.isEnabled(context)
                ) {
                    store.firstSetupCompleted = true
                    store.setupChecklistCompleted = true
                    setupChecklistCompleted = true
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
private fun BrandHeader(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(R.drawable.ic_pauza),
            contentDescription = null,
            modifier = Modifier
                .size(if (compact) 48.dp else 62.dp)
                .clip(RoundedCornerShape(if (compact) 15.dp else 19.dp))
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                "Пауза",
                fontSize = if (compact) 27.sp else 34.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                "Только нужное",
                fontSize = if (compact) 13.sp else 15.sp,
                color = PauseMuted
            )
        }
    }
}

@Composable
private fun SoftScreenBackground(
    content: @Composable BoxScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.pauza_ui_bg),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize()
        )
        content()
    }
}

@Composable
private fun SetupChecklistScreen(
    restrictedSettingsConfirmed: Boolean,
    usageAccessEnabled: Boolean,
    accessibilityEnabled: Boolean,
    batteryUnrestricted: Boolean,
    onOpenAppSettings: () -> Unit,
    onConfirmRestrictedSettings: () -> Unit,
    onOpenUsageAccess: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onContinue: () -> Unit,
) {
    var restrictedSettingsOpened by rememberSaveable { mutableStateOf(false) }

    val requiredReady =
        restrictedSettingsConfirmed && usageAccessEnabled && accessibilityEnabled

    SoftScreenBackground {
        LazyColumn(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp),
            contentPadding = PaddingValues(top = 18.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                BrandHeader()
                Spacer(Modifier.height(24.dp))
                Text(
                    "Настройка Паузы",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    "Сделайте настройки по порядку. Следующий шаг станет доступен после предыдущего.",
                    color = PauseMuted,
                    lineHeight = 21.sp
                )
            }

            item {
                SetupChecklistCard(
                    number = "1",
                    title = "Разрешить запрещённые настройки",
                    text = "Откройте настройки приложения «Пауза». На HyperOS: меню ⋮ → «Разрешить запрещённые настройки». Если такого пункта нет, просто вернитесь и подтвердите шаг.",
                    completed = restrictedSettingsConfirmed,
                    enabled = !restrictedSettingsConfirmed,
                    buttonText = if (restrictedSettingsOpened) {
                        "Подтвердить, что разрешено"
                    } else {
                        "Открыть настройки приложения"
                    },
                    onClick = {
                        if (restrictedSettingsOpened) {
                            onConfirmRestrictedSettings()
                        } else {
                            restrictedSettingsOpened = true
                            onOpenAppSettings()
                        }
                    }
                )
            }

            item {
                SetupChecklistCard(
                    number = "2",
                    title = "Доступ к статистике использования",
                    text = "Нужен только для определения приложения на переднем плане и блокировки всего, чего нет в белом списке.",
                    completed = usageAccessEnabled,
                    enabled = restrictedSettingsConfirmed && !usageAccessEnabled,
                    buttonText = "Открыть настройки",
                    onClick = onOpenUsageAccess
                )
            }

            item {
                SetupChecklistCard(
                    number = "3",
                    title = "Специальные возможности",
                    text = "Включите «Пауза» в специальных возможностях. Это резервный контроль и блокирующий экран.",
                    completed = accessibilityEnabled,
                    enabled = restrictedSettingsConfirmed &&
                        usageAccessEnabled &&
                        !accessibilityEnabled,
                    buttonText = "Открыть настройки",
                    onClick = onOpenAccessibility
                )
            }

            item {
                SetupChecklistCard(
                    number = "4",
                    title = "Работа без ограничений батареи",
                    text = "Рекомендуется для более надёжной работы Паузы в фоне. Этот шаг необязательный.",
                    completed = batteryUnrestricted,
                    enabled = requiredReady && !batteryUnrestricted,
                    buttonText = "Открыть настройки",
                    onClick = onOpenBatterySettings,
                    optional = true
                )
            }

            item {
                Spacer(Modifier.height(2.dp))
                Button(
                    onClick = onContinue,
                    enabled = requiredReady,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF123D2E),
                        disabledContainerColor = Color(0xFFD9DCD6),
                    )
                ) {
                    Text(
                        if (batteryUnrestricted) {
                            "Готово, открыть Паузу"
                        } else {
                            "Продолжить"
                        },
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                }

                if (requiredReady && !batteryUnrestricted) {
                    Spacer(Modifier.height(7.dp))
                    Text(
                        "Можно продолжить без изменения настроек батареи.",
                        color = PauseMuted,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupChecklistCard(
    number: String,
    title: String,
    text: String,
    completed: Boolean,
    enabled: Boolean,
    buttonText: String,
    onClick: () -> Unit,
    optional: Boolean = false,
) {
    val outline = when {
        completed -> Color(0xFFB7D8BB)
        enabled -> Color(0xFFB8CCB8)
        else -> Color(0xFFE1E2DD)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            ,
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFEFA).copy(alpha = .90f)),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, outline.copy(alpha = .25f))
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                completed -> Color(0xFF38B54A)
                                enabled -> Color(0xFFE0F2DF)
                                else -> Color(0xFFE8E8E4)
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (completed) "✓" else number,
                        color = when {
                            completed -> Color.White
                            enabled -> Color(0xFF17633D)
                            else -> Color(0xFF858984)
                        },
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.width(13.dp))

                Column(Modifier.weight(1f)) {
                    Column {
                        Text(
                            title,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                        )
                        if (optional) {
                            Spacer(Modifier.height(5.dp))
                            Surface(
                                color = Color(0xFFFFE8A8),
                                shape = RoundedCornerShape(99.dp)
                            ) {
                                Text(
                                    "необязательно",
                                    color = Color(0xFF765B18),
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text,
                        color = PauseMuted,
                        lineHeight = 18.sp,
                        fontSize = 13.sp
                    )
                    if (completed) {
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "Готово",
                            color = Color(0xFF21813B),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            if (!completed) {
                Button(
                    onClick = onClick,
                    enabled = enabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(15.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFE1F2DE),
                        contentColor = Color(0xFF164E34),
                        disabledContainerColor = Color(0xFFF0F0ED),
                        disabledContentColor = Color(0xFF9A9C98),
                    )
                ) {
                    Text(buttonText, fontWeight = FontWeight.SemiBold)
                }
            }
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

    SoftScreenBackground {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 8.dp)
        ) {
            BrandHeader(compact = true)
            Spacer(Modifier.height(6.dp))
            Text(
                "Выберите длительность и доступные приложения.",
                color = PauseMuted,
                fontSize = 14.sp,
                lineHeight = 18.sp
            )

            Spacer(Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFEFA).copy(alpha = .90f)),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, Color(0xFFE1E3DC))
            ) {
                Column(
                    Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        "Длительность паузы",
                        color = Color(0xFF184F35),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(3.dp))
                    DurationPicker(
                        duration = duration,
                        onChange = onDuration
                    )
                }
            }

            Spacer(Modifier.height(6.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF4D8)),
                shape = RoundedCornerShape(18.dp)
            ) {
                Text(
                    "Проверьте, что оставили доступными нужные приложения: банковские приложения, карты и навигацию, транспорт и проездные, такси, домофон или пропуск, парковку, билеты и документы.",
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }

            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Найти приложение") },
                singleLine = true,
                shape = RoundedCornerShape(17.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color(0xFFFFFEFA),
                    unfocusedContainerColor = Color(0xFFFFFEFA),
                    focusedBorderColor = Color(0xFF85B18D),
                    unfocusedBorderColor = Color(0xFFD9DDD5)
                ),
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        TextButton(onClick = { searchQuery = "" }) {
                            Text("×", fontSize = 21.sp)
                        }
                    }
                }
            )

            Row(
                Modifier.fillMaxWidth().height(28.dp),
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

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFEFA).copy(alpha = .90f)),
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(0.dp, Color.Transparent)
            ) {
                when {
                    loading -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }

                    filteredApps.isEmpty() -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) { Text("Ничего не найдено", color = PauseMuted) }

                    else -> LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
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
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onContinue,
                enabled = duration.isValid,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF184B34),
                    disabledContainerColor = Color(0xFFD9DCD6)
                )
            ) {
                Text("Продолжить", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun DurationPicker(
    duration: PauseDuration,
    onChange: (PauseDuration) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(90.dp)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(26.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(PauseMuted.copy(alpha = 0.09f))
        )

        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            DurationWheel(
                value = duration.days,
                max = 29,
                modifier = Modifier.weight(1f),
                formatter = { formatDaysWheel(it) },
                onValueChange = { onChange(duration.copy(days = it)) }
            )
            DurationWheel(
                value = duration.hours,
                max = 23,
                modifier = Modifier.weight(1f),
                formatter = { "$it ч" },
                onValueChange = { onChange(duration.copy(hours = it)) }
            )
            DurationWheel(
                value = duration.minutes,
                max = 59,
                modifier = Modifier.weight(1f),
                formatter = { "$it мин" },
                onValueChange = { onChange(duration.copy(minutes = it)) }
            )
        }
    }

    Spacer(Modifier.height(4.dp))
    Text(
        "От 1 минуты до 29 дней 23 часов 59 минут",
        color = PauseMuted,
        fontSize = 10.sp
    )
}

@Composable
private fun DurationWheel(
    value: Int,
    max: Int,
    modifier: Modifier = Modifier,
    formatter: (Int) -> String,
    onValueChange: (Int) -> Unit,
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = value.coerceIn(0, max)
    )
    val latestValue by rememberUpdatedState(value)
    val latestOnValueChange by rememberUpdatedState(onValueChange)

    val centeredValue by remember(listState, max, value) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val visible = layout.visibleItemsInfo
            if (visible.isEmpty()) {
                value.coerceIn(0, max)
            } else {
                val viewportCenter =
                    (layout.viewportStartOffset + layout.viewportEndOffset) / 2
                val nearest = visible.minByOrNull { item ->
                    abs((item.offset + item.size / 2) - viewportCenter)
                }
                ((nearest?.index ?: (value + 2)) - 2).coerceIn(0, max)
            }
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (!scrolling && listState.layoutInfo.visibleItemsInfo.isNotEmpty()) {
                    val target = centeredValue.coerceIn(0, max)
                    if (
                        listState.firstVisibleItemIndex != target ||
                        listState.firstVisibleItemScrollOffset != 0
                    ) {
                        listState.animateScrollToItem(target)
                    }
                    if (target != latestValue) {
                        latestOnValueChange(target)
                    }
                }
            }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxHeight(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        items(
            count = max + 5,
            key = { it }
        ) { listIndex ->
            val actualValue = listIndex - 2
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(18.dp),
                contentAlignment = Alignment.Center
            ) {
                if (actualValue in 0..max) {
                    val distance = abs(actualValue - centeredValue)
                    val alpha = when (distance) {
                        0 -> 1f
                        1 -> 0.52f
                        else -> 0.16f
                    }
                    val fontSize = when (distance) {
                        0 -> 18.sp
                        1 -> 13.sp
                        else -> 10.sp
                    }
                    val fontWeight = when (distance) {
                        0 -> FontWeight.SemiBold
                        1 -> FontWeight.Medium
                        else -> FontWeight.Normal
                    }

                    Text(
                        text = formatter(actualValue),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                        fontSize = fontSize,
                        fontWeight = fontWeight,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

private fun formatDaysWheel(value: Int): String {
    val mod100 = value % 100
    val mod10 = value % 10
    val word = when {
        mod100 in 11..14 -> "дней"
        mod10 == 1 -> "день"
        mod10 in 2..4 -> "дня"
        else -> "дней"
    }
    return "$value $word"
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
    val reviewApps = remember(apps, alwaysApps, selected) {
        (alwaysApps + apps.filter { it.packageName in selected })
            .distinctBy { it.launchType.name + ":" + it.packageName }
    }

    SoftScreenBackground {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(18.dp)
        ) {
            BrandHeader(compact = true)
            Spacer(Modifier.height(10.dp))
            Text(
                "Проверьте перед запуском",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "После запуска список приложений изменить нельзя до окончания таймера.",
                color = PauseMuted,
                lineHeight = 19.sp
            )

            Spacer(Modifier.height(10.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF4D8)),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(
                    Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "Точно всё нужное оставили?",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                    Text(
                        "Проверьте банковские приложения, карты и навигацию, транспорт и проездные, такси, домофон или пропуск, парковку, билеты и документы.",
                        color = PauseMuted,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Card(
                modifier = Modifier.fillMaxWidth().weight(1f),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFEFA).copy(alpha = .90f)),
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.dp, Color(0xFFE2E4DD))
            ) {
                Column(Modifier.fillMaxSize().padding(15.dp)) {
                    Text(
                        "Пауза: " + formatDuration(duration),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Доступные приложения",
                        color = PauseMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = .8.sp
                    )
                    Spacer(Modifier.height(12.dp))

                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                        contentPadding = PaddingValues(bottom = 8.dp)
                    ) {
                        items(
                            items = reviewApps,
                            key = { it.launchType.name + ":" + it.packageName }
                        ) { app ->
                            LauncherAppIcon(app = app)
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            HoldButton(onConfirmed = onStart)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, Color(0xFFB9C3B8))
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

    var remaining by remember(sessionEnd) {
        mutableLongStateOf(max(0L, sessionEnd - System.currentTimeMillis()))
    }
    var tapCount by remember { mutableIntStateOf(0) }
    var lastTapAt by remember { mutableLongStateOf(0L) }

    LaunchedEffect(sessionEnd) {
        while (true) {
            remaining = max(0L, sessionEnd - System.currentTimeMillis())
            if (remaining <= 0L) {
                onFinished()
                break
            }
            delay(250)
        }
    }

    val shortcuts = remember(apps, alwaysApps, selected) {
        alwaysApps + apps.filter { it.packageName in selected }
    }

    Box(Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.pauza_active_concept_bg),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize()
        )

        Box(
            Modifier
                .matchParentSize()
                .background(Color(0xFFFBF7EA).copy(alpha = .10f))
        )

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(22.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF3DB94A))
                )
                Spacer(Modifier.width(9.dp))
                Text(
                    "Пауза",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF172119)
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "Доступны только выбранные приложения",
                color = Color(0xFF596359),
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(24.dp))

            val timerTapInteraction = remember { MutableInteractionSource() }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = timerTapInteraction,
                        indication = null
                    ) {
                        val tapAt = SystemClock.elapsedRealtime()
                        if (tapAt - lastTapAt > 3_000L) tapCount = 0
                        lastTapAt = tapAt
                        tapCount += 1
                        if (tapCount >= 7) {
                            tapCount = 0
                            onTapExit()
                        }
                    },
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFFFFEFA).copy(alpha = .88f)
                ),
                shape = RoundedCornerShape(30.dp),
                border = BorderStroke(
                    1.dp,
                    Color.White.copy(alpha = .74f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color(0xFFE7F3E2).copy(alpha = .94f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_pauza_leaf),
                            contentDescription = null,
                            modifier = Modifier.size(30.dp)
                        )
                    }

                    Spacer(Modifier.width(17.dp))

                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            "Осталось",
                            color = Color(0xFF667067),
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = formatRemainingForLauncher(remaining),
                            color = Color(0xFF111713),
                            fontSize = when {
                                remaining >= 86_400_000L -> 31.sp
                                remaining >= 3_600_000L -> 38.sp
                                else -> 42.sp
                            },
                            lineHeight = 44.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(Modifier.height(28.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
                contentPadding = PaddingValues(top = 2.dp, bottom = 22.dp)
            ) {
                items(
                    items = shortcuts,
                    key = { it.launchType.name + ":" + it.packageName }
                ) { app ->
                    LauncherAppIcon(
                        app = app,
                        onClick = { onLaunch(app) },
                        compact = true
                    )
                }
            }
        }
    }
}

@Composable
private fun LauncherAppIcon(
    app: InstalledApp,
    onClick: (() -> Unit)? = null,
    compact: Boolean = false,
) {
    val interactionModifier =
        if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier

    val tileSize = if (compact) 58.dp else 66.dp
    val iconSize = if (compact) 48.dp else 54.dp
    val radius = if (compact) 18.dp else 20.dp
    val labelSize = if (compact) 11.sp else 12.sp
    val labelLineHeight = if (compact) 13.sp else 14.sp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(interactionModifier),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(tileSize)
                .shadow(
                    if (compact) 5.dp else 7.dp,
                    RoundedCornerShape(radius),
                    ambientColor = Color.Black.copy(alpha = .08f)
                )
                .clip(RoundedCornerShape(radius))
                .background(Color(0xFFFFFEFA).copy(alpha = .90f)),
            contentAlignment = Alignment.Center
        ) {
            val bitmap = app.icon
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = app.label,
                    modifier = Modifier.size(iconSize)
                )
            } else {
                Box(
                    Modifier
                        .size(iconSize)
                        .clip(RoundedCornerShape(if (compact) 15.dp else 16.dp))
                        .background(PauseGreenSoft),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        app.label.take(1).uppercase(Locale.getDefault()),
                        color = PauseGreen,
                        fontWeight = FontWeight.Bold,
                        fontSize = if (compact) 19.sp else 22.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(if (compact) 6.dp else 7.dp))
        Text(
            text = app.label,
            fontSize = labelSize,
            lineHeight = labelLineHeight,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            color = Color(0xFF1C221E)
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
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 5.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFFF8F8F4))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIconSmall(app)
        Spacer(Modifier.width(12.dp))
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
                checkedTrackColor = Color(0xFF36B34A),
                checkedBorderColor = Color(0xFF36B34A),
                uncheckedThumbColor = PauseMuted,
                uncheckedTrackColor = MaterialTheme.colorScheme.surface,
                uncheckedBorderColor = Color(0xFFCBD0C8),
                disabledCheckedThumbColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.62f),
                disabledCheckedTrackColor = Color(0xFF36B34A).copy(alpha = 0.42f),
                disabledCheckedBorderColor = Color(0xFF36B34A).copy(alpha = 0.32f),
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
            .height(56.dp)
            .shadow(7.dp, RoundedCornerShape(19.dp), ambientColor = Color(0xFF184B34).copy(alpha = .18f))
            .clip(RoundedCornerShape(19.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(Color(0xFF1E6842), Color(0xFF45B44D))
                )
            )
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
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
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
    } else if (hours > 0) {
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

private fun formatRemaining(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
}
