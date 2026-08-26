plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val brokerTokenForBuild = (System.getenv("CAR_AI_BROKER_TOKEN") ?: "")
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
val ciRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "nl.chatgptauto.app"
    compileSdk = 36

    signingConfigs {
        create("carAiStable") {
            storeFile = file("../signing/car-ai-release-fixed.jks")
            storePassword = "CarAiUpdate2026!"
            keyAlias = "carai"
            keyPassword = "CarAiUpdate2026!"
        }
    }

    defaultConfig {
        applicationId = "nl.carai.androidauto"
        minSdk = 28
        targetSdk = 35
        versionCode = 3000 + ciRunNumber
        versionName = "0.5.1"
        buildConfigField("String", "BROKER_URL", "\"wss://bilateral.netwerkers.nl/chatgpt-auto\"")
        buildConfigField("String", "BROKER_TOKEN", "\"$brokerTokenForBuild\"")
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("carAiStable")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("carAiStable")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-projected:1.7.0")
    implementation("androidx.media3:media3-session:1.11.0")
    implementation("androidx.media3:media3-exoplayer:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
