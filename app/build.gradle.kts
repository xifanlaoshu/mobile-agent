plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("kotlinx-serialization")
}

android {
    namespace = "com.xhs.agent"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.xhs.agent"
        minSdk = 28  // Android 9 (荣耀 V20 出厂系统)
        targetSdk = 29 // Android 10 (荣耀 V20 可升级)
        versionCode = 1
        versionName = "0.1.0"

        // DeepSeek API Key — 通过 local.properties 或环境变量注入
        buildConfigField("String", "DEEPSEEK_API_KEY", "\"${project.findProperty("DEEPSEEK_API_KEY") ?: ""}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.4"
    }
}

dependencies {
    // ============ Compose UI ============
    val composeBom = platform("androidx.compose:compose-bom:2024.01.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.8.2")

    // ============ 网络 ============
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-kotlinx-serialization:2.9.0")

    // ============ 序列化 ============
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // ============ 协程 ============
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // ============ OCR (Tesseract) ============
    implementation("com.rmtheis:tess-two:9.1.0")

    // ============ 本地存储 ============
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // ============ 基础 ============
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // ============ 调试 ============
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
