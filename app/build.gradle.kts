plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.untouch.app"
    // API 35 matches Android 15 (vivo Y400 5G) for personal sideloading.
    compileSdk = 35

    defaultConfig {
        applicationId = "com.untouch.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "2.1.0"
    }

    // Stable debug signing key committed to the repo so successive CI builds
    // can update the app in place instead of forcing an uninstall (which would
    // wipe the encrypted PIN/config). This is a personal, sideload-only key.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("untouch-debug.keystore")
            storePassword = "untouchdebug"
            keyAlias = "untouch"
            keyPassword = "untouchdebug"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.11.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
}
