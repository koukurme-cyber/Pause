package ru.pauza.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ru.pauza.app.data.InstalledAppsRepository
import ru.pauza.app.data.PauseStore
import ru.pauza.app.domain.AccessibilityPauseBlocker
import ru.pauza.app.domain.PauseAccessibilityService
import ru.pauza.app.ui.PauseRoot
import ru.pauza.app.ui.theme.PauseTheme

class MainActivity : ComponentActivity() {
    private val systemUiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (
                intent?.action == PauseAccessibilityService.ACTION_BACKGROUND_FOR_SYSTEM_UI
            ) {
                moveTaskToBack(true)
            }
        }
    }

    private var systemUiReceiverRegistered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val filter = IntentFilter(
            PauseAccessibilityService.ACTION_BACKGROUND_FOR_SYSTEM_UI
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(systemUiReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(systemUiReceiver, filter)
        }
        systemUiReceiverRegistered = true

        val store = PauseStore(this)
        val appsRepository = InstalledAppsRepository(this)
        val blocker = AccessibilityPauseBlocker(this)

        setContent {
            PauseTheme {
                PauseRoot(
                    store = store,
                    appsRepository = appsRepository,
                    blocker = blocker,
                )
            }
        }
    }

    override fun onDestroy() {
        if (systemUiReceiverRegistered) {
            runCatching { unregisterReceiver(systemUiReceiver) }
            systemUiReceiverRegistered = false
        }
        super.onDestroy()
    }
}
