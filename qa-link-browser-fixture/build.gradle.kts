plugins {
    id("com.android.application")
}
android {
    namespace = "org.pauza.qa.browser"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.pauza.qa.browser"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "qa-1"
    }
}