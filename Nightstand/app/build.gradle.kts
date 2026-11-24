plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nightstand"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nightstand"
        // 30: WindowInsetsController for immersive mode. Nothing older is worth
        // supporting for a personal sideloaded app aimed at a 2026 phone.
        minSdk = 30
        // 35 (Android 15). Everything this app relies on — specialUse
        // foreground service started from BOOT_COMPLETED, full-screen intent,
        // overlay-permission background launch — is allowed at 35.
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-prototype"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    testImplementation("junit:junit:4.13.2")
}
