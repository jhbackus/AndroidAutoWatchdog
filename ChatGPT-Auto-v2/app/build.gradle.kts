plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val brokerTokenForBuild = (System.getenv("CAR_AI_BROKER_TOKEN") ?: "")
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")

android {
    namespace = "nl.chatgptauto.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "nl.chatgptauto.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 14
        versionName = "0.4.3"
        buildConfigField("String", "BROKER_URL", "\"wss://bilateral.netwerkers.nl/chatgpt-auto\"")
        buildConfigField("String", "BROKER_TOKEN", "\"$brokerTokenForBuild\"")
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
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-projected:1.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
