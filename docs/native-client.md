# dsh-mobile 后台协议与 SSH 架构

> 本文档描述 dsh-mobile 的后台/连接层架构。**App 界面已经是纯 WebView**（只显示 dsh 官方 Web 前端）；
> 协议客户端 `DshClient` 不再提供任何原生业务页面，仅用于 `AgentMonitorService` 的后台完成通知
> （监听 `host/session-status`），以及 `SshTunnel` 提供 SSH 本地端口转发。
> 协议规格见 `docs/dsh-protocol.md`。

## 为什么保留一个轻量协议客户端

- **WebView 界面**：由 dsh web 前端负责全部功能，App 不重复实现。
- **后台通知**：App 退后台后，`AgentMonitorService` 需要一个不依赖浏览器页面的通道来监听 Agent 完成事件，
  因此直接实现 DSH 协议的两个 downlink WebSocket + unary RPC：
  - **unary RPC**：`POST /api/<method>`，body 为 `client-request` JSON，校验 `rpcId` 回显。
  - **事件流**：`/api/events.mux` + `/api/events.host` 两个 downlink-only WebSocket（每帧一个 `server-request` JSON）。
- **SSH 隧道**：`SshTunnel`（JSch）通过本地端口转发让 WebView/后台服务都能以回环身份访问配置平面。

## 模块结构

```
com.dshmobile/
├── app/
│   ├── DshApp.kt              # Application 单例：持有 WebView 与 SshTunnel
│   ├── MainActivity.kt        # 连接屏 + WebView 壳 + SSH 隧道（纯 WebView，无原生业务页）
│   └── AgentMonitorService.kt # 后台 Agent 完成通知（使用 DshClient + 可选 SshTunnel）
└── protocol/
    ├── Rpc.kt                 # 四象限 RPC envelope + 判别错误体 + 解析/构造
    ├── DshClient.kt           # HTTP POST /api unary + 双 WebSocket downlink + 就绪握手/重连 + 方法目录 + respond
    └── Models.kt              # host/session/workspace/model/事件帧 Kotlin 数据类
```

## 数据流

```
[MainActivity]  connect(host:port / SSH) -> WebView.loadUrl(dsh web)
        |  用户所有交互由 dsh web 前端完成
        v
[AgentMonitorService] (退后台后)
        DshClient.start()  -> open /api/events.mux + /api/events.host
        |                   downlink frames (envelope rpcId, payload)
        v
    host/session-status running=true→false -> notifyDone() 推送完成通知
```

## 就绪握手 & 重连

- `DshClient.start()`：建立两个 downlink WebSocket + 一次 `host.describe()`。
- `host.describe` 成功 → `connected`，回调 `onConnected(describeValue)`。
- 失败 → `reconnecting`，指数退避（base 500ms、倍率 2、上限 10s + 抖动）。

## 远程访问 / 配置平面 403

- unary 业务接口（会话、模型、工作区、事件流）远程可用。
- **配置平面**（`settings.*`、`credentials.*`、`agentPreset` 写、`host.pickDirectory/openPath`、`llm.discoverModels`）**仅回环可访问**（`PRIVILEGED_METHODS`）。
- 远程合规方案：SSH 端口转发 `ssh -L 3080:127.0.0.1:3080 用户@电脑IP`，客户端填 `http://127.0.0.1:3080`（从服务端视角仍是回环）。
- App 内置 `SshTunnel`（JSch）实现同样的本地端口转发，支持密码/私钥认证、断线自动重连；WebView 模式下 App 重启后会自动重建隧道并重新加载新端口。

## 已实现的核心交互（App 内可见功能都由 WebView 承担）

| 交互 | 实现方式 | 状态 |
|---|---|---|
| 全部会话/对话/Markdown/设置页 | dsh Web 前端（WebView） | ✅ |
| SSH 隧道/自动重连 | SshTunnel + MainActivity | ✅ |
| 连接/就绪 | 主界面 WebView；后台服务用 host.describe | ✅ |
| 后台完成通知 | DshClient + /api/events.host | ✅ |

## 说明

- 后台服务的所有网络调用在后台线程执行（`Thread { ... }`）。
- 事件流回调带 `(envelope rpcId, payload)`，`rpcId` 用于应答 `server-request`（审批/提问，后台通知目前只消费 host 状态）。
- 依赖 OkHttp（`com.squareup.okhttp3:okhttp:4.12.0`）、JSch（`com.github.mwiede:jsch:0.2.21`，仅 SSH 隧道用）；JSON 用 Android 内置 `org.json`，无额外序列化库。
