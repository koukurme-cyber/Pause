package ru.pauza.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import ru.pauza.app.data.PauseStore
import ru.pauza.app.domain.HomeRoleManager

class PauseHomeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        routeHome()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeHome()
    }

    private fun routeHome() {
        val end = PauseStore(this).sessionEndEpochMs
        val pauseActive = end > System.currentTimeMillis()

        if (pauseActive) {
            startActivity(
                Intent(this, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                }
            )
        } else {
            HomeRoleManager.launchOriginalHome(this)
        }

        finish()
    }
}
