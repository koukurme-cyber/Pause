package ru.pauza.app.ui

import android.app.Activity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.AnimatedVisibility
import android.app.ActivityManager
import android.app.TimePickerDialog
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
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.pauza.app.BuildConfig
import ru.pauza.app.R
import ru.pauza.app.data.InstalledAppsRepository
import ru.pauza.app.data.PauseStore
import ru.pauza.app.domain.AccessibilityPauseBlocker
import ru.pauza.app.domain.BatteryOptimizationHelper
import ru.pauza.app.domain.PauseBlocker
import ru.pauza.app.domain.NotificationSilencer
import ru.pauza.app.domain.ShortVideoDiagnostics
import ru.pauza.app.domain.UsageAccessMonitor
import ru.pauza.app.model.InstalledApp
import ru.pauza.app.ui.theme.PauseGreen
import ru.pauza.app.ui.theme.PauseGreenSoft
import ru.pauza.app.ui.theme.PauseMuted
import ru.pauza.app.ui.theme.PauseWarning
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

private enum class Screen { SETUP, SETTINGS, REVIEW, ACTIVE }
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
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var accessibilityEnabled by remember {
        mutableStateOf(AccessibilityPauseBlocker.isEnabled(context))
    }
    var usageAccessEnabled by remember {
        mutableStateOf(UsageAccessMonitor.isGranted(context))
    }
    var batteryUnrestricted by remember {
        mutableStateOf(BatteryOptimizationHelper.isUnrestricted(context))
    }
    var notificationPolicyAccessGranted by remember {
        mutableStateOf(NotificationSilencer.isPolicyAccessGranted(context))
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
            notificationPolicyAccessGranted = NotificationSilencer.isPolicyAccessGranted(context)
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
    var manualSelected by remember { mutableStateOf(store.manualSelectedPackages) }
    var savedSets by remember { mutableStateOf(store.savedAppSets) }
    var activeSavedSetName by remember { mutableStateOf(store.activeSavedSetName) }
    var duration by remember { mutableStateOf(PauseDuration()) }
    var testModeEnabled by remember { mutableStateOf(store.testModeEnabled) }
    var blockShortVideos by remember { mutableStateOf(store.blockShortVideos) }
    var startVibrationEnabled by remember { mutableStateOf(store.startVibrationEnabled) }
    var suppressNotificationsEnabled by remember { mutableStateOf(store.suppressNotificationsEnabled) }

    val now = System.currentTimeMillis()
    var sessionEnd by remember {
        mutableLongStateOf(store.sessionEndEpochMs.takeIf { it > now } ?: 0L)
    }
    var screen by remember {
        mutableStateOf(if (sessionEnd > now) Screen.ACTIVE else Screen.SETUP)
    }

    LaunchedEffect(Unit) {
        while (true) {
            val storedEnd = store.sessionEndEpochMs
            if (storedEnd > System.currentTimeMillis() && storedEnd != sessionEnd) {
                selected = store.selectedPackages
                activeSavedSetName = store.activeSavedSetName
                sessionEnd = storedEnd
                screen = Screen.ACTIVE
            }
            delay(500)
        }
    }

    LaunchedEffect(Unit) {
        if (sessionEnd > System.currentTimeMillis()) {
            NotificationSilencer.applyForPause(context, store)
            val restored = withContext(Dispatchers.IO) {
                appsRepository.loadAppsByPackages(selected) to appsRepository.loadAlwaysAllowedApps()
            }
            apps = restored.first
            alwaysApps = restored.second
            loading = false
        }

        if (sessionEnd <= System.currentTimeMillis()) {
            NotificationSilencer.restoreAfterPause(context, store)
        }

        val loaded = withContext(Dispatchers.IO) {
            appsRepository.loadLaunchableApps() to appsRepository.loadAlwaysAllowedApps()
        }
        apps = loaded.first
        alwaysApps = loaded.second

        val validPackages = loaded.first.map { it.packageName }.toSet()
        val cleanedSelected = selected.intersect(validPackages)
        if (cleanedSelected != selected) {
            selected = cleanedSelected
            store.selectedPackages = cleanedSelected
        }

        val cleanedManualSelected = manualSelected.intersect(validPackages)
        if (cleanedManualSelected != manualSelected) {
            manualSelected = cleanedManualSelected
            store.manualSelectedPackages = cleanedManualSelected
        }

        val activeSavedSet = savedSets.firstOrNull {
            it.name == activeSavedSetName
        }
        if (
            activeSavedSetName != null &&
            (
                activeSavedSet == null ||
                    activeSavedSet.packages.intersect(validPackages) != cleanedSelected
            )
        ) {
            activeSavedSetName = null
            store.activeSavedSetName = null
            selected = cleanedManualSelected
            store.selectedPackages = cleanedManualSelected
        }

        loading = false
    }

    val currentScreen by rememberUpdatedState(screen)
    val currentSelected by rememberUpdatedState(selected)

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && currentScreen != Screen.ACTIVE) {
                scope.launch {
                    val loaded = withContext(Dispatchers.IO) {
                        appsRepository.loadLaunchableApps() to appsRepository.loadAlwaysAllowedApps()
                    }
                    apps = loaded.first
                    alwaysApps = loaded.second

                    val validPackages = loaded.first.map { it.packageName }.toSet()
                    val cleanedSelected = currentSelected.intersect(validPackages)
                    if (cleanedSelected != currentSelected) {
                        selected = cleanedSelected
                        store.selectedPackages = cleanedSelected
                    }

                    loading = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    when (screen) {
        Screen.SETUP -> SetupScreen(
            apps = apps,
            alwaysApps = alwaysApps,
            loading = loading,
            selected = selected,
            savedSets = savedSets,
            activeSavedSetName = activeSavedSetName,
            duration = duration,
            testModeEnabled = testModeEnabled,
            onToggle = { pkg, enabled ->
                val nextSelected = if (enabled) selected + pkg else selected - pkg
                selected = nextSelected
                store.selectedPackages = nextSelected
                manualSelected = nextSelected
                store.manualSelectedPackages = nextSelected
                activeSavedSetName = null
                store.activeSavedSetName = null
            },
            onDuration = { duration = it },
            onTestModeChanged = { enabled ->
                testModeEnabled = enabled
                store.testModeEnabled = enabled
            },
            onApplySet = { index ->
                val savedSet = savedSets.getOrNull(index)
                if (savedSet != null) {
                    val installedPackages = apps.map { it.packageName }.toSet()
                    selected = savedSet.packages.intersect(installedPackages)
                    store.selectedPackages = selected
                    activeSavedSetName = savedSet.name
                    store.activeSavedSetName = savedSet.name
                }
            },
            onClearSet = {
                val installedPackages = apps.map { it.packageName }.toSet()
                val restoredManual = manualSelected.intersect(installedPackages)
                manualSelected = restoredManual
                store.manualSelectedPackages = restoredManual
                selected = restoredManual
                store.selectedPackages = restoredManual
                activeSavedSetName = null
                store.activeSavedSetName = null
            },
            onOpenSettings = { screen = Screen.SETTINGS },
            onContinue = { screen = Screen.REVIEW },
        )

        Screen.SETTINGS -> SettingsScreen(
            apps = apps,
            alwaysApps = alwaysApps,
            savedSets = savedSets,
            activeSavedSetName = activeSavedSetName,
            onUpsertSet = { index, name, packages, schedules ->
                val normalizedName =
                    name.trim().take(PauseStore.MAX_SAVED_APP_SET_NAME_LENGTH)
                if (index == null) {
                    if (savedSets.size < PauseStore.MAX_SAVED_APP_SETS) {
                        savedSets = savedSets + PauseStore.SavedAppSet(
                            name = normalizedName,
                            packages = packages,
                            schedules = schedules,
                        )
                        store.savedAppSets = savedSets
                    }
                } else if (index in savedSets.indices) {
                    val previous = savedSets[index]
                    val updated = PauseStore.SavedAppSet(
                        name = normalizedName,
                        packages = packages,
                        schedules = schedules,
                    )
                    savedSets = savedSets.toMutableList().also {
                        it[index] = updated
                    }
                    store.savedAppSets = savedSets

                    if (activeSavedSetName == previous.name) {
                        activeSavedSetName = updated.name
                        store.activeSavedSetName = updated.name

                        val installedPackages = apps.map { it.packageName }.toSet()
                        selected = updated.packages.intersect(installedPackages)
                        store.selectedPackages = selected
                    }
                }
            },
            onDeleteSet = { index ->
                if (index in savedSets.indices) {
                    val removed = savedSets[index]
                    savedSets = savedSets.toMutableList().also { it.removeAt(index) }
                    store.savedAppSets = savedSets
                    if (activeSavedSetName == removed.name) {
                        val installedPackages = apps.map { it.packageName }.toSet()
                        val restoredManual = manualSelected.intersect(installedPackages)
                        manualSelected = restoredManual
                        store.manualSelectedPackages = restoredManual
                        selected = restoredManual
                        store.selectedPackages = restoredManual
                        activeSavedSetName = null
                        store.activeSavedSetName = null
                    }
                }
            },
            blockShortVideos = blockShortVideos,
            onBlockShortVideosChanged = { enabled ->
                blockShortVideos = enabled
                store.blockShortVideos = enabled
            },
            startVibrationEnabled = startVibrationEnabled,
            onStartVibrationChanged = { enabled ->
                startVibrationEnabled = enabled
                store.startVibrationEnabled = enabled
            },
            suppressNotificationsEnabled = suppressNotificationsEnabled,
            notificationPolicyAccessGranted = notificationPolicyAccessGranted,
            onSuppressNotificationsChanged = { enabled ->
                suppressNotificationsEnabled = enabled
                store.suppressNotificationsEnabled = enabled
                if (enabled && !NotificationSilencer.isPolicyAccessGranted(context)) {
                    NotificationSilencer.openPolicyAccessSettings(context)
                }
            },
            onOpenNotificationPolicyAccess = {
                NotificationSilencer.openPolicyAccessSettings(context)
            },
            onBack = { screen = Screen.SETUP },
        )

        Screen.REVIEW -> ReviewScreen(
            apps = apps,
            alwaysApps = alwaysApps,
            selected = selected,
            duration = duration,
            onOpenSettings = { screen = Screen.SETTINGS },
            onBack = { screen = Screen.SETUP },
            onStart = {
                sessionEnd = System.currentTimeMillis() + duration.totalMinutes * 60_000L
                store.selectedPackages = selected
                store.sessionEndEpochMs = sessionEnd
                blocker.start(
                    selected + appsRepository.alwaysAllowedPackages(),
                    sessionEnd
                )
                if (startVibrationEnabled) {
                    vibratePauseStart(context)
                }
                NotificationSilencer.applyForPause(context, store)
                screen = Screen.ACTIVE
            }
        )

        Screen.ACTIVE -> ActiveScreen(
            apps = apps,
            alwaysApps = alwaysApps,
            selected = selected,
            sessionEnd = sessionEnd,
            testModeEnabled = testModeEnabled,
            onLaunch = appsRepository::launch,
            onFinished = {
                blocker.stop()
                NotificationSilencer.restoreAfterPause(context, store)
                store.clearSession()
                sessionEnd = 0L
                screen = Screen.SETUP
            },
            onTapExit = {
                blocker.stop()
                NotificationSilencer.restoreAfterPause(context, store)
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
    subtitle: String = "Только нужное",
    onTitleTap: (() -> Unit)? = null,
) {
    val titleTapInteraction = remember { MutableInteractionSource() }

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
                modifier = if (onTitleTap != null) {
                    Modifier.clickable(
                        interactionSource = titleTapInteraction,
                        indication = null,
                        onClick = onTitleTap
                    )
                } else {
                    Modifier
                },
                fontSize = if (compact) 27.sp else 34.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                subtitle,
                fontSize = if (compact) 12.sp else 14.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.6.sp,
                color = Color(0xFF4F6657)
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
                    text = "На разных оболочках Android предоставление этого разрешения устроено по-разному: название пункта и путь к нему могут отличаться. На HyperOS: настройки приложения «Пауза» → меню ⋮ → «Разрешить запрещённые настройки». Если такого пункта нет, вернитесь и подтвердите шаг.",
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
                    shape = RoundedCornerShape(13.dp),
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
    savedSets: List<PauseStore.SavedAppSet>,
    activeSavedSetName: String?,
    duration: PauseDuration,
    testModeEnabled: Boolean,
    onToggle: (String, Boolean) -> Unit,
    onDuration: (PauseDuration) -> Unit,
    onTestModeChanged: (Boolean) -> Unit,
    onApplySet: (Int) -> Unit,
    onClearSet: () -> Unit,
    onOpenSettings: () -> Unit,
    onContinue: () -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    var hiddenModeTapCount by rememberSaveable { mutableIntStateOf(0) }
    val appListState = rememberLazyListState()
    val allApps = remember(apps, alwaysApps) { alwaysApps + apps }
    val filteredApps = remember(allApps, searchQuery) {
        val query = searchQuery.trim()
        if (query.isBlank()) {
            allApps
        } else {
            allApps.filter { it.label.contains(query, ignoreCase = true) }
        }
    }
    val activeSavedSetIndex = remember(savedSets, activeSavedSetName) {
        savedSets.indexOfFirst { it.name == activeSavedSetName }
    }
    val collapseThresholdPx = with(LocalDensity.current) {
        56.dp.roundToPx()
    }
    val listExpanded by remember(appListState, collapseThresholdPx) {
        derivedStateOf {
            appListState.firstVisibleItemIndex > 0 ||
                appListState.firstVisibleItemScrollOffset > collapseThresholdPx
        }
    }

    LaunchedEffect(searchQuery) {
        if (appListState.firstVisibleItemIndex > 0 ||
            appListState.firstVisibleItemScrollOffset > 0
        ) {
            appListState.scrollToItem(0)
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BrandHeader(
                    modifier = Modifier.weight(1f),
                    compact = true,
                    subtitle = if (testModeEnabled) "Тестовый режим" else "Только нужное",
                    onTitleTap = {
                        hiddenModeTapCount += 1
                        if (hiddenModeTapCount >= 20) {
                            hiddenModeTapCount = 0
                            onTestModeChanged(!testModeEnabled)
                        }
                    }
                )
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_settings),
                        contentDescription = "Настройки",
                        tint = Color(0xFF263029),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            AnimatedVisibility(
                visible = !listExpanded,
                enter = slideInVertically(
                    initialOffsetY = { fullHeight -> -fullHeight },
                    animationSpec = tween(durationMillis = 520)
                ) + expandVertically(
                    expandFrom = Alignment.Top,
                    animationSpec = tween(durationMillis = 520)
                ),
                exit = slideOutVertically(
                    targetOffsetY = { fullHeight -> -fullHeight },
                    animationSpec = tween(durationMillis = 520)
                ) + shrinkVertically(
                    shrinkTowards = Alignment.Top,
                    animationSpec = tween(durationMillis = 520)
                )
            ) {
                Column {
                    Spacer(Modifier.height(7.dp))
                    Text(
                        "Пауза помогает на время убрать лишнее. Выберите приложения, которые должны остаться доступными, и срок — до окончания таймера всё остальное будет заблокировано.",
                        color = Color(0xFF737A75),
                        fontSize = 14.sp,
                        lineHeight = 19.sp
                    )

                    Spacer(Modifier.height(10.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFFFFEFA).copy(alpha = .94f)
                        ),
                        shape = RoundedCornerShape(26.dp),
                        border = BorderStroke(1.dp, Color(0xFFDCE3D9))
                    ) {
                        Column(
                            Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Text(
                                "Длительность паузы",
                                color = Color(0xFF184F35),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(5.dp))
                            DurationPicker(
                                duration = duration,
                                onChange = onDuration
                            )
                        }
                    }

                }
            }

            if (savedSets.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                SavedSetSelector(
                    savedSets = savedSets,
                    activeSetIndex = activeSavedSetIndex,
                    onApply = { index ->
                        onApplySet(index)
                        searchQuery = ""
                    },
                    onClear = onClearSet,
                )
            }

            Spacer(Modifier.height(8.dp))
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
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFFFFEFA).copy(alpha = .92f)
                ),
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
                        state = appListState,
                        modifier = Modifier.fillMaxSize(),
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .shadow(
                        7.dp,
                        RoundedCornerShape(19.dp),
                        ambientColor = Color(0xFF184B34).copy(alpha = .18f)
                    )
                    .clip(RoundedCornerShape(19.dp))
                    .background(
                        if (duration.isValid) {
                            Brush.horizontalGradient(
                                listOf(Color(0xFF1E6842), Color(0xFF45B44D))
                            )
                        } else {
                            Brush.horizontalGradient(
                                listOf(Color(0xFFD9DCD6), Color(0xFFD9DCD6))
                            )
                        }
                    )
                    .clickable(enabled = duration.isValid, onClick = onContinue),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Продолжить",
                    color = if (duration.isValid) Color.White else Color(0xFF9A9C98),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
            }
        }

    }
}

