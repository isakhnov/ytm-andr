plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aatest"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aatest"
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
}

dependencies {
    // The legacy media-app API — deliberately NOT androidx.car.app, whose
    // every category (including ytmprobe's IOT) maps internally to
    // gearhead's blocked TEMPLATE bucket. This is the one thing this whole
    // app exists to test: does the *other* bucket, MEDIA, actually get
    // through gearhead's sideload allowlist. See README.md.
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.media:media:1.7.0")
}
