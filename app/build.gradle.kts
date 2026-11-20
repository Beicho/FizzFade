plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.qishui.automix"
    compileSdk = 36 // miuix 0.8.8 and activity 1.13 require compileSdk >= 36

    defaultConfig {
        applicationId = "io.github.qishui.automix"
        minSdk = 28
        targetSdk = 35
        versionCode = 24
        versionName = "0.6.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        // Java hook code stays Java 8. Built-in Kotlin inherits this as jvmTarget and AGP 9 rejects
        // a different Kotlin target. Raise both together if a library inline function needs it.
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildFeatures {
        compose = true
    }

    dependenciesInfo {
        // Encrypted dependency report that only Google Play can read; it is re-encrypted on every
        // build, so dropping it makes the sideloaded APK byte-for-byte reproducible.
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(libs.miuix)
    implementation(libs.miuix.icons)
    implementation(libs.androidx.activity.compose)
    implementation(libs.haze)
    compileOnly(libs.xposed.api) // provided by LSPosed at runtime; never packaged
}
