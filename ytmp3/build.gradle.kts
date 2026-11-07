plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.ytmp3"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.ytmp3"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        // Phones only: keeps the bundled Python/ffmpeg native libraries to two ABIs.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the release APK installs directly for testing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // youtubedl-android runs python/ffmpeg from extracted native libraries.
    packaging { jniLibs { useLegacyPackaging = true } }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
}
