# dsh-mobile

**手机端远程使用 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)（`dsh`）Web GUI 的 Android 全屏 WebView 壳。**

连接运行在你笔记本 / VPS 上的 `dsh --profile web` 服务，在手机上获得与桌面一致的 DeepSeek Harness 编程体验。dsh-mobile 本身只是一个极简的 WebView 容器——不做任何 UI 重写，不代理你的代码或对话，所有能力都由你自己的 dsh 实例与 provider 配置提供。

```
┌─────────────────────────────┐
│        dsh-mobile           │
│  (Android WebView 壳)       │
└──────────────┬──────────────┘
               │  HTTP (WebView 加载页面)
               │  局域网 / Tailscale / 隧道
               ▼
┌─────────────────────────────┐
│        dsh web              │
│  (dsh --profile web)        │
│  运行在你的笔记本 / VPS     │
│  默认 http://<host>:3080    │
└──────────────┬──────────────┘
               │
               ▼
┌─────────────────────────────┐
│   DeepSeek Harness Agent    │
│   你自己的 key，你自己的配置 │
└─────────────────────────────┘
```

---

## 功能

- **全屏沉浸 WebView** — `IMMERSIVE_STICKY` + `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`，WebView 铺满整块屏幕（包括状态栏后方），在三星等机型也能画到刘海后面
- **连接屏** — 输入 host:port + 选择 http/https 协议即可连接 dsh web，默认端口 `3080`
- **自动重连** — 上次连接的 URL 存入 SharedPreferences，下次打开 App 直接加载，无需重复输入
- **WebView 跨重建保活** — WebView 实例由 `Application` 单例持有，转屏 / 从最近任务返回时不重载页面（保留 JS 运行时、滚动位置、会话状态）
- **返回键导航** — 优先回退 WebView 浏览历史，无历史时回到连接屏（换服务器）
- **安全 WebView 配置** — 关闭 `allowFileAccess` / `allowContentAccess`，开启 `domStorage`，自定义 UA `DshMobile/1.0`
- **明文 HTTP 支持** — `usesCleartextTraffic="true"`，方便局域网直连
- **`crypto.randomUUID` 兜底** — dsh 前端 RPC 依赖 `crypto.randomUUID`，而 WebView 在局域网明文 HTTP（非安全上下文）下访问不到该 API，会导致白屏。App 在文档开始前注入标准 UUID v4 polyfill（与 `dsh-lan-access` 插件同款、幂等），因此即使服务端没装插件也能连
- **Basic Auth 支持** — 隧道 / 反向代理带 Basic Auth 时弹出登录框

---

## 快速开始

### 1. 在笔记本 / VPS 上启动 dsh web

```sh
dsh --profile web
# 默认监听 http://127.0.0.1:3080
```

> 如果要让手机通过**局域网**访问，需要把服务绑到 `0.0.0.0`。`dsh web` 出于安全考虑**故意拒绝 `--host 0.0.0.0`**，请安装官方认可的 [dsh-lan-access](https://www.npmjs.com/package/dsh-lan-access) 插件：
>
> ```sh
> dsh plugin --profile web add dsh-lan-access
> # 重启 dsh web 生效
> ```
>
> 该插件会：将 webserver 的 `host` 改为 `0.0.0.0`；向每次返回的 index.html 注入 `crypto.randomUUID` polyfill（对未装插件的环境是额外保险）；屏幕宽度 ≤820px 时自动切换为紧凑移动排版（正好覆盖手机窄屏）。

### 2. 安装 dsh-mobile

```bash
cd android && ./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

也可以直接从 GitHub Actions 的构建产物（`dsh-mobile-debug-apk` artifact）下载 APK。

### 3. 连接

打开 App，在连接屏选择协议（http / https），输入：
- **Host** — 笔记本的局域网 IP（如 `192.168.1.100`）或 Tailscale IP（如 `100.x.x.x`）或隧道域名
- **Port** — dsh web 端口，默认 `3080`

点 **Connect**，WebView 全屏加载 dsh web，开始使用。

---

## 连接方式

| 场景 | 协议 | Host 示例 | 说明 |
|---|---|---|---|
| 局域网（推荐） | http | `192.168.1.100` | 同一 WiFi 下直连，需 `dsh-lan-access` 插件绑定 `0.0.0.0` |
| Tailscale | http | `100.x.x.x` | 跨网络、端到端加密，推荐远程使用 |
| Cloudflare Tunnel / ngrok | https | `my-dsh.trycloudflare.com` | 端口填 443，选 https 协议 |
| SSH 端口转发 | http | `127.0.0.1` | 见下方「远程访问限制」 |

---

## 远程访问限制（DSH 官方安全设计）

DSH 把**配置平面**——设置页、模型 / Provider 管理、凭据、Agent Preset、目录选择、`llm.discoverModels`（模型探测）等接口——**硬性限制为仅本机回环（127.0.0.1）可访问**。用 `dsh-lan-access` 绑定 `0.0.0.0` 后，这些接口从局域网 IP 访问仍会返回 `HTTP 403`。这是官方的安全边界（`PRIVILEGED_METHODS`），插件有意不绕过。

- **远程正常**：对话、实时进度、会话内模型选择、会话历史、工作区浏览、其余正常 API
- **远程 403（仅本机回环）**：设置页、凭据管理、Agent Preset 管理、目录选择、`llm.discoverModels`

### 远程需要改设置怎么办

- **日常路径**：在电脑本机（`http://127.0.0.1:3080`）完成模型 / 凭据配置，手机只用于对话、看进度、选模型。
- **唯一合规的完整方案**：SSH 本地端口转发

  ```sh
  ssh -L 3080:127.0.0.1:3080 用户@电脑IP
  ```

  然后手机访问 `http://127.0.0.1:3080`（需把 Host 填 `127.0.0.1`，或在连接屏改地址）。从服务端视角这仍是回环访问（不绕过栅栏），且自带 SSH 认证。

---

## 项目结构

```
dsh-mobile/
├── android/
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── java/com/dshmobile/app/
│   │   │   │   ├── MainActivity.kt      # 连接屏 + 全屏 WebView + 前后台检测
│   │   │   │   └── DshApp.kt            # Application 单例：跨 Activity 保活 WebView
│   │   │   ├── AndroidManifest.xml
│   │   │   └── res/                     # 启动图标 + 主题
│   │   ├── build.gradle.kts
│   │   └── debug.keystore              # 固定 debug 签名（CI/本地一致，install -r 覆盖升级）
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   └── gradle/wrapper/
├── scripts/
│   └── build-apk.sh                     # 封装 gradle assembleDebug
├── package.json
└── README.md
```

---

## 注意事项

- **WebView 版本**：建议使用系统最新版 Android System WebView，以支持新版 Chrome 内核能力（ADB 可 `adb shell pm trim-caches` 或到应用商店更新）。`WebViewCompat` 会在不支持 `DOCUMENT_START_SCRIPT` 的旧内核下自动跳过 polyfill 注入。
- **安全**：绑定 `0.0.0.0` 后，同一内网任何设备都能访问 dsh web（无认证）。仅限家庭 / 公司可信内网使用，公共 WiFi 请勿开启；出外网请叠加 Tailscale 或 SSH 转发。

---

## License

MIT
