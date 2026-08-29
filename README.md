# dsh-mobile

**手机端使用 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)（`dsh`）的纯 WebView Android 客户端。**

连接运行在你笔记本 / VPS 上的 `dsh --profile web`：

- **完整网页（默认，也是唯一界面）** — 全屏 WebView 加载 dsh web 前端，功能与桌面 100% 一致（Markdown / 代码高亮 / 设置页 / 会话树……）。内置 `crypto.randomUUID` 文档启动注入（局域网明文 HTTP 防白屏）、Basic Auth 弹窗、错误重试页、WebView 跨重建保活。
- **SSH 隧道** — 内置 JSch 本地端口转发，服务端视角为回环，**解锁设置/凭据等本机限制接口**（官方认可的合规远程完整方案），无需 `dsh-lan-access` 插件、无需 `--host 0.0.0.0`；支持密码登录、私钥登录（含口令），SSH 断线自动重连，App 重启后自动重建隧道。

```
┌─────────────────────────────┐
│        dsh-mobile           │
│     完整网页(WebView)       │
│   SSH 隧道 / 局域网直连      │
└──────────────┬──────────────┘
               │  http://<host>:3080
               │  局域网 / Tailscale / 隧道 / SSH 转发
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

- **连接屏** — 输入 host:port + 选择 http/https 协议即可连接 dsh web，默认端口 `3080`；SSH 隧道配置持久化，App 重启自动恢复
- **完整网页** — 所有功能（会话、对话、Markdown/代码高亮、设置页、模型、凭据、工作区等）均由 dsh 官方 Web 前端提供，与桌面端一致
- **SSH 自动恢复** — SSH 隧道配置持久化；App 重启自动重建，断线重连后 WebView 自动跟随新端口
- **SSH 完成通知** — App 退后台时通过同一 SSH 隧道监听 Agent 完成事件，完成后推通知；回前台自动停止
- **明文 HTTP 支持** — `usesCleartextTraffic="true"`，支持局域网直连、Tailscale、反向代理、SSH 本地转发

> 后台通知服务（`AgentMonitorService`）内部仍使用轻量协议客户端 `DshClient` 监听 `host/session-status`，但 App 界面不包含任何原生业务页面。

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

点 **Connect**，App 加载 dsh Web 前端，开始使用。

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
│   │   │   ├── java/com/dshmobile/
│   │   │   │   ├── app/
│   │   │   │   │   ├── MainActivity.kt          # 连接屏/WebView 壳 + SSH 隧道
│   │   │   │   │   ├── AgentMonitorService.kt   # 后台 Agent 完成通知服务
│   │   │   │   │   └── DshApp.kt                # Application：持有 WebView / SSH 隧道
│   │   │   │   └── protocol/
│   │   │   │       ├── Rpc.kt                   # 四象限 RPC envelope + 错误体
│   │   │   │       ├── DshClient.kt             # HTTP RPC + 双 WebSocket + 方法目录 + respond
│   │   │   │       └── Models.kt                # 领域数据类
│   │   │   ├── AndroidManifest.xml
│   │   │   └── res/                      # 启动图标 + 主题
│   │   ├── build.gradle.kts
│   │   └── debug.keystore               # 固定 debug 签名（CI/本地一致，install -r 覆盖升级）
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   └── gradle/wrapper/
├── scripts/
│   └── build-apk.sh                     # 封装 gradle assembleDebug
├── docs/
│   ├── dsh-protocol.md                  # 逆向的 DSH 线上协议规格
│   └── native-client.md                 # 后台协议客户端/SSH 架构（仅用于完成通知）
├── package.json
└── README.md
```

---

## 注意事项

- **网络调用**：全部在后台线程执行，UI 通过 `Handler(Looper.getMainLooper())` 回主线程刷新，避免阻塞主线程。
- **安全**：绑定 `0.0.0.0` 后，同一内网任何设备都能访问 dsh web（无认证）。仅限家庭 / 公司可信内网使用，公共 WiFi 请勿开启；出外网请叠加 Tailscale 或 SSH 转发。
- **配置平面 403**：`settings.*` / `credentials.*` / `agentPreset` 写 / `host.pickDirectory` / `llm.discoverModels` 仅回环可访问，远程需 SSH 端口转发（见上文「远程访问限制」）。

---

## License

MIT
