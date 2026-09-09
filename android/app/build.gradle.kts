plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dshmobile.app"
    compileSdk = 34

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.dshmobile.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "1.5.4"
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.webkit:webkit:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Termux 终端组件（GPL-3.0，见项目 LICENSE）：TUI 模式的终端渲染 + 软键盘交互。
    // JitPack 多模块坐标：group 为 termux/termux-app 仓库点分路径（否则 Gradle
    // 会把 4 段坐标当成 group:artifact:version:module 而找不到）。
    // terminal-view 依赖 terminal-emulator（含 NDK 原生渲染，x86/x86_64/arm 全部 ABI）。
    implementation("com.github.termux.termux-app:terminal-view:0.118.1")
}
