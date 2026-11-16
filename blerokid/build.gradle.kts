plugins {
    id("com.android.application")
}

android {
    namespace = "com.drift.bleremoterokid"
    compileSdk { version = release(37) { minorApiLevel = 0 } }

    defaultConfig {
        applicationId = "com.drift.bleremoterokid"
        minSdk = 28
        targetSdk = 36
        versionCode = 13
        versionName = "R13"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}


dependencies {
    implementation("com.rokid.security:glass3.open.sdk:2.2.2-E")
}
