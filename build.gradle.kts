plugins {
    id("com.android.application") version "9.4.0" apply false
    // AGP 9.x has built-in Kotlin support. Compose compiler must match the built-in KGP line.
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
