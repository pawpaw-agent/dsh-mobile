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

    // CI 安装这个确切的 NDK 并把版本写进环境变量，构建脚本与 workflow 因此不会漂移。
    // 回退值让本地构建在变量缺失时仍可用（AGP 会在缺包时自行下载）。
    ndkVersion = System.getenv("ANDROID_NDK_VERSION") ?: "25.1.8937393"

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
        versionCode = 31
        versionName = "0.1.4"

        // 只构建实际打包的 ABI。dbclient 也只有 arm64-v8a（见 README「仅 arm64」），
        // 多构建其它 ABI 只会拖慢构建并留下无用的 .so。
        // 注意：`ndk { }` 必须写在 defaultConfig 内——写在 android 直下会编译失败。
        ndk {
            abiFilters += "arm64-v8a"
        }
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

    // 自研 pty（native pty 分配 + 子进程启动）：见 docs/terminal-rewrite-plan.md 阶段 1。
    //
    // 为什么要自己写：Termux 的 libtermux.so 是我们要替换的最后一层 native 依赖；
    // 而且它的行为契约有两处容易搞错（waitFor 对被信号终止的子进程返回**负**信号号、
    // 子进程靠 setsid 后 open(pts) 自动获得控制终端），自己实现后由
    // app/src/main/cpp/dsh_pty_test.c 在宿主机上直接验证。
    //
    // 库名必须仍是 libtermux.so：Termux 的 com.termux.terminal.JNI 里写死了
    // System.loadLibrary("termux")，阶段 1 的目标就是**顶替**它（这样才能在真机上
    // 用原版 emulator 验证我们的 pty）。阶段 5 连同 JNI 类一起改名。
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    // 升级上限受**两个**独立约束，二者都必须满足：
    //
    // 1) AAR 元数据的 minCompileSdk —— 不得超过当前 compileSdk 34
    //    core 1.13.1 → 34；core 1.15.0 → 35；1.17.0 → 36；1.19.0 → 37
    //    webkit 1.15.0/1.17.0 → 33
    //
    // 2) 传递依赖的 kotlin-stdlib metadata 版本 —— 不得高于本机 Kotlin 编译器
    //    （当前 1.9.22，最多读 metadata 2.0.0）。这条更隐蔽：webkit 1.9.0–1.15.0
    //    不带 kotlin 依赖，而 **webkit 1.16.0 起引入 kotlin-stdlib 2.1.20**
    //    （metadata 2.1.0），会直接编译失败：
    //      "Module was compiled with an incompatible version of Kotlin"
    //    core-ktx 到 1.13.1 为止仍只依赖 kotlin-stdlib 1.8.22，故可用。
    //
    // 因此当前安全上限是 core-ktx 1.13.1 + webkit 1.15.0。再往上必须连同
    // Kotlin 2.x 一起升 —— 那是一笔独立的现代化工程，见 README「版本与升级」。
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.15.0")
    // Termux 终端组件（GPL-3.0，见项目 LICENSE）：TUI 模式的终端渲染 + 软键盘交互。
    // JitPack 多模块坐标：group 为 termux/termux-app 仓库点分路径（否则 Gradle
    // 会把 4 段坐标当成 group:artifact:version:module 而找不到）。
    // terminal-view 依赖 terminal-emulator（含 NDK 原生渲染，x86/x86_64/arm 全部 ABI）。
    //
    // 我们自带 libtermux.so（见上面 externalNativeBuild），而 terminal-emulator 的
    // AAR 里也有一个同名文件，合并时会冲突。pickFirsts 保留我们的——CI 会校验打包后
    // 的 .so 含 dsh-handheld 的构建标记，所以「留下的是哪一个」不是靠信任而是靠检查。
    implementation("com.github.termux.termux-app:terminal-view:0.118.1")
}

android {
    packaging {
        jniLibs {
            pickFirsts += "**/libtermux.so"
        }
    }
}
