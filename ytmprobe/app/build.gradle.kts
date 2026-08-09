plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ytmprobe"
    // 34 -> 36: android:intentMatchingFlags (see ResumeCarAppService's manifest
    // entry) isn't a recognized attribute below API 36 — aapt2 needs the
    // platform 36 android.jar to resolve it at all, independent of anything
    // about this app's actual minSdk/targetSdk floor.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ytmprobe"
        minSdk = 26
        targetSdk = 36
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
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.media:media:1.7.0")
    // 1.4.0 -> 1.7.0 (latest stable): androidx.car.app's own IOT-category app
    // discovery/binding had real gaps in this window. Matches the version
    // Home Assistant's Android app uses for the exact same IOT category.
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.car.app:app-projected:1.7.0")
    // Launch v2 only — ItemTouchHelper properly disambiguates a row's
    // horizontal swipe from the list's vertical scroll, which the manual
    // GestureDetector approach in FavoriteGestures cannot do reliably.
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Regression suite — plain JVM (JUnit4) + Robolectric where a test needs
    // a real Context (SharedPreferences-backed Store/Favorites) or a real
    // View/Activity (FavoriteGestures' GestureDetector wiring). No device
    // or emulator needed; this is separate from the probes, which still
    // need a real device for anything touching YTM/AA itself.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
}
