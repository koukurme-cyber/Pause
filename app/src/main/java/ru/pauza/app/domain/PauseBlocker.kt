package ru.pauza.app.domain

/**
 * Boundary for the future strict Android blocker.
 *
 * v0.1 intentionally ships with NoopPauseBlocker only. This is a safety decision:
 * UI/session logic can be tested on a primary phone without Device Owner,
 * AccessibilityService or Lock Task Mode being able to trap the user.
 */
interface PauseBlocker {
    fun start(allowedPackages: Set<String>, untilEpochMs: Long)
    fun stop()
}

class NoopPauseBlocker : PauseBlocker {
    override fun start(allowedPackages: Set<String>, untilEpochMs: Long) = Unit
    override fun stop() = Unit
}
