plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.meindaten.digitalisierer"
    compileSdk = 34

    defaultConfig {
        applicationId = "de.meindaten.digitalisierer"
        minSdk = 29          // Android 10+
        targetSdk = 34
        // Wird von GitHub bei jedem Bau hochgezählt (für automatische Updates)
        val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toInt()
        versionCode = 100 + run
        versionName = "1.0.$run"
    }

    // Fester Schlüssel, damit Updates sich ohne Deinstallation installieren lassen
    signingConfigs {
        create("app") {
            storeFile = file("release.keystore")
            storePassword = "mdd-app-2026"
            keyAlias = "mdd"
            keyPassword = "mdd-app-2026"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("app")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("app")
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.fragment:fragment-ktx:1.8.3")
    // Entsperren per Fingerabdruck (Schlüssel im Android-Keystore)
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Texterkennung direkt auf dem Handy (offline, nichts wird hochgeladen)
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
