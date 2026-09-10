# dsh-handheld

**把 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)（`dsh`）装进口袋的 Android 客户端。**

完整 dsh Web 界面 + 内置 SSH 隧道 + 终端模式。服务端**零改动**——不需要装任何 dsh 插件，不需要 `--host 0.0.0.0`。

```
┌──────────────────────────────┐
│        dsh-handheld          │
│                              │
│  ① 看网页（全屏 WebView）    │
│  ② 打开终端（远程 shell）    │
│                              │
│  内置 SSH 隧道（dbclient）   │
└───────────────┬──────────────┘
                │  隧道后访问 http://127.0.0.1:3080
                │  （服务端视角 = 本机回环）
                ▼
┌──────────────────────────────┐
│          dsh web             │
│    （dsh --profile web）     │
│    运行在你的笔记本 / VPS    │
└───────────────┬──────────────┘
                ▼
┌──────────────────────────────┐
│    DeepSeek Harness Agent    │
│   你自己的 key、自己的配置   │
└──────────────────────────────┘
```

---

## 为什么用 SSH 隧道

DSH 出于安全设计，把**配置平面**（设置页、凭据管理、模型探测、目录选择等）**硬性限制为仅本机回环可访问**。从局域网 IP 访问这些接口会返回 `HTTP 403`——这是官方的安全边界（`PRIVILEGED_METHODS`），本项目不绕过它。

SSH 本地端口转发让服务端**仍然认为请求来自本机**，因此是**唯一既能远程使用、又不破坏官方安全模型的完整方案**，且自带 SSH 认证。本项目把这个过程内置了：填一次账号密码，App 自己维持隧道。

| | 局域网直连 | **内置 SSH 隧道** |
|---|---|---|
| 对话 / 会话 / 工作区 | ✅ | ✅ |
| 设置页 / 凭据 / 模型探测 | ❌ 403 | ✅ |
| 额外服务端插件 | 需要 | **不需要** |
| 认证 | dsh token | **SSH + dsh token（双重）** |
| 跨网络 | 需 Tailscale 等 | ✅ 只要 SSH 可达 |

---

## 功能