@Composable
private fun SavedSetSelector(
    savedSets: List<PauseStore.SavedAppSet>,
    activeSetIndex: Int,
    onApply: (Int) -> Unit,
    onClear: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val activeName = savedSets.getOrNull(activeSetIndex)?.name

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFFFFEFA).copy(alpha = .96f)
        ),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Color(0xFFD7DBD5))
    ) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = true }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Набор",
                    color = PauseMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.width(58.dp)
                )
                Text(
                    activeName ?: "Без набора",
                    color = if (activeName != null) {
                        Color(0xFF1F6B3A)
                    } else {
                        Color(0xFF505550)
                    },
                    fontSize = 14.sp,
                    fontWeight = if (activeName != null) {
                        FontWeight.SemiBold
                    } else {
                        FontWeight.Medium
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "⌄",
                    color = PauseMuted,
                    fontSize = 20.sp,
                    lineHeight = 20.sp
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.fillMaxWidth(.82f)
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            "Без набора",
                            fontWeight = if (activeName == null) {
                                FontWeight.SemiBold
                            } else {
                                FontWeight.Normal
                            }
                        )
                    },
                    leadingIcon = {
                        if (activeName == null) {
                            Text("✓", color = Color(0xFF2D7A45))
                        }
                    },
                    onClick = {
                        onClear()
                        expanded = false
                    }
                )

                savedSets.forEachIndexed { index, savedSet ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                savedSet.name,
                                fontWeight = if (index == activeSetIndex) {
                                    FontWeight.SemiBold
                                } else {
                                    FontWeight.Normal
                                }
                            )
                        },
                        leadingIcon = {
                            if (index == activeSetIndex) {
                                Text("✓", color = Color(0xFF2D7A45))
                            }
                        },
                        onClick = {
                            onApply(index)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SavedSetsSettingsCard(
    savedSets: List<PauseStore.SavedAppSet>,
    activeSavedSetName: String?,
    onCreate: () -> Unit,
    onEdit: (Int) -> Unit,
    onDelete: (Int) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFFFFEFA).copy(alpha = .94f)
        ),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color(0xFFDCE3D9))
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Наборы приложений",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "Здесь можно создать свой набор разрешённых приложений для Паузы.",
                        color = PauseMuted,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
                if (savedSets.size < PauseStore.MAX_SAVED_APP_SETS) {
                    TextButton(onClick = onCreate) {
                        Text("Создать")
                    }
                }
            }

            if (savedSets.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                savedSets.forEachIndexed { index, savedSet ->
                    val isActive = savedSet.name == activeSavedSetName
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (isActive) {
                            Color(0xFFF0F7EE)
                        } else {
                            Color(0xFFF5F5F2)
                        },
                        border = BorderStroke(
                            1.dp,
                            if (isActive) Color(0xFFBBD8BE) else Color(0xFFDDDFDB)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onEdit(index) }
                                .padding(start = 12.dp, top = 7.dp, bottom = 7.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (isActive) "✓ ${savedSet.name}" else savedSet.name,
                                    color = if (isActive) {
                                        Color(0xFF245E39)
                                    } else {
                                        Color(0xFF424642)
                                    },
                                    fontSize = 14.sp,
                                    fontWeight = if (isActive) {
                                        FontWeight.SemiBold
                                    } else {
                                        FontWeight.Medium
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (savedSet.schedules.isNotEmpty()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        scheduleListSummary(savedSet.schedules),
                                        color = PauseMuted,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            IconButton(
                                onClick = { onDelete(index) },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Text(
                                    "×",
                                    color = PauseMuted,
                                    fontSize = 19.sp
                                )
                            }
                        }
                    }

                    if (index != savedSets.lastIndex) {
                        Spacer(Modifier.height(7.dp))
                    }
                }
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
                .height(32.dp)
                .clip(RoundedCornerShape(16.dp))
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

}

@Composable
private fun DurationWheel(
    value: Int,
    max: Int,
    modifier: Modifier = Modifier,
    formatter: (Int) -> String,
    onValueChange: (Int) -> Unit,
) {
    val valueCount = max + 1
    val loopBase = remember(max) {
        val middle = Int.MAX_VALUE / 2
        middle - Math.floorMod(middle, valueCount)
    }
    val initialFirstVisible = loopBase + value.coerceIn(0, max) - 1

    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialFirstVisible
    )
    val latestValue by rememberUpdatedState(value)
    val latestOnValueChange by rememberUpdatedState(onValueChange)

    val centeredItemIndex by remember(listState) {
        derivedStateOf {
            val layout = listState.layoutInfo
            val visible = layout.visibleItemsInfo
            if (visible.isEmpty()) {
                loopBase + latestValue.coerceIn(0, max)
            } else {
                val viewportCenter =
                    (layout.viewportStartOffset + layout.viewportEndOffset) / 2
                visible.minByOrNull { item ->
                    abs((item.offset + item.size / 2) - viewportCenter)
                }?.index ?: (loopBase + latestValue.coerceIn(0, max))
            }
        }
    }

    val centeredValue by remember(centeredItemIndex, loopBase, valueCount) {
        derivedStateOf {
            Math.floorMod(centeredItemIndex - loopBase, valueCount)
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (!scrolling && listState.layoutInfo.visibleItemsInfo.isNotEmpty()) {
                    val layout = listState.layoutInfo
                    val viewportCenter =
                        (layout.viewportStartOffset + layout.viewportEndOffset) / 2
                    val nearestIndex = layout.visibleItemsInfo.minByOrNull { item ->
                        abs((item.offset + item.size / 2) - viewportCenter)
                    }?.index ?: centeredItemIndex

                    val targetFirstIndex =
                        (nearestIndex - 1).coerceIn(0, Int.MAX_VALUE - 3)

                    if (
                        listState.firstVisibleItemIndex != targetFirstIndex ||
                        listState.firstVisibleItemScrollOffset != 0
                    ) {
                        listState.animateScrollToItem(targetFirstIndex)
                    }

                    val targetValue =
                        Math.floorMod(nearestIndex - loopBase, valueCount)
                    if (targetValue != latestValue) {
                        latestOnValueChange(targetValue)
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
            count = Int.MAX_VALUE,
            key = { it }
        ) { listIndex ->
            val actualValue = Math.floorMod(listIndex - loopBase, valueCount)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(30.dp),
                contentAlignment = Alignment.Center
            ) {
                val distance = abs(listIndex - centeredItemIndex)
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
                    maxLines = 1,
                    lineHeight = when (distance) {
                        0 -> 18.sp
                        1 -> 13.sp
                        else -> 10.sp
                    },
                    style = TextStyle(
                        platformStyle = PlatformTextStyle(
                            includeFontPadding = false
                        )
                    )
                )
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
private fun SavedSetEditorScreen(
    apps: List<InstalledApp>,
    alwaysApps: List<InstalledApp>,
    initialName: String,
    initialPackages: Set<String>,
    initialSchedules: List<PauseStore.SavedSetSchedule>,
    existingNames: List<String>,
    isNew: Boolean,
    onSave: (String, Set<String>, List<PauseStore.SavedSetSchedule>) -> Unit,
    onBack: () -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var schedules by remember(initialSchedules) { mutableStateOf(initialSchedules) }
    var scheduleEditorOpen by remember { mutableStateOf(false) }

    if (scheduleEditorOpen) {
        SavedSetScheduleScreen(
            setName = name.trim().ifBlank { "Набор" },
            schedules = schedules,
            onSchedulesChange = { schedules = it },
            onBack = { scheduleEditorOpen = false },
        )
        return
    }

    BackHandler(onBack = onBack)
    var selectedPackages by remember(initialPackages) {
        mutableStateOf(initialPackages.intersect(apps.map { it.packageName }.toSet()))
    }
    var searchQuery by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val allApps = remember(apps, alwaysApps) { alwaysApps + apps }

    val filteredApps = remember(allApps, searchQuery) {
        val query = searchQuery.trim()
        if (query.isBlank()) {
            allApps
        } else {
            allApps.filter { it.label.contains(query, ignoreCase = true) }
        }
    }

    val normalizedName = name.trim()
    val duplicateName = existingNames.any {
        it.equals(normalizedName, ignoreCase = true)
    }
    val canSave = normalizedName.isNotBlank() && !duplicateName

    SoftScreenBackground {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onBack,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                ) {
                    Text(
                        "‹",
                        fontSize = 34.sp,
                        lineHeight = 34.sp,
                        color = Color(0xFF4F5952)
                    )
                }
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (isNew) "Новый набор" else "Изменить набор",
                        fontSize = 25.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        "Телефон и Сообщения всегда доступны отдельно",
                        color = PauseMuted,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it.take(PauseStore.MAX_SAVED_APP_SET_NAME_LENGTH)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Название набора") },
                placeholder = { Text("Например, Работа") },
                singleLine = true,
                isError = duplicateName,
                supportingText = {
                    when {
                        duplicateName -> Text("Такое название уже есть")
                        else -> Text(
                            "${name.length}/${PauseStore.MAX_SAVED_APP_SET_NAME_LENGTH}"
                        )
                    }
                },
                shape = RoundedCornerShape(17.dp)
            )

            Spacer(Modifier.height(6.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { scheduleEditorOpen = true },
                shape = RoundedCornerShape(17.dp),
                color = Color(0xFFFFFEFA),
                border = BorderStroke(1.dp, Color(0xFFD9DDD5))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Расписание",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            scheduleListSummary(schedules),
                            color = PauseMuted,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                    Text("›", color = PauseMuted, fontSize = 24.sp)
                }
            }

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Найти приложение") },
                singleLine = true,
                shape = RoundedCornerShape(17.dp),
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        TextButton(onClick = { searchQuery = "" }) {
                            Text("×", fontSize = 21.sp)
                        }
                    }
                }
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .height(30.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Выбрано: ${selectedPackages.size}",
                    color = PauseMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                if (selectedPackages.isNotEmpty()) {
                    TextButton(onClick = { selectedPackages = emptySet() }) {
                        Text("Снять все", fontSize = 12.sp)
                    }
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFFFFEFA).copy(alpha = .92f)
                ),
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(0.dp, Color.Transparent)
            ) {
                if (filteredApps.isEmpty()) {
                    Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Ничего не найдено", color = PauseMuted)
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        items(
                            items = filteredApps,
                            key = { "set-editor:" + it.launchType.name + ":" + it.packageName }
                        ) { app ->
                            val locked = app in alwaysApps
                            AppRow(
                                app = app,
                                checked = locked || app.packageName in selectedPackages,
                                locked = locked,
                                onChecked = { enabled ->
                                    if (!locked) {
                                        selectedPackages =
                                            if (enabled) {
                                                selectedPackages + app.packageName
                                            } else {
                                                selectedPackages - app.packageName
                                            }
                                    }
                                }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            Button(
                onClick = {
                    onSave(normalizedName, selectedPackages, schedules)
                },
                enabled = canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF17633D),
                    disabledContainerColor = Color(0xFFD9DCD6),
                )
            ) {
                Text(
                    if (isNew) "Создать набор" else "Сохранить изменения",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
            }
        }
    }
}


private fun scheduleListSummary(
    schedules: List<PauseStore.SavedSetSchedule>,
): String {
    if (schedules.isEmpty()) return "Не настроено"
    val enabled = schedules.count { it.enabled }
    return when {
        enabled == 0 -> "Все правила выключены"
        schedules.size == 1 -> {
            val schedule = schedules.first()
            "${formatScheduleDays(schedule.daysOfWeek)}, " +
                "${formatScheduleTime(schedule.startMinuteOfDay)} · " +
                "${formatScheduleDuration(schedule.durationMinutes)}" +
                if (schedule.enabled) "" else " · выключено"
        }
        else -> "${schedules.size} правил · включено ${enabled}"
    }
}

private fun formatScheduleDays(days: Set<Int>): String = when (days) {
    setOf(1, 2, 3, 4, 5, 6, 7) -> "Каждый день"
    setOf(1, 2, 3, 4, 5) -> "Пн–Пт"
    setOf(6, 7) -> "Сб–Вс"
    else -> {
        val names = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
        days.sorted().joinToString(", ") { names.getOrElse(it - 1) { "?" } }
    }
}

private fun formatScheduleTime(startMinuteOfDay: Int): String =
    String.format(
        Locale.US,
        "%02d:%02d",
        startMinuteOfDay.coerceIn(0, 1439) / 60,
        startMinuteOfDay.coerceIn(0, 1439) % 60,
    )

private fun formatScheduleDuration(totalMinutes: Int): String {
    val minutes = totalMinutes.coerceAtLeast(1)
    val days = minutes / (24 * 60)
    val hours = (minutes % (24 * 60)) / 60
    val restMinutes = minutes % 60
    return buildList {
        if (days > 0) add("$days д")
        if (hours > 0) add("$hours ч")
        if (restMinutes > 0 || isEmpty()) add("$restMinutes мин")
    }.joinToString(" ")
}

@Composable
private fun SavedSetScheduleScreen(
    setName: String,
    schedules: List<PauseStore.SavedSetSchedule>,
    onSchedulesChange: (List<PauseStore.SavedSetSchedule>) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    var durationRuleIndex by remember { mutableStateOf<Int?>(null) }

    durationRuleIndex?.let { index ->
        schedules.getOrNull(index)?.let { schedule ->
            ScheduleDurationDialog(
                durationMinutes = schedule.durationMinutes,
                onDismiss = { durationRuleIndex = null },
                onConfirm = { durationMinutes ->
                    onSchedulesChange(
                        schedules.toMutableList().also {
                            if (index in it.indices) {
                                it[index] = it[index].copy(durationMinutes = durationMinutes)
                            }
                        }
                    )
                    durationRuleIndex = null
                }
            )
        } ?: run {
            durationRuleIndex = null
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onBack,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                ) {
                    Text(
                        "‹",
                        fontSize = 34.sp,
                        lineHeight = 34.sp,
                        color = Color(0xFF4F5952)
                    )
                }
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "Расписание",
                        fontSize = 25.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        setName,
                        color = PauseMuted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "Набор будет включаться автоматически. Если в этот момент уже идёт другая Пауза, она не будет заменена.",
                color = PauseMuted,
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
            Spacer(Modifier.height(10.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                schedules.forEachIndexed { index, schedule ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFFFFFEFA).copy(alpha = .95f)
                        ),
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, Color(0xFFDCE3D9))
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Правило ${index + 1}",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = schedule.enabled,
                                    onCheckedChange = { enabled ->
                                        onSchedulesChange(
                                            schedules.toMutableList().also {
                                                it[index] = schedule.copy(enabled = enabled)
                                            }
                                        )
                                    }
                                )
                                IconButton(
                                    onClick = {
                                        onSchedulesChange(
                                            schedules.toMutableList().also {
                                                it.removeAt(index)
                                            }
                                        )
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Text("×", color = PauseMuted, fontSize = 20.sp)
                                }
                            }

                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Дни",
                                color = PauseMuted,
                                fontSize = 12.sp
                            )
                            Spacer(Modifier.height(5.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                val dayNames = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")
                                dayNames.forEachIndexed { dayIndex, label ->
                                    val day = dayIndex + 1
                                    val selected = day in schedule.daysOfWeek
                                    Surface(
                                        modifier = Modifier
                                            .size(width = 38.dp, height = 32.dp)
                                            .clickable {
                                                val nextDays =
                                                    if (selected) {
                                                        if (schedule.daysOfWeek.size <= 1) {
                                                            schedule.daysOfWeek
                                                        } else {
                                                            schedule.daysOfWeek - day
                                                        }
                                                    } else {
                                                        schedule.daysOfWeek + day
                                                    }
                                                onSchedulesChange(
                                                    schedules.toMutableList().also {
                                                        it[index] = schedule.copy(
                                                            daysOfWeek = nextDays
                                                        )
                                                    }
                                                )
                                            },
                                        shape = RoundedCornerShape(11.dp),
                                        color = if (selected) {
                                            Color(0xFFE0F2E2)
                                        } else {
                                            Color(0xFFF2F3F0)
                                        },
                                        border = BorderStroke(
                                            1.dp,
                                            if (selected) {
                                                Color(0xFF8FC39A)
                                            } else {
                                                Color(0xFFD9DDD5)
                                            }
                                        )
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                label,
                                                color = if (selected) {
                                                    Color(0xFF245E39)
                                                } else {
                                                    Color(0xFF626762)
                                                },
                                                fontSize = 11.sp,
                                                fontWeight = if (selected) {
                                                    FontWeight.SemiBold
                                                } else {
                                                    FontWeight.Normal
                                                }
                                            )
                                        }
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                TextButton(
                                    onClick = {
                                        onSchedulesChange(
                                            schedules.toMutableList().also {
                                                it[index] = schedule.copy(
                                                    daysOfWeek = setOf(1, 2, 3, 4, 5)
                                                )
                                            }
                                        )
                                    }
                                ) { Text("Будни", fontSize = 11.sp) }
                                TextButton(
                                    onClick = {
                                        onSchedulesChange(
                                            schedules.toMutableList().also {
                                                it[index] = schedule.copy(
                                                    daysOfWeek = setOf(6, 7)
                                                )
                                            }
                                        )
                                    }
                                ) { Text("Выходные", fontSize = 11.sp) }
                                TextButton(
                                    onClick = {
                                        onSchedulesChange(
                                            schedules.toMutableList().also {
                                                it[index] = schedule.copy(
                                                    daysOfWeek = (1..7).toSet()
                                                )
                                            }
                                        )
                                    }
                                ) { Text("Все дни", fontSize = 11.sp) }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        TimePickerDialog(
                                            context,
                                            { _, hour, minute ->
                                                onSchedulesChange(
                                                    schedules.toMutableList().also {
                                                        it[index] = schedule.copy(
                                                            startMinuteOfDay = hour * 60 + minute
                                                        )
                                                    }
                                                )
                                            },
                                            schedule.startMinuteOfDay / 60,
                                            schedule.startMinuteOfDay % 60,
                                            true,
                                        ).show()
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Text(
                                        "Начало " + formatScheduleTime(
                                            schedule.startMinuteOfDay
                                        ),
                                        fontSize = 12.sp
                                    )
                                }
                                OutlinedButton(
                                    onClick = { durationRuleIndex = index },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Text(
                                        "На " + formatScheduleDuration(
                                            schedule.durationMinutes
                                        ),
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }

                if (schedules.size < PauseStore.MAX_SCHEDULES_PER_SET) {
                    OutlinedButton(
                        onClick = {
                            onSchedulesChange(
                                schedules + PauseStore.SavedSetSchedule(
                                    id = UUID.randomUUID().toString(),
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("Добавить правило")
                    }
                }

                if (schedules.isEmpty()) {
                    Text(
                        "Расписание не настроено. Набор можно запускать только вручную.",
                        color = PauseMuted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF17633D)
                )
            ) {
                Text("Готово", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ScheduleDurationDialog(
    durationMinutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val initial = durationMinutes.coerceIn(1, PauseStore.MAX_SCHEDULE_DURATION_MINUTES)
    var duration by remember(initial) {
        mutableStateOf(
            PauseDuration(
                days = initial / (24 * 60),
                hours = (initial % (24 * 60)) / 60,
                minutes = initial % 60,
            )
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Длительность Паузы") },
        text = {
            Column {
                Text(
                    "После этого времени доступ восстановится автоматически.",
                    color = PauseMuted,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(8.dp))
                DurationPicker(
                    duration = duration,
                    onChange = { duration = it }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val value = duration.totalMinutes
                        .coerceIn(1L, PauseStore.MAX_SCHEDULE_DURATION_MINUTES.toLong())
                        .toInt()
                    onConfirm(value)
                }
            ) {
                Text("Готово")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

@Composable
private fun SettingsScreen(
    apps: List<InstalledApp>,
    alwaysApps: List<InstalledApp>,
    savedSets: List<PauseStore.SavedAppSet>,
    activeSavedSetName: String?,
    onUpsertSet: (
        Int?,
        String,
        Set<String>,
        List<PauseStore.SavedSetSchedule>,
    ) -> Unit,
    onDeleteSet: (Int) -> Unit,
    blockShortVideos: Boolean,
    onBlockShortVideosChanged: (Boolean) -> Unit,
    startVibrationEnabled: Boolean,
    onStartVibrationChanged: (Boolean) -> Unit,
    suppressNotificationsEnabled: Boolean,
    notificationPolicyAccessGranted: Boolean,
    onSuppressNotificationsChanged: (Boolean) -> Unit,
    onOpenNotificationPolicyAccess: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var editingSetIndex by remember { mutableStateOf<Int?>(null) }
    var creatingSet by remember { mutableStateOf(false) }
    var shortVideoDiagnosticsStatus by remember { mutableStateOf<String?>(null) }
    var versionTapCount by rememberSaveable { mutableIntStateOf(0) }
    var showDiagnosticsDialog by rememberSaveable { mutableStateOf(false) }

    if (creatingSet || editingSetIndex != null) {
        val editingSet = editingSetIndex?.let(savedSets::getOrNull)
        SavedSetEditorScreen(
            apps = apps,
            alwaysApps = alwaysApps,
            initialName = editingSet?.name.orEmpty(),
            initialPackages = editingSet?.packages.orEmpty(),
            initialSchedules = editingSet?.schedules.orEmpty(),
            existingNames = savedSets
                .mapIndexedNotNull { index, savedSet ->
                    if (index == editingSetIndex) null else savedSet.name
                },
            isNew = creatingSet,
            onSave = { name, packages, schedules ->
                onUpsertSet(editingSetIndex, name, packages, schedules)
                creatingSet = false
                editingSetIndex = null
            },
            onBack = {
                creatingSet = false
                editingSetIndex = null
            },
        )
        return
    }

    if (showDiagnosticsDialog) {
        AlertDialog(
            onDismissRequest = {
                showDiagnosticsDialog = false
                shortVideoDiagnosticsStatus = null
            },
            title = {
                Text("Диагностика")
            },
            text = {
                Column {
                    Text(
                        "Сохраните лог, если нужно проверить работу блокировки коротких видео.",
                        color = PauseMuted,
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )

                    shortVideoDiagnosticsStatus?.let { status ->
                        Spacer(Modifier.height(10.dp))
                        Text(
                            status,
                            color = PauseMuted,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        shortVideoDiagnosticsStatus =
                            ShortVideoDiagnostics.exportToDownloads(context)
                                ?.let { "Лог сохранён: $it" }
                                ?: "Не удалось сохранить лог"
                    }
                ) {
                    Text("Сохранить лог")
                }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = {
                            ShortVideoDiagnostics.clear(context)
                            shortVideoDiagnosticsStatus = "Лог очищен"
                        }
                    ) {
                        Text("Очистить")
                    }
                    TextButton(
                        onClick = {
                            showDiagnosticsDialog = false
                            shortVideoDiagnosticsStatus = null
                        }
                    ) {
                        Text("Закрыть")
                    }
                }
            }
        )
    }

    BackHandler(onBack = onBack)

    SoftScreenBackground {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onBack,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                ) {
                    Text(
                        "‹",
                        fontSize = 34.sp,
                        lineHeight = 34.sp,
                        color = Color(0xFF4F5952)
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    "Настройки",
                    fontSize = 27.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            Spacer(Modifier.height(12.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFFFFEFA).copy(alpha = .94f)
                ),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, Color(0xFFDCE3D9))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Вибрация при запуске",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "Короткая вибрация подтверждает, что Пауза действительно запущена.",
                            color = PauseMuted,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Switch(
                        checked = startVibrationEnabled,
                        onCheckedChange = onStartVibrationChanged,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = Color(0xFF36B34A),
                            checkedBorderColor = Color(0xFF36B34A),
                            uncheckedThumbColor = PauseMuted,
                            uncheckedTrackColor = MaterialTheme.colorScheme.surface,
                            uncheckedBorderColor = Color(0xFFCBD0C8),
                        )
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFFFFEFA).copy(alpha = .94f)
                ),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, Color(0xFFDCE3D9))
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Не беспокоить во время Паузы",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(5.dp))
                            Text(
                                "Убирает звуки и всплывающие уведомления. Сами уведомления не удаляются и останутся в шторке.",
                                color = PauseMuted,
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Switch(
                            checked = suppressNotificationsEnabled,
                            onCheckedChange = onSuppressNotificationsChanged,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = Color(0xFF36B34A),
                                checkedBorderColor = Color(0xFF36B34A),
                                uncheckedThumbColor = PauseMuted,
                                uncheckedTrackColor = MaterialTheme.colorScheme.surface,
                                uncheckedBorderColor = Color(0xFFCBD0C8),
                            )
                        )
                    }

                    if (suppressNotificationsEnabled && !notificationPolicyAccessGranted) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Нужно один раз разрешить «Паузе» управлять режимом «Не беспокоить».",
                            color = Color(0xFF8E2B22),
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onOpenNotificationPolicyAccess,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("Разрешить доступ")
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFFFFEFA).copy(alpha = .94f)
                ),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, Color(0xFFDCE3D9))
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Блокировать короткие видео",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(5.dp))
                            Text(
                                "Эксперимент. Во время Паузы пытается закрывать YouTube Shorts, Instagram Reels и короткие видео RUTUBE, если само приложение оставлено доступным.",
                                color = PauseMuted,
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Switch(
                            checked = blockShortVideos,
                            onCheckedChange = onBlockShortVideosChanged,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                                checkedTrackColor = Color(0xFF36B34A),
                                checkedBorderColor = Color(0xFF36B34A),
                                uncheckedThumbColor = PauseMuted,
                                uncheckedTrackColor = MaterialTheme.colorScheme.surface,
                                uncheckedBorderColor = Color(0xFFCBD0C8),
                            )
                        )
                    }


                }
            }

            Spacer(Modifier.height(12.dp))

            SavedSetsSettingsCard(
                savedSets = savedSets,
                activeSavedSetName = activeSavedSetName,
                onCreate = {
                    editingSetIndex = null
                    creatingSet = true
                },
                onEdit = { index ->
                    editingSetIndex = index
                    creatingSet = false
                },
                onDelete = onDeleteSet,
            )

            Spacer(Modifier.height(20.dp))

            Text(
                "Версия " + BuildConfig.VERSION_NAME,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        versionTapCount += 1
                        if (versionTapCount >= 20) {
                            versionTapCount = 0
                            shortVideoDiagnosticsStatus = null
                            showDiagnosticsDialog = true
                        }
                    }
                    .padding(vertical = 10.dp),
                color = PauseMuted,
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
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
    onOpenSettings: () -> Unit,
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BrandHeader(
                    modifier = Modifier.weight(1f),
                    compact = true
                )
                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_settings),
                        contentDescription = "Настройки",
                        tint = Color(0xFF263029),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Проверьте перед запуском",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
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
                    Row(
                        verticalAlignment = Alignment.Top
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .drawBehind {
                                    val triangle = Path().apply {
                                        moveTo(size.width / 2f, 0f)
                                        lineTo(size.width, size.height)
                                        lineTo(0f, size.height)
                                        close()
                                    }
                                    drawPath(
                                        path = triangle,
                                        color = Color(0xFFF6C543)
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "!",
                                color = Color(0xFF5C4710),
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                modifier = Modifier.padding(top = 5.dp)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "После запуска изменить время и список приложений нельзя.",
                            modifier = Modifier.weight(1f),
                            color = Color(0xFF8E2B22),
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            lineHeight = 20.sp
                        )
                    }

                    Text(
                        "Проверьте банковские, транспортные, навигационные и другие важные приложения.",
                        modifier = Modifier.fillMaxWidth(),
                        color = PauseMuted,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        textAlign = TextAlign.Start
                    )

                    Spacer(Modifier.height(4.dp))
                    Text(
                        "В некоторых приложениях во время Паузы регистрация или создание нового аккаунта могут быть недоступны.",
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFF8E2B22),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        textAlign = TextAlign.Start
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
                            LauncherAppIcon(app = app, useSystemLauncherSize = true)
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
    testModeEnabled: Boolean,
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

    val defaultFlingBehavior = ScrollableDefaults.flingBehavior()
    val gentleFlingBehavior = remember(defaultFlingBehavior) {
        object : FlingBehavior {
            override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
                return with(defaultFlingBehavior) {
                    performFling(initialVelocity * 0.5f)
                }
            }
        }
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
                .statusBarsPadding()
                .navigationBarsPadding()
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
                    .then(
                        if (testModeEnabled) {
                            Modifier.clickable(
                                interactionSource = timerTapInteraction,
                                indication = null
                            ) {
                                val tapAt = SystemClock.elapsedRealtime()
                                if (tapAt - lastTapAt > 3_000L) tapCount = 0
                                lastTapAt = tapAt
                                tapCount += 1
                                if (tapCount >= 20) {
                                    tapCount = 0
                                    onTapExit()
                                }
                            }
                        } else {
                            Modifier
                        }
                    ),
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

            Spacer(Modifier.height(16.dp))
            Text(
                "Подождите до окончания Паузы. Все остальные приложения недоступны.",
                color = Color(0xFF596359),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 18.dp)
            )
            Spacer(Modifier.height(20.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
                contentPadding = PaddingValues(top = 2.dp, bottom = 22.dp),
                flingBehavior = gentleFlingBehavior
            ) {
                items(
                    items = shortcuts,
                    key = { it.launchType.name + ":" + it.packageName }
                ) { app ->
                    LauncherAppIcon(
                        app = app,
                        onClick = { onLaunch(app) },
                        compact = true,
                        useSystemLauncherSize = true
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
    useSystemLauncherSize: Boolean = false,
) {
    val interactionModifier =
        if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier

    val context = LocalContext.current
    val density = LocalDensity.current
    val systemLauncherIconSize = remember(context, density) {
        val activityManager =
            context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as ActivityManager
        with(density) { activityManager.launcherLargeIconSize.toDp() }
    }

    val iconSize = when {
        useSystemLauncherSize -> systemLauncherIconSize
        compact -> 48.dp
        else -> 54.dp
    }
    val tileSize = when {
        useSystemLauncherSize -> iconSize + 8.dp
        compact -> 58.dp
        else -> 66.dp
    }
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
    val holdProgress = remember { Animatable(0f) }

    LaunchedEffect(pressing) {
        if (pressing) {
            holdProgress.snapTo(0f)
            holdProgress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = 2000,
                    easing = LinearEasing,
                ),
            )
            if (pressing) {
                pressing = false
                onConfirmed()
            }
        } else if (holdProgress.value > 0f) {
            holdProgress.animateTo(
                targetValue = 0f,
                animationSpec = tween(durationMillis = 160),
            )
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .shadow(
                7.dp,
                RoundedCornerShape(19.dp),
                ambientColor = Color(0xFF184B34).copy(alpha = .18f)
            )
            .clip(RoundedCornerShape(19.dp))
            .background(Color(0xFF1E6842))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressing = true
                        tryAwaitRelease()
                        pressing = false
                    }
                )
            }
    ) {
        if (pressing || holdProgress.value > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(holdProgress.value.coerceIn(0f, 1f))
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color(0xFF45B44D),
                                Color(0xFF63C85C),
                            )
                        )
                    )
            )
        }

        Text(
            "Удерживайте 2 секунды, чтобы начать",
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 16.dp)
        )
    }
}

private fun vibratePauseStart(context: android.content.Context) {
    val effect = android.os.VibrationEffect.createOneShot(
        160L,
        200
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(android.os.VibratorManager::class.java)
            ?.defaultVibrator
            ?.vibrate(effect)
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(android.os.Vibrator::class.java)
            ?.vibrate(effect)
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
