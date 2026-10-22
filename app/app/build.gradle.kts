plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.thirdcoastsailing.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.thirdcoastsailing.app"
        minSdk = 34                       // Android 14 and newer
        targetSdk = 37
        versionCode = 13
        versionName = "0.7.6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Phones and tablets only: ship the map engine for ARM (64- and 32-bit), not the x86 emulator builds (-27 MB)
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            optimization {
                // Off: R8 shrinking broke Kotlin's text classes in 0.7 (crash on start, IllegalAccessError in kotlin.text)
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

// Must run without Google Play Services (GrapheneOS / LineageOS): fail the build if any GMS or Firebase library appears
configurations.configureEach {
    resolutionStrategy.eachDependency {
        val g = requested.group
        if (g.startsWith("com.google.android.gms") || g.startsWith("com.google.firebase")) {
            throw GradleException("Google Play Services / Firebase dependency is not allowed: $requested")
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.maplibre.android)              // map engine (open source, no Google services)
    implementation(libs.androidx.lifecycle.runtime.compose)   // lifecycle access from Compose
    implementation(libs.androidx.documentfile)         // reading the folder picked in the system file picker
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
