# dsh-mobile 原生客户端架构

> 本文档描述 dsh-mobile 的**原生（非 WebView）**客户端架构，对照 `docs/dsh-protocol.md` 的协议规格。
> native 客户端通过直接实现 DSH 的线上协议（HTTP RPC + 双 WebSocket downlink）与 `dsh web` 通信。

## 为什么是原生而非 WebView

原生客户端**不依赖浏览器内核**，直接实现 DSH 的线上协议：
- **unary RPC**：`POST /api/<method>`，body 为 `client-request` JSON，校验 `rpcId` 回显。
- **事件流**：`/api/events.mux` + `/api/events.host` 两个 downlink-only WebSocket（每帧一个 `server-request` JSON）。

相比之下 WebView 方案通过 `dsh-lan-access` 注入 `crypto.randomUUID` polyfill + 窄屏适配，原生客户端则**完全不需要**这些。

## 模块结构

```
com.dshmobile/
├── app/
│   ├── DshApp.kt              # Application 单例：持有进程级 DshClient
│   ├── MainActivity.kt        # 连接屏（host:port/protocol → create+start DshClient → 就绪握手 → 会话页）
│   └── ConversationActivity.kt # 会话列表 + 打开会话 + 发送 prompt + 实时流式 + 审批 + 模型选择
└── protocol/
    ├── Rpc.kt                 # 四象限 RPC envelope + 判别错误体 + 解析/构造
    ├── DshClient.kt           # HTTP POST /api unary + 双 WebSocket downlink + 就绪握手/重连 + 方法目录 + respond
    └── Models.kt              # host/session/workspace/model/事件帧 Kotlin 数据类
```

## 数据流

```
[MainActivity]  connect(host:port) -> new DshClient(baseUrl).hostDescribe()  就绪
        | 成功
        v
[ConversationActivity]  -> sessionList() / sessionHistory(sid)
        |                   openSession(sid)
        v
    DshClient.start()  -> open /api/events.mux + /api/events.host
        |                   downlink frames (envelope rpcId, payload)
        v
    渲染: session/event -> handleEvent (assistant/chunk 按 content-block index 分块:
          block-start → text-delta… → block-end(以完整文本落定)，text 与 tool-call 各一个 block)
          approval/requested -> respondApproval(rpcId, sessionId, approvalId, approve)
          question/requested -> respondQuestion(rpcId, cancelled)
          session/jobs -> renderJobs
```

## 就绪握手 & 重连

- `DshClient.start()`：建立两个 downlink WebSocket + 一次 `host.describe()`。
- `host.describe` 成功 → `connected`，回调 `onConnected(describeValue)`。
- 失败 → `reconnecting`，指数退避（base 500ms、倍率 2、上限 10s + 抖动）。

## 远程访问 / 配置平面 403

- unary 业务接口（会话、模型、工作区、事件流）远程可用。
- **配置平面**（`settings.*`、`credentials.*`、`agentPreset` 写、`host.pickDirectory/openPath`、`llm.discoverModels`）**仅回环可访问**（`PRIVILEGED_METHODS`）。
- 远程合规方案：SSH 端口转发 `ssh -L 3080:127.0.0.1:3080 用户@电脑IP`，客户端填 `http://127.0.0.1:3080`（从服务端视角仍是回环）。

## 已实现的核心交互

| 交互 | 协议方法 | 状态 |
|---|---|---|
| 连接/就绪 | host.describe | ✅ |
| 会话列表 | session.list | ✅ |
| 打开会话/历史 | session.history | ✅ |
| 发送消息 | session.prompt (queue/steer) | ✅ |
| 实时流式 | /api/events.mux → assistant/chunk | ✅ |
| 模型选择 | session.models / session.selectModel | ✅ |
| 工具审批 | approval/requested → respondApproval | ✅ |
| 用户提问 | question/requested → respondQuestion | ✅ |
| 任务/进度 | session/jobs → renderJobs | ✅ |
| 工作区浏览 | workspace.list | 已接入协议层，UI 待补 |
| 全局状态 | /api/events.host | 已接入协议层，UI 待补 |

## 说明

- 所有网络调用在后台线程执行（`Thread { ... }`），UI 通过 `Handler(Looper.getMainLooper())` 回主线程刷新。
- 事件流回调带 `(envelope rpcId, payload)`，`rpcId` 用于应答 `server-request`（审批/提问）。
- 依赖 OkHttp（`com.squareup.okhttp3:okhttp:4.12.0`）；JSON 用 Android 内置 `org.json`，无额外序列化库。
