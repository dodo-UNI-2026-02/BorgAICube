// Sin dependencias: solo el SDK de Android y Kotlin (org.json viene en Android).
plugins {
    id("com.android.application")
}

android {
    namespace = "pe.uni.cubo"
    compileSdk = 36
    defaultConfig {
        applicationId = "pe.uni.cubo"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
}
