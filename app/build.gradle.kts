import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    jacoco
}

android {
    namespace = "app.perfectsound.player"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.perfectsound.player"
        minSdk = 29 // audio playback capture needs Android 10
        targetSdk = 36
        versionCode = 10
        versionName = "0.5.0"
        setProperty("archivesBaseName", "PerfectSound")
    }

    // The release key lives outside the repo; without it, release builds are left unsigned.
    val keystoreFile = file(System.getProperty("user.home") + "/.config/perfect-sound/keystore.properties")
    if (keystoreFile.exists()) {
        val keystore = Properties().apply { keystoreFile.inputStream().use { load(it) } }
        signingConfigs {
            create("release") {
                storeFile = file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug" // installs alongside the release build
            enableUnitTestCoverage = true
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.androidx.media) // shuffle/repeat of other apps' sessions
    testImplementation(libs.junit)
    testImplementation("org.json:json:20231013")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
