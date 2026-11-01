plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lite.tvlauncher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lite.tvlauncher"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Ký bằng khóa debug để APK release cài được ngay, không cần keystore riêng
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += listOf(
            "/META-INF/*.version",
            "/META-INF/*.kotlin_module",
            "DebugProbesKt.bin",
            "kotlin-tooling-metadata.json"
        )
    }
}

// Không thêm thư viện nào khác: không AndroidX, không Glide, không Compose.
