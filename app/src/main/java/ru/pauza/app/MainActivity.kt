package ru.pauza.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ru.pauza.app.data.InstalledAppsRepository
import ru.pauza.app.data.PauseStore
import ru.pauza.app.domain.AccessibilityPauseBlocker
import ru.pauza.app.ui.PauseRoot
import ru.pauza.app.ui.theme.PauseTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
}
