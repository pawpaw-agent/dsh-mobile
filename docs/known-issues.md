# 已知待修 / 行为记录

> 记录时间：0.1.2（versionCode 29）。本版把项目从 `dsh-mobile` 更名为 `dsh-handheld`，
> 并把此前 1.10.0 / 1.11.0 / 1.11.1 的开发迭代号统一归到 0.1.x 公开版本线上。
> 背景：1.10.0 把 SSH 隧道所有权上移到 `DshApp`，1.11.0 删除了后台通知与其前台服务，
> 0.1.2 删除了因此变成死代码的 `DshClient` / `Models` / `Rpc`（850 行）与 okhttp3 依赖。
> 这几个版本的**真机验证尚未完成**（等待手机恢复无线调试），下面两条是按代码推导出的
> 待验证/待修项，**暂不改动**，避免在上机验证前叠加未验证的改动。

---

## 一、App 重新打开时的判定逻辑（现状）

`MainActivity.onCreate`（约 306-321 行）三分支，状态源只有两个 SharedPreferences 键：

```
savedUrl   = prefs["url"]           ← 最后一次 connectWeb 写下的 http://127.0.0.1:<port>
currentUrl = retainedWebView?.url   ← 进程内保活 WebView 的当前地址
baseMatch  = currentUrl 以 savedUrl 开头
```

| 条件 | 行为 |
|---|---|
| `currentUrl` 已是 http 页且匹配 `savedUrl` | 连接屏 `GONE`，直接回网页：不重连、不重载、不换 token |
| 有 `savedUrl`、不匹配、且 `ssh_json` 完整 | `autoConnectSsh()`：重建隧道 + 重新捞 token + 带 `?token=` 加载 |
| 其余（无 `url` / 无 ssh 配置） | 停在连接屏 |

- **进程活着**（退后台未被杀）：复用 `DshApp.retainedWebView` 与 `DshApp.sshTunnel`，零成本。
- **进程被杀**（1.11.0 起无前台服务，这是常态）：完整冷启动重连（1-3s 拨号 + 页面重载）。
  因本地端口固定为 3080，origin 不变，localStorage/IndexedDB 状态不丢。
- **BACK 语义**：网页有历史先退历史；无历史回连接屏 Step 2 且**隧道保持**；连接屏再 BACK
  = `moveTaskToBack`，隧道**仍然保持**。隧道只由「断开连接」或进程结束关闭。

---

## 二、待修：`断开连接` 后重开可能白屏死路

**症状**：`断开连接` 后若 Activity 被系统销毁而**进程仍存活**（"不保留活动"、后台回收
Activity 而保留进程等），重新打开 App 是一个空白 WebView，既没有连接屏入口，按 BACK 也
只会 `moveTaskToBack`。

**根因**：`断开连接` 删掉 `prefs["url"]` 并把 WebView 载向 `about:blank`。重开时

- `savedUrl == null` → 第二个分支（自动重连）跳过；
- `currentUrl == "about:blank"` **非空** → `else if (!currentUrl.isNullOrBlank())` 命中 →
  `connectView.visibility = GONE`。

**修法**（一行）：

```kotlin
// MainActivity.onCreate
} else if (!currentUrl.isNullOrBlank()) {          // ← 对 about:blank 也成立
} else if (currentUrl?.startsWith("http") == true) { // ← 收紧为正式页面
```

**力场**：窄，但一旦命中就是死路（无 UI 出口），修复零风险。等 1.11.0 上机验证后一起改。

---

## 三、待定：后台掉线、同端口重建时不自动重载

`SshTunnel` 的看门狗线程（`ssh-tunnel-watchdog`）重建 dbclient 时，若候选端口 3080 仍空闲
就会重新绑回 3080 → `localBaseUrl` 未变 → **不触发** `onLocalBaseChanged` → `MainActivity`
不重载页面。回前台看到的是掉线前的旧页面（origin 未变，localStorage 不受影响）。

