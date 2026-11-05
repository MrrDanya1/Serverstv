plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "tv.serverstv.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "tv.serverstv.app"
        minSdk = 23
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"
    }
}
