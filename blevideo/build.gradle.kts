plugins {
    id("com.android.application")
}

android {
    namespace = "com.drift.rokidvideo"
    compileSdk { version = release(37) { minorApiLevel = 0 } }

    defaultConfig {
        applicationId = "com.drift.rokidvideo"
        minSdk = 28
        targetSdk = 36
        versionCode = 10
        versionName = "R10"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
