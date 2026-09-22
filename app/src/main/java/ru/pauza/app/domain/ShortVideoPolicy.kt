package ru.pauza.app.domain

/**
 * State holder for short video blocking.
 * Keeps detection separate from navigation and app-specific actions.
 */
class ShortVideoPolicy {
    private var blocked = false

    fun update(isShortVideoSurface: Boolean): Boolean {
        if (isShortVideoSurface) {
            blocked = true
        }
        return blocked
    }

    fun reset() {
        blocked = false
    }

    fun isBlocked(): Boolean = blocked
}
