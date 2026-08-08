plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ytmprobe"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ytmprobe"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.car.app:app:1.4.0")
    implementation("androidx.car.app:app-projected:1.4.0")
    // Launch v2 only — ItemTouchHelper properly disambiguates a row's
    // horizontal swipe from the list's vertical scroll, which the manual
    // GestureDetector approach in FavoriteGestures cannot do reliably.
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
