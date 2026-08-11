plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ytmlauncher"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ytmlauncher"
        // 34, not 36 (ytmprobe's value): 36 was only there for
        // ResumeCarAppService's intentMatchingFlags, which doesn't exist in
        // this app. Staying on 34 keeps Android 15's tightened background-
        // activity-launch rules and 6-hour cumulative dataSync foreground-
        // service timeout from applying — both directly relevant here, since
        // AutoMediaService's cold-start fallback needs to start an Activity
        // from a MediaSessionCompat.Callback (see AutoMediaService's doc
        // comment) and SessionLogger is a long-running dataSync service.
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
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.media:media:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
}
