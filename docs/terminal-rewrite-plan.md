# 终端自研计划（Termux 替换）

> 状态：**阶段 0 已完成并合入**（差异测试台，CI 门禁已生效）；
> 阶段 1–5 未开工。目标是把 SSH 终端模式里依赖 Termux 的三层
> （native pty / emulator 核心 / 视图层）全部换成自研实现。
>
> 阶段 0 的实测结果见 [`android/terminal-conformance/README.md`](../android/terminal-conformance/README.md)。
>
> 背景与动机见本文「为什么」；许可证方面的结论（**其实不必为了许可证重写**）见
> [`LICENSE-audit`](#附许可证核对结论)。

---

## 一、现状与目标

### 现在依赖 Termux 的四块

| 模块 | 规模 | 内容 |
|---|---|---|
| `terminal-emulator` | **266,881 B / 14 文件** | `TerminalEmulator`(131 KB, VT/xterm 状态机)、`WcWidth`(39.9 KB, Unicode 宽度表)、`TerminalBuffer`(23.8 KB)、`KeyHandler`(18 KB)、`TerminalSession`(14.7 KB)、`TerminalRow`(14 KB)、其余 8 个 |
| `terminal-view` | **86,510 B / 4 文件** | `TerminalView`(68 KB, 渲染+输入)、`TerminalRenderer`(12.8 KB)、`GestureAndScaleRecognizer`(3.7 KB) |
| `textselection` | **27,013 B / 3 文件** | 选择手柄与 ActionMode 集成 |
| `libtermux.so` | 9,928 B（arm64） | 5 个 JNI：`createSubprocess` / `setPtyWindowSize` / `setPtyUTF8Mode` / `waitFor` / `close` |
| （已 vendored）`extrakeys` | 1,493 行 / 7 文件 | 我们从 Termux 拷进来并已改过 2 处（`rowSpan`、字号） |

**合计待替换：约 38 万字节 Java（≈9,000–10,000 行）+ 约 200 行 C。**

### 自研后应达到

- 接口面积从「43 个 override、其中 19 个空实现」降到按需的 ~10 个回调
- 6 处已知 API 陷阱消失（见「附录 A」）
- 不再需要在升级 Termux 时重打我们的补丁
- 屏幕缓冲区成为**结构化、可读**的数据 —— 为日后的终端内搜索 / 链接跳转 /
  把 dsh 输出投射成原生 UI 留出可能
- 渲染与 App 自身设计语言统一，而非靠零散配置去凑

### 非目标

- 不改 SSH 层：继续用 Dropbear `dbclient`（MIT），本计划不涉及自研 SSH 协议
- 不改 WebView 模式（dsh 官方前端）
- 不改已自研的额外键栏交互逻辑（仅把 vendored 的 Termux 实现重写掉）

---

## 二、关键使能：差异测试台（先做这个）

**这是整个计划能否成立的前提。** 自研终端最大的风险不是「写不出来」，而是
**正确性长尾**——vim / htop / tmux / dsh-tui 会把每个 escape 序列的 bug 都翻出来，
而本项目**没有真机自动化测试**，靠肉眼比对无法收敛。

解法：**把 Termux 当黑盒对照，做逐格差异测试。**

已验证可行（本次调查实测）：

| 验证项 | 结果 |
|---|---|
| emulator 核心的 Android 依赖 | `TerminalBuffer` / `TerminalRow` / `KeyHandler` / `WcWidth` / `TextStyle` / `ByteQueue` / `TerminalOutput` = **0 个**；`TerminalEmulator` / `TerminalColors` 各 **1 个**（`android.util.Base64`，且**全文件只用在 1 处**：OSC 52 剪贴板，`TerminalEmulator.java:2110`） |
| 参照实现可否直接跑 | 可以——`terminal-emulator.aar` 里的 `classes.jar` 仅 **63,477 B / 20 个类**，纯 JVM 可加载 |
| 需要多少 shim | 一个 `android.util.Base64` 桩类（`encode`/`decode` 两个静态方法）即可让整套 emulator 在 JVM 上运行 |
| 语料从哪来 | `script -q -c "<cmd>" /dev/null` 分配 pty 并原样录下字节流；已实测抓到 `man ls` 9,908 B、`ls --color=always` 5,912 B、`htop` 3,981 B、`top -b` 2,044 B、`vi` 1,970 B |
| 跑在哪 | CI（已有 Temurin JDK 17）。本机无 JDK，不在本地跑 |

### 测试台形态

```
android/terminal-conformance/          # 纯 JVM 测试模块（不进 APK）
  src/main/java/.../Shim.java          # android.util.Base64 桩
  src/main/java/.../RefEmulator.java   # 包装 Termux classes.jar（黑盒调用）
  src/main/java/.../Cursor.java        # 从任一端读出「屏幕快照」
  src/test/java/.../ConformanceTest.java
  corpus/*.bin                         # 录制的字节流
```

**判定方式**：同一段字节流分别喂给两边，逐格比较 字符 / 前景色 / 背景色 / 属性 / 光标位置 / 回滚缓冲；
任何一格不同即失败，并打印可读的并排 diff（把两边的屏幕 dump 成带颜色的文本）。

**为什么放最前**：它定义了「什么叫做对了」。没有它，后面每一行代码都无法验证。

### 关于 clean-room

**建议：从规范写，把 Termux 只当黑盒 oracle。**

- 依据规范：xterm [ctlseqs](https://invisible-island.net/xterm/ctlseqs/ctlseqs.html)、
  ECMA-48、VT510 手册、Unicode 数据文件
- **不逐行阅读 Termux 源码再「改写」**——那会让自研代码成为其衍生作品，
  Apache-2.0 的署名义务仍然适用（虽然不再是 GPL）
- 测试时只调用其编译产物、比较输出，属黑盒行为对照，干净且是行业常规做法

好处不止法律：照规范写能学到「应该是什么样」，而不是继承「Termux 是什么样」。

---

## 三、分阶段计划

每阶段都有**可验证的完成标志**，且尽量做到「不破坏现有可用版本」。

### 阶段 0：差异测试台 ✅ 已完成

| | |
|---|---|
| 产出 | `android/terminal-conformance`（纯 JVM 模块）+ 52 用例语料 + CI 任务 `conformance` |
| 状态 | **已合入**（commit `fac7862`），CI 全绿 |
| 完成标志 | ① Termux 对自己 = **0 差异**（`check` 52/52，分片喂入下逐片一致）✅；② 语料含 **12 个真实程序** pty 录制（ls/man/top/htop/vi/less/git/dpkg/find/bash/tput/watch）✅；③ CI 一条命令给出「N/M 一致」✅ |
| 额外收获 | `selftest` 证明测试台**会失败**——这条最初不通过（49/52），暴露出三个真实缺陷：只比末屏会漏中间状态、一次性喂入没测序列跨包、覆盖率统计用错解码。详见模块 README |
| 规模 | 实际约 1,500 行 Java + 290 行生成器 + 54 行捕获脚本 |

**阶段 0 让后面每个阶段都有判据**：`coverage` 防止语料空转，`selftest` 防止测试台
失去失败能力，`check` 给出「N/M 一致」。加入自研实现只需在
`Harness.implementations()` 注册一行。

### 阶段 1：native pty

| | |
|---|---|
| 产出 | 自研 `libdshtty.so`，5 个 JNI 函数约 200 行 C |
| 关键点 | `posix_openpt`/`grantpt`/`unlockpt` → `fork` → `setsid` → `ioctl(TIOCSCTTY)` → `execvp`；`TIOCSWINSZ` 传窗口尺寸（**必须有本地 pty**，否则 dbclient 拿不到尺寸、远端只能 80×24） |
| 完成标志 | ① 自研 `.so` 能起 `/system/bin/sh` 并正确回显；② 改尺寸后 `TIOCGWINSZ` 立即反映；③ 反复开关 200 次无 fd 泄漏；④ CI 用 NDK 构建（CI 已在为 dropbear 拉 NDK，可复用） |
| 规模 | 小 |
| 风险 | **低**——路径成熟，且与其它阶段解耦，可独立上线 |

### 阶段 2：emulator 核心（主体，约 60% 工作量）

按「能力」切子阶段，**每个子阶段以语料覆盖率作为放行条件**：

| 子阶段 | 内容 | 放行条件（对照 Termux 全绿） |
|---|---|---|
| 2a | 屏幕模型、C0 控制符（BS/HT/LF/CR/BEL）、可打印字符、自动换行、滚动 | 基础语料 + `ls` |
| 2b | CSI 光标移动（CUU/CUD/CUF/CUB/CUP/HVP）、擦除（ED/EL/ECH）、插入删除（ICH/DCH/IL/DL） | `top -b` |
| 2c | SGR 属性 + 16 色 + 256 色 + truecolor | `ls --color` |
| 2d | 滚动区域（DECSTBM）、origin 模式、保存/恢复光标（DECSC/DECRC） | `vi` |
| 2e | 备用屏（?1049/?47/?1047）、回滚缓冲 | `vi`、`htop` |
| 2f | DECSET/DECRST 全套：4 种鼠标协议、括号粘贴、焦点事件、自动换行、反显、应用光标键/键盘、DECLRMM | `htop`、`tmux` |
| 2g | **DEC 特殊图形字符集**（画线字符）——ncurses 画框全靠它 | `htop`、`tmux`、`man` |
| 2h | OSC：标题、颜色查询（4/10/11）、剪贴板（52）、超链接（8） | `man` |
| 2i | UTF-8 解码（含跨包不完整序列）、宽字符/组合字符 → 需**生成** Unicode 宽度表（替代 39.9 KB 的 `WcWidth`） | 中文语料 + `dsh-tui` |
| 2j | DCS / DECRQSS / 终端查询响应 | 按需 |

| | |
|---|---|
| 完成标志 | 语料全绿 + `dsh-tui` / `vim` / `htop` / `tmux` / `man` 实机肉眼对齐 |
| 规模 | **大**——`TerminalEmulator` 单体 131 KB，是十年打磨的状态机 |
| 风险 | **高**（长尾在此）。缓解手段就是阶段 0 的覆盖率数字——把「感觉差不多」变成「N/M 一致」 |

### 阶段 3：会话 / IO 层

| | |
|---|---|
| 内容 | 读线程、字节队列、UTF-8 跨包缓冲、尺寸变化传播、进程退出、自研的精简客户端接口（目测 5–6 个回调：`onTextChanged` / `onTitleChanged` / `onSessionFinished` / `onClipboardCopy` / `onClipboardPaste` / `onBell`） |
| 注意 | 现在**必须手动**在 `onTextChanged` 里调 `onScreenUpdated()`，否则打字卡顿——自研时应把「数据到达 → 标脏 → 重绘」做成内部自动，不暴露给调用方 |
| 完成标志 | 跑 `sh`/`top` 正常；改尺寸立刻生效；退出码与结束状态正确回传 |
| 规模 | 中 |
| 风险 | 低–中 |

### 阶段 4：视图层

| | |
|---|---|
| 内容 | ① 单元格网格渲染器（字体度量、等宽单元格、**按 run 绘制**而非逐字符、光标样式）；② 触摸（点击弹键盘、拖动滚动+惯性、长按选择）；③ 文本选择 + 复制粘贴（接 ActionMode）；④ 输入法（按键/码点/组合输入/物理键盘/修饰键状态）；⑤ 与 App 集成（安全区、主题、额外键栏） |
| 难点 | **IME 是仅次于 emulator 的坑**——Termux 的代码里攒了三星/Gboard 等一堆机型修复（`shouldEnforceCharBasedInput`、`shouldUseCtrlSpaceWorkaround` 就是痕迹）。这部分我们会重新踩一遍 |
| 完成标志 | 与 Termux 版并排截图对比：字形/颜色/光标一致；打字无卡顿；真机（三星 + 至少一台其它品牌）输入法正常 |
| 规模 | 中–大（~113 KB 待替换） |
| 风险 | 中（IME 部分偏高） |

### 阶段 5：切换与移除

| | |
|---|---|
| 内容 | ① 用开关让两套实现并存对比；② 切换默认；③ 删除 Termux 依赖与 vendored 文件；④ 自研额外键栏（替代 vendored 的 1,493 行）；⑤ 更新 README / 许可 / 归属声明 |
| 完成标志 | APK 内 `com.termux.*` 类数 = **0**；实机回归通过；许可文件更新 |
| 规模 | 小 |
| 风险 | 低 |

---

## 四、工程结构调整

当前是单模块 `:app`。为让核心可测，需拆成：

```
android/
  terminal-core/         java-library，纯 JVM，零 Android 依赖   ← emulator + 会话模型
  terminal-view/         Android library                        ← 渲染 + 输入 + 选择
  terminal-conformance/  java-library，仅测试                    ← 差异测试台
  app/                   Android application                     ← TuiActivity / MainActivity
```

**关键约束：`terminal-core` 不得出现任何 `import android.*`** —— 由 CI 静态检查强制。
这条纪律是阶段 0 能成立的前提；一旦破了，JVM 测试就没了。

ndk 构建放 `terminal-core/src/main/cpp`（或独立 `terminal-pty`），CI 复用现有 NDK 拉取步骤。

---

## 五、规模与顺序

相对工作量（非日历时间）：

| 阶段 | 占比 | 依赖 |
|---|---|---|
| 0 差异测试台 | 5% | —— |
| 1 native pty | 5% | —— |
| 2 emulator 核心 | **60%** | 0 |
| 3 会话/IO | 10% | 1, 2 |
| 4 视图层 | 15% | 2, 3 |
| 5 切换移除 | 5% | 全部 |

阶段 0 与 1 可并行；阶段 2 是唯一的长杆。**建议逐个阶段独立合并**，
每阶段结束时 App 都保持可用（阶段 5 之前 Termux 仍在，只是多了一套并存的实现）。

---

## 六、风险登记

| 风险 | 等级 | 缓解 |
|---|---|---|
| escape 序列长尾（罕见程序） | **高** | 阶段 0 覆盖率数字；语料持续从真实使用中补录 |
| 性能：快速输出（`yes`、大文件 `cat`）下的吞吐与重绘 | 中 | 参考成熟的「脏行 / damage」模型；阶段 2 就加吞吐基准，别留到最后 |
| IME 机型兼容 | 中–高 | 早做（阶段 4 提前验证），保留 Termux 版可回退对比 |
| 无真机自动化测试 | 中 | 差异测试台跑在 CI；实机部分建一个可复现的检查脚本（adb 驱动，如本次调查所用） |
| 工作量比预期大 | 中 | 分阶段合并，任何阶段可停；停下时旧实现仍在，不会烂尾 |
| Unicode 宽度表维护 | 低 | 从 Unicode 数据文件生成，不手写 |

---

## 七、可以不做的部分（供权衡）

- **`WcWidth` 的 39.9 KB 表**：是数据不是逻辑，生成即可，不必「自研」
- **额外键栏**：已 vendored 且我们改过；若只为「自主可控」，重写它收益最小（1,493 行、纯 GridLayout）
- **native pty**：200 行 C，但它是唯一**必须**有 native 的部分（pty 无法在 Java 层创建）

---

## 附录 A：现在踩过的 6 个 API 陷阱

自研后应全部消失（记录在此以免重蹈）：

| 陷阱 | 现象 |
|---|---|
| `setTextSize()` | KDoc 说 dp，**实际吃 px** |
| `setTerminalCursorBlinkerRate()` | **必须先调**，否则随后的 `setState(true,true)` 静默失效、光标不闪 |
| `onTextChanged` | 不手动调 `onScreenUpdated()` → 字节进了 emulator 但无人重绘 → **打字卡顿**（只能等 500 ms 光标闪烁蹭刷） |
| `requestFocus()` | 不够，必须显式 `showSoftInput` 才弹键盘 |
| `isAcceptingText` | 焦点在 TerminalView 时**恒为 true**，据此判断会误判 |
| `onScale` | 必须返回 `1f` 关掉它自带的缩放手势 |

## 附录 B：许可证核对结论

**结论：为了许可证，其实不必重写。**

依据（2026-09-11 核对）：

- Termux 根 `LICENSE.md`：整个仓库 GPLv3，但 **`terminal-view` 与 `terminal-emulator` 在「Exceptions」中列为 Apache-2.0**
- 上游 [jackpal/Android-Terminal-Emulator](https://github.com/jackpal/Android-Terminal-Emulator)：Apache-2.0，仓库带 `LICENSE` + `MODULE_LICENSE_APACHE2` + `NOTICE`
- 两个模块源码文件：**无任何 GPL 头**（逐个 grep 确认）
- `termux-shared/LICENSE.md`：库为 **MIT**；GPLv3-only 仅限 `com/termux/shared/termux/*`——而我们 vendored 的是 `com/termux/shared/terminal/io/extrakeys/`，**不在该范围内**

APK 逐类核对（78 个 `com.termux.*` 类）：

| 包 | 类数 | 许可 |
|---|---|---|
| `com.termux.terminal` | 21 | Apache-2.0 |
| `com.termux.view` + `.textselection` | 22 | Apache-2.0 |
| `com.termux.shared.terminal.io.extrakeys` | 35 | MIT |
| `com.termux.shared.termux.*`（真正的 GPLv3-only） | **0** | —— |

因此项目**本可**改用 Apache-2.0 / MIT，只需保留 Termux 与 ATE 的归属声明。

⇒ 本计划的价值在于**自主可控与可扩展性**，不是解除许可证约束。
若哪天决定不做，改许可 + 补 NOTICE 是十几分钟的事，不必写一行代码。
