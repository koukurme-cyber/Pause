pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Pauza"
include(":app")
include(":qa-shortvideo-fixture")
include(":qa-link-origin-fixture")
include(":qa-link-browser-fixture")
