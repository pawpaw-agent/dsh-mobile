plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ── 发布签名 ────────────────────────────────────────────────────────────────
// 签名材料经环境变量注入（CI 从 GitHub Secrets 解出），**不入库**：公开仓库里的
// 签名密钥等于任何人都能签出可覆盖安装的“升级包”。
//
// 缺失时回退 debug 签名，使 fork / 本地构建 / PR 构建仍可产出 APK。这种产物**同样不可
// 调试**（release 变体显式 isDebuggable = false），但签的是公开的 debug 密钥 ——
// 任何人都能签出可覆盖安装的“升级包”，因此不得对外分发。CI 会对此告警。
val releaseStorePath: String? = System.getenv("SIGNING_KEYSTORE_PATH")
val releaseStorePassword: String? = System.getenv("SIGNING_STORE_PASSWORD")
val releaseKeyAlias: String? = System.getenv("SIGNING_KEY_ALIAS")
val releaseKeyPassword: String? = System.getenv("SIGNING_KEY_PASSWORD")
val hasReleaseSigning: Boolean = releaseStorePath != null &&
    file(releaseStorePath).exists() &&
    !releaseStorePassword.isNullOrEmpty() &&
    !releaseKeyAlias.isNullOrEmpty() &&
    !releaseKeyPassword.isNullOrEmpty()

android {
    namespace = "com.dshhandheld.app"
    compileSdk = 34

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStorePath!!)
                storeType = "PKCS12"
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.dshhandheld.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 30
        versionName = "0.1.3"
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            // 发布包必须不可调试：debuggable=true 会让 `run-as` 无需 root 就能读走
            // 应用私有目录（含凭据），并使 WebView 远程调试对整个局域网开放。
            isDebuggable = false
            // R8 暂不开启：release 变体从未构建过，首次同时启用压缩/混淆与签名，
            // 一旦 R8 裁掉反射用到的类将无法在本机复现（且当前无法真机验证）。
            // 作为独立的后续项处理。见 README「版本与升级」。
            isMinifyEnabled = false
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
    // 版本上限由 AAR 元数据里的 minCompileSdk 决定，不是“有新版就升”：
    //   core 1.13.1 → minCompileSdk 34（= 当前 compileSdk，可用）
    //   core 1.15.0 → 35，1.17.0 → 36，1.19.0 → 37（均须先升 compileSdk）
    //   webkit 1.17.0 → 33（可用）
    // 升 compileSdk / AGP / Kotlin 是一笔独立的现代化工程，见 README「版本与升级」。
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.17.0")
    // Termux 终端组件（GPL-3.0，见项目 LICENSE）：TUI 模式的终端渲染 + 软键盘交互。
    // JitPack 多模块坐标：group 为 termux/termux-app 仓库点分路径（否则 Gradle
    // 会把 4 段坐标当成 group:artifact:version:module 而找不到）。
    // terminal-view 依赖 terminal-emulator（含 NDK 原生渲染，x86/x86_64/arm 全部 ABI）。
    implementation("com.github.termux.termux-app:terminal-view:0.118.1")
}
