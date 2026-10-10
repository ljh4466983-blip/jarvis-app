plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.imagestory.jarvis"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.imagestory.jarvis"
        minSdk = 29          // Android 10+
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    // 고정 서명키: 새 버전을 받아도 기존 앱 위에 그대로 업데이트되게 한다
    signingConfigs {
        create("jarvis") {
            storeFile = file("jarvis.keystore")
            storePassword = "jarvis1988"
            keyAlias = "jarvis"
            keyPassword = "jarvis1988"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("jarvis") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("jarvis")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