- 页面自身若重连（前端 SSE/WS 重试）则无感；
- 否则需要用户手动刷新。

**可能的修法**：`onResume` 时校验隧道健康度（`sshTunnel?.isHealthy()`），不健康或刚重建过
则重新 `connectWeb(lastUrl)`。需真机确认重载时机是否会打扰正在输入的用户。

---

## 四、日志体系：已补关键路径，仍缺取证入口

### 补之前的实况

全项目只有 **28 处** `Log.*`，`Log.d`/`Log.v` 各 0 处，分布在 4 个 TAG
（`DshHandheld` / `DshApp` / `SshTunnel` / `TuiActivity`）。按文件看问题很集中：

| 文件 | 行数 | 补前 Log | 补后 |
|---|---|---|---|
| `SshTunnel.kt` | 303 | 10 | 10（本来就够） |
| `TuiActivity.kt` | 409 | 9 | 9 |
| `DshApp.kt` | 157 | 5 | 5（本来就够） |
| `MainActivity.kt` | 1376 | **4** | **29** |

规律：**有日志的 `SshTunnel` 是唯一被成功定位过的那一层**（双 dbclient 抢 3080 就是靠它
一条 warn 直接定性的）；而 431、白屏、回退后停 Step3 这些只能靠 CDP 手工挖的问题，
全都落在当时零日志的 `MainActivity` 里。

### 1.11.1 补了什么（commit `2609b445`，远端）

**只加日志与注释，行为零变化**：`onCreate` 三分支判定、`onPageFinished` 的 ack 迁移、
`onReceivedError`/`onReceivedHttpError` 的原始 code/desc/reason、`connectWeb` 的
`needsToken`/`tokenLen`/是否清 cookie jar、`connectViaSsh`+`autoConnectSsh` 的配置与
`ensureTunnel` 结果、`handleUnauthorized` 的两段回退、五个生命周期回调与 `onBackPressed`
四个分支、`disconnectCurrent`/`showConnectScreen`/`refreshConnectState` 的按钮可见性。

**安全**：去掉 `autoFetchToken` 的 `token.take(8)` 令牌前缀泄漏；`TuiActivity` 的
`env` 改为只记键名（原 `it.take(8)` 恰好只截到 `DROPBEAR` 这个键名而侥幸没漏密码）。

### 仍未做（当时评估为可延后）

- **应用内日志查看器**（环形缓冲 + 连接屏入口）：手机上出问题时唯一证据仍是 `status()`
  那一行给用户看的、刻意去术语的文案；看 logcat 需要电脑 adb，而 adb 恰恰最常不可用。
  这是**取证链路**最后的缺口。
- **分级与开关**：仍无 `Log.d/v`，想临时加详细日志 = 改代码 + CI + 安装（十几分钟），
  所以实际上没人会为一次排查去做。
- **`BuildConfig.DEBUG` 守卫**：release 里日志照留。对本项目**有意保留**——用户只装
  debug 包，release 日志反而是资产。
- **清理死代码**：`protocol/DshClient.kt`(505) + `Models.kt`(237) + `Rpc.kt`(108) 外部
  零引用（删掉通知客户端后的孤岛），`okhttp3` 依赖只被 `DshClient.kt` 用，可一并删。

### 排查备忘：验证 APK 里的日志字符串

app 自身的类在 **`classes6.dex`**（不是 `classes.dex`，那里是 Kotlin/AndroidX）：

```bash
unzip -p app-debug.apk classes6.dex | grep -a -o "onCreate: savedUrl" | wc -l
# 1 = 含 1.11.1 的日志；v1110 为 0，且 v1110 的 prefix= 命中 1、v1111 命中 0
# （证明 token 前缀泄漏确实被移除）
```

因此该包即使 `versionCode` 与 1.11.0 同为 28，也能靠 `onCreate: savedUrl` 是否存在自我标识。
