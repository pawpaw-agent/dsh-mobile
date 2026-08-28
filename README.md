# dsh-mobile

**手机端远程使用 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)（`dsh`）Web GUI 的**原生 Android 客户端**（非 WebView）。**

原生客户端直接实现 DSH 的线上协议与 `dsh --profile web` 通信，无需浏览器内核。连接运行在你笔记本 / VPS 上的 `dsh --profile web`，在手机上获得原生体验（会话、对话、实时流式、模型选择、工具审批、任务进度）。协议细节见 [`docs/dsh-protocol.md`](docs/dsh-protocol.md)，架构见 [`docs/native-client.md`](docs/native-client.md)。

```
┌─────────────────────────────┐
│        dsh-mobile           │
│  (原生 Android / 非 WebView) │
│   HTTP RPC + WebSocket 事件流 │
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

- **连接屏** — 输入 host:port + 选择 http/https 协议即可连接 dsh web，默认端口 `3080`；连接前做一次 `host.describe` 就绪握手
- **会话列表与打开** — `session.list` / `session.history`，展示最近会话并可进入
- **对话发送** — `session.prompt`（queue / steer），等待 agent 响应
- **实时流式渲染** — `/api/events.mux` 的 `session/event` → `assistant/chunk`，按 content-block index 分块渲染（text 与 tool-call 分属不同 block，`block-end` 以服务端完整文本落定）
- **模型选择** — 顶栏「模型」按钮，`session.models` 拉取分组模型列表，`session.selectModel` 切换
- **工具审批** — `approval/requested` 弹出「允许一次 / 拒绝」，`/api/respond` 回 `client-response`
- **用户提问** — `question/requested` 接入 `respondQuestion`
- **任务/进度** — `session/jobs` 渲染 TaskView 列表
- **明文 HTTP 支持** — `usesCleartextTraffic="true"`，局域网直连（原生客户端无需浏览器 polyfill/窄屏适配）
- **`DshClient` 断线重连** — 就绪握手失败按指数退避重连（base 500ms、倍率 2、上限 10s + 抖动）

> 协议层（`com.dshmobile.protocol`）还实现了 `workspace.*`、`subagent.*`、`llm.*`、`settings.*`、`credentials.*`、`goal.*` 等方法目录，供后续 UI 接入。

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

点 **Connect**，App 做 `host.describe` 就绪握手后进入原生会话页，开始使用。

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
│   │   │   │   │   ├── MainActivity.kt          # 连接屏（host:port → DshClient 就绪握手）
│   │   │   │   │   ├── ConversationActivity.kt  # 原生会话/对话/流式/审批/模型选择
│   │   │   │   │   └── DshApp.kt                # Application 单例：持有进程级 DshClient
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
│   └── native-client.md                 # 原生客户端架构
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