- **看 dsh 网页** — 全屏 WebView 加载 dsh 官方 Web 前端，功能与桌面端一致（Markdown、代码高亮、会话树、设置页、模型管理……）。内置 `crypto.randomUUID` 文档启动注入（防局域网明文 HTTP 下白屏）、跨 Activity 重建的 WebView 保活。
- **内置 SSH 隧道** — 打包 dropbear `dbclient`（arm64）做进程式本地端口转发，固定使用 `3080`（占用时回退 `13080`）。支持**密码**与**私钥**（含口令，可用系统文件选择器导入）两种认证；断线自动重连，App 重启后自动重建隧道。
- **打开终端** — 经 SSH PTY 打开远程 shell，用 Termux 的 [terminal-view](https://github.com/termux/termux-app) 原生渲染（真 IME / 软键盘交互，非 WebView），底部常驻键排（ESC / TAB / CTRL / 方向键 / Enter）。进入后就是普通远程 shell，**自由输入 `dsh-tui` 等命令**，不做任何自动启动。
- **token 全自动** — dsh 0.1.2+ 启用了浏览器 token 认证。SSH 模式下 App 会在服务端自动提取最新 token 并保存，服务重启后无需手动更新；失败时回退到连接屏手动填写。
- **连接屏刻意去术语** — 只出现「电脑地址 / 登录账号 / 电脑登录密码 / 看 dsh 网页 / 打开终端」，不暴露 SSH、端口、令牌等概念。

---

## 快速开始

### 1. 在电脑上启动 dsh web

```sh
dsh --profile web
# 默认监听 http://127.0.0.1:3080
```

### 2. 安装 App

从 [Releases](../../releases) 下载 APK，或自行构建：

```sh
cd android && ./gradlew assembleDebug
adb install android/app/build/outputs/apk/debug/app-debug.apk
```

> CI（GitHub Actions）也会构建 debug APK 作为 artifact。

### 3. 连接

打开 App，选择用途，填写三项即可：

| 字段 | 填什么 |
|---|---|
| 用途 | **看 dsh 网页**（默认）或 **打开终端** |
| 电脑地址 | 电脑的局域网 IP（如 `192.168.1.100`）或 Tailscale IP |
| 登录账号 / 密码 | 你在这台电脑上的 SSH 账号密码（隧道用） |

点「连接」即可。SSH 隧道由 App 自动建立。

---

## 连接方式

| 场景 | 说明 |
|---|---|
| **同一 WiFi（推荐）** | 填电脑局域网 IP，走内置 SSH 隧道 |
| **跨网络** | 用 [Tailscale](https://tailscale.com/) 等组网后填其 IP；隧道仍然生效 |
| **纯局域网直连（不建隧道）** | 功能受限（配置平面 403），需要服务端插件把 dsh 绑到 `0.0.0.0` |

---

## 项目结构

```
dsh-handheld/
├── android/
│   └── app/src/main/
│       ├── java/com/dshhandheld/
│       │   ├── app/
│       │   │   ├── MainActivity.kt           # 连接屏 + WebView 壳 + 隧道编排
│       │   │   ├── TuiActivity.kt            # SSH 终端模式（PTY + Termux 渲染）
│       │   │   ├── DshTerminalExtraKeys.kt   # 终端底部常驻键排
│       │   │   ├── SecurePrefs.kt            # 凭据静态加密（AndroidKeyStore AES-GCM）
│       │   │   └── DshApp.kt                 # Application：持有保活 WebView 与唯一隧道
│       │   └── protocol/
│       │       └── SshTunnel.kt              # dbclient 进程 + 端口选择 + 看门狗
│       ├── assets/plugins/                   # 注入的移动端适配插件（MIT，见下）
│       ├── jniLibs/arm64-v8a/                # dbclient（CI 阶段构建后放入）
│       └── AndroidManifest.xml
├── scripts/
│   ├── build-dropbear.sh                     # 交叉编译 dropbear dbclient
│   ├── build-apk.sh
│   ├── push-via-api.py                       # 增量推送（git 传输不可用时）
│   └── mirror-via-api.py                     # 整树镜像推送（重命名/删除时更稳）
├── docs/
│   ├── known-issues.md                       # 已知问题与行为记录
│   ├── dsh-protocol.md                       # DSH 线上协议逆向规格（历史存档）
│   └── dsh-plugins-404-fix.md
├── .github/workflows/ci.yml                  # 构建 dbclient → 构建并校验 release APK
└── package.json
```

---

## 凭据存储

SSH 密码与私钥口令**不以明文落盘**：

```
SharedPreferences "dsh-handheld"
  ssh_json      → enc.v1.<base64(iv ‖ AES-256-GCM 密文)>
  server_token  → 同上
  url / ssh_enabled → 明文（非敏感）
```

密钥由 **AndroidKeyStore** 持有且不可导出，因此即便应用私有目录被完整复制到另一台设备
也无法解密。实现见 `SecurePrefs.kt`。

**不使用** `androidx.security:security-crypto`——该库的全部 API 已被官方废弃
（1.1.0-beta01 起："Deprecated all APIs in favour of existing platform APIs and direct use
of Android Keystore"），故直接按官方指引使用平台 Keystore，不引入额外依赖。

**兼容性**：读取时若发现历史版本写入的明文，会原样返回并**顺手迁移**为密文，用户无感。
若 Keystore 密钥失效（设备策略变更等），解密失败按“未配置”处理并让用户重新输入，
而不是让 App 崩溃。

**不在威胁模型内**：已 root 且能在应用进程内执行代码的攻击者——此时应用自身必须能解密，
任何应用侧加密都无济于事。

---

## 版本与升级

### 依赖为什么“不是最新”

androidx 能否升级由 AAR 元数据里的 `minCompileSdk` 决定，**不是有新版就能升**：

| 库 | 当前 | 升级上限（compileSdk 34） |
|---|---|---|
| `androidx.core:core-ktx` | 1.13.1 | 1.13.1（1.15.0 要 35，1.19.0 要 37） |
| `androidx.webkit:webkit` | 1.17.0 | 已是当前 compileSdk 下的最高 |
| `termux terminal-view` | 0.118.1 | 见下方 vendoring 说明 |

### 待办的现代化项

- **compileSdk / targetSdk 34 → 36**，连带 AGP → 9.x、Gradle → 9.x、Kotlin → 2.x。
  耦合改动且涉及 K2 编译器迁移，应单独成一个变更并做真机回归。
- **启用 R8**：`release` 变体目前 `isMinifyEnabled = false`。首次启用压缩/混淆需真机验证
  （R8 可能裁掉运行期才引用的类），不宜与签名变更同时进行。

### Termux 组件的 vendoring

`android/app/src/main/java/com/termux/shared/terminal/io/extrakeys/` 下的 6 个文件是从
Termux `v0.118.1` 复制的副本，与 JitPack 依赖 `terminal-view` 内的同名类**同包同名**，
编译时**源码优先于 jar**：

- **4 个逐字未改**（仅加归属头）；
- **2 个有本地改动**：`ExtraKeyButton`（新增 `rowSpan` 配置项）与 `ExtraKeysView`
  （字号与纵向跨行）。两文件的头注释已写明区别。

**升级 `terminal-view` 时**：未 vendored 的类会随依赖更新，这 6 个副本**不会** ——
上游在这几个类里的修复无法自动到达，需手工比对合并。

---

## 发布与签名

发布产物由 CI 用 **release 签名**构建，签名材料经环境变量注入，**不入库**（公开仓库里的
签名密钥等于任何人都能签出可覆盖安装的“升级包”）：

| GitHub Secret | 内容 |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | PKCS12 keystore 的 base64 |
| `SIGNING_STORE_PASSWORD` | keystore 口令 |
| `SIGNING_KEY_ALIAS` | 密钥别名 |
| `SIGNING_KEY_PASSWORD` | 密钥口令 |

secrets 缺失时（fork / PR）回退 debug 签名并告警，该产物**不可对外分发**。CI 另有一道
硬校验：release APK 若含 `application-debuggable` 则**构建直接失败**——`debuggable=true`
会让 `run-as` 无需 root 即可读取应用私有目录，并使 WebView 远程调试对整个局域网开放。

> ⚠️ **务必备份 keystore 与口令。** 丢失后无法再发布可覆盖安装的升级包，只能让所有用户
> 卸载重装。

---

## 移动端界面适配

dsh 官方 Web 前端是桌面布局，窄屏下侧栏会常驻挤占内容。本项目在 **App 侧**注入
[dsh-web-mobile](https://github.com/mexiaosqwq/dsh-web-mobile)（MIT，作者 mexiaosqwq）
客户端插件来适配——`addDocumentStartJavaScript` 钩住 `__DSH_BOOT__` 启动图，
`shouldInterceptRequest` 从 APK assets 返回插件 bundle，**服务端不需要装任何插件**。

> 该项目是第三方作品，其许可证见 `android/app/src/main/assets/plugins/LICENSE-dsh-web-mobile.txt`。

---

## 注意事项

- **明文 HTTP** — `usesCleartextTraffic="true"`。隧道模式下流量本身已由 SSH 加密，明文仅存在于设备本地回环；但如果用局域网直连，请确保在可信内网。
- **安全** — SSH 密码与 dsh token 经 AndroidKeyStore 加密后落盘（见「凭据存储」）；发布包不可调试。dsh 0.1.2+ 默认启用浏览器 token 认证，SSH 隧道再叠加一层 SSH 认证。公开 WiFi 下建议用 Tailscale 而不是直接暴露端口。
- **仅 arm64** — `dbclient` 目前只为 `arm64-v8a` 构建，不适用于 32 位或 x86 设备。
- **签名变更需重装** — 0.1.3 起改用独立发布签名（此前为 debug 签名）。签名不同，Android **不允许覆盖安装**：需先卸载旧版，已保存的连接配置会一并清除。
- **真机验证状态** — 见 `docs/known-issues.md`。

---

## 已知问题

见 [`docs/known-issues.md`](docs/known-issues.md)（含两处已定性待修行为与日志排查说明）。

---

## License

**GPL-3.0**（[GNU General Public License v3.0](https://www.gnu.org/licenses/gpl-3.0.html)）

SSH 终端模式集成了 Termux 的 [terminal-view / terminal-emulator](https://github.com/termux/termux-app)（GPL-3.0），因此整个项目以 GPL-3.0 发布；衍生作品需同样以 GPL-3.0 开源。

项目中还打包了第三方组件，各自的许可证随附于对应目录：

| 组件 | 许可证 | 位置 |
|---|---|---|
| Termux terminal-view / terminal-emulator | GPL-3.0 | Gradle 依赖 |
| Dropbear `dbclient` | MIT 风格（见随附文件） | `jniLibs/.../LICENSE-dropbear.txt` |
| dsh-web-mobile 客户端插件 | MIT | `assets/plugins/LICENSE-dsh-web-mobile.txt` |
