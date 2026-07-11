plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Production signing is opt-in and must be supplied completely through the
// environment. Ordinary local and CI builds never fall back to a committed or
// hard-coded signing key.
val releaseSigningEnvironment: Map<String, String?> = mapOf(
    "KEYSTORE_FILE" to System.getenv("KEYSTORE_FILE"),
    "KEYSTORE_PASS" to System.getenv("KEYSTORE_PASS"),
    "KEY_ALIAS" to System.getenv("KEY_ALIAS"),
    "KEY_PASS" to System.getenv("KEY_PASS"),
)
val hasAnyReleaseSigningValue = releaseSigningEnvironment.values.any { !it.isNullOrBlank() }
val hasCompleteReleaseSigning = releaseSigningEnvironment.values.all { !it.isNullOrBlank() }

if (hasAnyReleaseSigningValue && !hasCompleteReleaseSigning) {
    val missing = releaseSigningEnvironment
        .filterValues { it.isNullOrBlank() }
        .keys
        .joinToString()
    throw GradleException("Incomplete release signing environment. Missing: $missing")
}

android {
    namespace = "com.renardoberou.spectralcamera"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.renardoberou.spectralcamera"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("VERSION_CODE") ?: "32").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.9.2"
    }

    signingConfigs {
        if (hasCompleteReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseSigningEnvironment["KEYSTORE_FILE"]))
                storePassword = requireNotNull(releaseSigningEnvironment["KEYSTORE_PASS"])
                keyAlias = requireNotNull(releaseSigningEnvironment["KEY_ALIAS"])
                keyPassword = requireNotNull(releaseSigningEnvironment["KEY_PASS"])
            }
        }
    }

    buildTypes {
        debug {
            // Use the Android toolchain's normal debug key. Debug artifacts are
            // ephemeral and are never presented as update-compatible releases.
        }
        release {
            isMinifyEnabled = false
            if (hasCompleteReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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

    testImplementation("junit:junit:4.13.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
