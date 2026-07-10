plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Signing: environment-driven for real releases (release.yml); otherwise a
// COMMITTED, purpose-made throwaway keystore keeps the signature STABLE across
// CI runs, so updates install in place instead of demanding uninstall (which
// also wiped MediaStore ownership and emptied the in-app gallery). This key is
// public and for sideloading only - never ship it to a store.
val ksFile: String = System.getenv("KEYSTORE_FILE") ?: "signing/debug-only.keystore"

android {
    namespace = "com.renardoberou.spectralcamera"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.renardoberou.spectralcamera"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("VERSION_CODE") ?: "28").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.8.6"
    }

    signingConfigs {
        create("stable") {
            storeFile = file(ksFile)
            storePassword = System.getenv("KEYSTORE_PASS") ?: "spectraldebug"
            keyAlias = System.getenv("KEY_ALIAS") ?: "spectraldebug"
            keyPassword = System.getenv("KEY_PASS") ?: "spectraldebug"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stable")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.05.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
