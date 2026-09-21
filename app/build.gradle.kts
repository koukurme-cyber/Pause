plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ru.pauza.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "ru.pauza.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "0.5.0"
    }

    signingConfigs {
        create("stable") {
            storeFile = file("../signing/pause-dev.jks")
            storePassword = "pause-dev-2026"
            keyAlias = "pause-dev"
            keyPassword = "pause-dev-2026"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("stable")
        }
        getByName("release") {
            signingConfig = signingConfigs.getByName("stable")
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
