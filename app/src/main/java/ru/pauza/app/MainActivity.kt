package ru.pauza.app

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsetsController
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
        configureSystemNavigation()

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

    override fun onResume() {
        super.onResume()
        configureSystemNavigation()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun configureSystemNavigation() {
        window.navigationBarColor = Color.WHITE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.setSystemBarsAppearance(
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            run {
                window.decorView.systemUiVisibility =
                    window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
        }
    }
}
