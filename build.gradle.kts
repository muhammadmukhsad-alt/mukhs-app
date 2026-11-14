plugins {
    id("com.android.application") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "1.9.24"
}

android {
    namespace = "com.mukhsapp.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mukhsapp.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 18
        versionName = "0.18"
    }

    // The same debug key is used for every build, so a new APK installs over the old
    // app without uninstalling it (and chat history is kept).
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-nearby:19.1.0")
}
