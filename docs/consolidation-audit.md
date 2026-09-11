# 合并与精简审计（2026-09-11）

对全仓库（127 个受版本控制的文件、1.29 MB、约 4 000 行代码 + 1 500 行文档）做的
一次性精简审计。**所有条目都带 `file:line` 证据**；标 `~` 的是估算，其余是实测。
本文件是一次性工作清单，执行完可删（或留作记录）。

---

## 结论速览

| # | 项 | 净减少 | 风险 | 需要真机？ |
|---|---|---|---|---|
| 1 | 删除零引用文件与死符号 | ~250 行 / 7 个文件 | **零** | 否 |
| 2 | MainActivity 内重复代码合并 | ~120 行 | 低 | 否 |
| 3 | `ssh_json` / prefs / 颜色三处跨文件重复收敛 | ~60 行 | 低 | 否 |
| 4 | 文档精简（含 1 份需归档的 320 行规格） | ~500 行 | 零 | 否 |
| 5 | 构建与 CI（Jetifier、Gradle 版本单一出处） | 构建时间 | 低 | 否 |
| 6 | vendored Termux 就地裁剪 | ~450 行 | 中 | **是** |
| 7 | `MainActivity.createConnectView` 拆分 | ~480 行 | 高 | **是** |

顺带查出 **2 个真问题**（§3）：一个文案错位的 UI bug，一个 `known_hosts` 落两份的
配置不一致。两者都在"反正要改"的代码里，合并时顺手修掉。

**一条重要反结论**（§4）：**不要**改成依赖 `termux-shared` 来消掉那 1 493 行 vendored
代码——实测会拽进 AppCompat + Material + Guava + markdown 渲染器，并丢掉 `rowSpan`。

---

## 1. 可直接删（零风险）

| 对象 | 位置 | 行数 | 证据 |
|---|---|---|---|
| `colors.xml` **13 个颜色全部零引用** | `android/app/src/main/res/values/colors.xml` | 16 | 逐个 grep `res/` + `java/`：13 个名字在 colors.xml 之外命中 **0** 次 |
| `ic_status_dot.xml` | `res/drawable/` | 9 | 全仓引用数 0 |
| `scripts/build-apk.sh` | `scripts/` | 2 | 唯一引用是 README 的项目结构树；CI 不调它，且它调裸 `gradle`（与 wrapper 冲突） |
| `.codegraph/` 目录 | 仓库根 | 1 | 目录里只有一个 229 B 的 `.gitignore`，无任何数据文件 |
| `COL_SURFACE` | `MainActivity.kt:122` | 1 | 全模块唯一出现处即声明 |
| `COL_INPUT_BG` | `MainActivity.kt:128` | 1 | 同上 |
| `PREF_SSH_ENABLED` | `MainActivity.kt:136` + `:731` | 2 | 只写不读（`:731` 是唯一写入，无任何读取）；直连模式已移除的遗留 |
| `forceToken` 参数 | `MainActivity.kt:971,979,982` | 1 | 8 个调用点全部单参调用，默认值永远为 false，`979` 的 `\|\|` 左半恒假 |
| `autoConnectSsh(savedUrl)` 参数 | `MainActivity.kt:349` | 1 | 自带 `@Suppress("UNUSED_PARAMETER")`，唯一调用点 `:335` |
| `onTunnelState` 空实现 + 整条通知通道 | `MainActivity.kt:70-74` + `DshApp.kt:44,94` | ~8 | 全仓无非空实现；这是一条只写不读的状态通道 |
| `package.json` | 仓库根 | 5 | 只有 name/version/private/description，**没有 scripts**；`version` 还停在 `0.1.3`（app 已是 `0.1.4`） |

> `COL_HINT`（`MainActivity.kt:127`）**不是**死代码（`:431,1080,1087` 在用），别一起删。

---

## 2. 重复代码与可合并处（低风险）

### 2.1 `MainActivity.kt` 内部

| 重复 | 位置 | 可省 | 说明 |
|---|---|---|---|
| `rowParams` 定义两份，函数体逐字相同 | `:468` 与 `:1439` | 3 | 局部那份遮蔽了成员那份，纯冗余 |
| 按钮 chrome 重复 12 次（text/isAllCaps/textSize/颜色/背景） | `:474,610,665,717,725,793,825,851,1132,1139,1202,1209` | ~34 | 三种样式（主/次/错误）各抽一个工厂函数 |
| 错误页与令牌页是同一脚手架的两份拷贝 | `:1111-1152` 与 `:1181-1223` | ~35 | 连点击闭包都逐字相同（`:1137` ≡ `:1216`）；**且藏着一个 bug，见 §3.1** |
| `ssh_json` 三种解析写法 | `:325-327`（runCatching+校验）、`:350-355`（try/catch+另一处校验）、`:532-534`（try/catch 无校验） | ~8 | 收敛成一个 `readSshConfig()` |
| `guideLineDone` 与 `guideLine(i,t)` 默认行为完全等价 | `:1358-1362` vs `:1353-1357` | 7 | `guideLineFail` 只差一个颜色 |
| `resetGuideLines` 与 `guideStep3Show` 内联段逐字相同 | `:1420-1422` vs `:1342-1344` | 3 | — |
| 引导页 Step3 三行 TextView chrome 相同 | `:766-771, 772-777, 778-783` | ~10 | 只有文字不同 |
| `label` / `stepLabel` / `stepHint` 三个 TextView 工厂近乎同构 | `:419-424, 537-542, 543-547` | ~7 | 合并为一个带参工厂 |
| 令牌保存 → 回主线程 → 连接，写了 3 遍 | `:382-392, 1026-1037, 1236-1246` | ~10 | 且 `:1238` 的 `token.length < 40` 前半段不可达（`autoFetchToken:951` 已保证） |
| 连接前的冗余状态写入 | `:78-79` 与 `:385-386` 重复了 `:974`/`:977` | 4 | 同效果重复赋值 |

### 2.2 跨文件

| 重复 | 位置 | 说明 |
|---|---|---|
| `ssh_json` 的字段名字面量散落 | `DshApp.kt:124-146`、`TuiActivity.kt:173-177,278`、`MainActivity.kt:327-367,625-708,864-867,1047-1058` | `"sshHost"`/`"authType"`/`"password"` 等至少 7 个键、4 个文件各写一遍，加字段要改 4 处 |
| prefs 文件名 `"dsh-handheld"` | `MainActivity.kt:189`、`TuiActivity.kt:101,170,276` | 4 处字面量 |
| prefs 键 `"url"` / `"ssh_json"` | `"url"` ×5、`"ssh_json"` ×6 | 有常量的只有那两个死常量（§1） |
| **颜色定义了三份** | `values/colors.xml`（13 个，全零引用）、`MainActivity.kt:121-131`、`TuiActivity.kt:55-58`，另有 8 个 `bg_*.xml` 里再写一遍十六进制 | `#0A0A0E`/`#F5F5F7`/`#99FFFFFF` 在 Kotlin 里重复定义（两个 Activity 各一份），XML 里第三份 |
| 两个推送脚本的样板 | `scripts/push-via-api.py`（163 行）与 `mirror-via-api.py`（129 行） | 逐字相同的非空行 **44 行**（`REPO`/`API`/`TOKEN`/`gh()`）；抽一个 `scripts/ghapi.py` 即可 |

### 2.3 建议的落点

- `UiKit.kt`：颜色常量 + `dp`/`rowParams`/`spacer` + 按钮/输入框/文本工厂（吃下 2.1 的 4 条，~120 行）
- `SshPrefs.kt`：`ssh_json` 读写编解码（吃下 2.1 与 2.2 各一条，主仓 ~30 行 + TuiActivity ~10 行）
- `scripts/ghapi.py`：两个推送脚本共用（~40 行）

---

## 3. 顺手修掉的两个真问题

### 3.1 错误页与令牌页共用 `errorView`，文案会错位（真 bug）

`showErrorPage` 与 `showTokenPromptPage` 都只在 `errorView == null` 时构建，之后共用同一实例：

- `MainActivity.kt:1112` / `:1182`：`if (errorView == null)` —— 谁先被调用，谁决定标题与副标题；
- `:1149-1150`：只有 `showErrorPage` 会把消息写进 `getChildAt(1)`；`showTokenPromptPage` 从不写文字。

后果：401 先到、网络错误后到时，看到的是「需要访问令牌」标题配一条早已过期的
`HTTP xxx` 副标题（反向同理）。`hideErrorPage` 只切 `visibility`，不会重建。
修法就是 §2.1 那条合并：一个 `buildOverlay(title, subtitle, primary, secondary)`，
每次显示都重写文本。

### 3.2 `known_hosts` 落在两个 HOME（真不一致）

```
protocol/SshTunnel.kt:230   "HOME" to (System.getenv("HOME") ?: "/data/data/com.dshhandheld.app")
app/TuiActivity.kt:193-194  val homeDir = filesDir.absolutePath        // /data/data/…/files
                            arrayOf("HOME=$homeDir", …)
```

`SshTunnel.kt:227` 的注释明确写着「与终端模式一致的认证 env（HOME 写 known_hosts）」，
实际两条路径不同：终端模式的 known_hosts 在 `files/`，隧道模式的在
`System.getenv("HOME")`（应用进程里通常是 `/` 或 null）。后果是 TOFU 信任在两个模式之间
不共享，同一个主机可能被"首次接受"两次。修法是让两边都用 `context.filesDir`
（`SshTunnel` 无 Context，可由 `DshApp` 注入，与 `binPath` 同一模式）。

---

## 4. 依赖 vs 继续 vendoring（实测数据，反结论）

那 7 个 vendored 文件（1 493 行）值得重新评估，因为 **`termux-shared:0.118.1` 确实发布在
JitPack 上，而且确实包含这些类**——"改用依赖就能删掉 1 493 行"在技术上成立：

| 实测项 | 结果 |
|---|---|
| `termux-shared-0.118.1.aar` | 282 584 B，`classes.jar` **130 个类** |
| 其中 `ExtraKeysView.class` | 存在，19 640 B，39 个方法 |
| 其中 `rowSpan` / `getRowSpan` | **不存在**（那是本仓自己加的功能） |
| 其中键排字号定制 | **不存在**（同样是本仓改的） |
| 其中 `com/termux/shared/termux/*` | **15 个类**，正是 GPLv3-only 的那部分 |
| POM 传递依赖 | appcompat 1.3.1、material 1.4.0、**guava 24.1-jre**、core 1.6.0、markdown 4.6.2（strikethrough 等） |

四条代价，任何一条都足以否掉这件事：

1. 拽进 **AppCompat + Material + Guava + markdown 渲染器**，而本项目刻意"不依赖 Material
   Components / AppCompat，直接用平台内置主题"（`values/themes.xml` 注释）；
2. **15 个 GPLv3-only 类进 APK**，恰好是许可证审计里唯一想避开的东西；
3. 丢掉 `rowSpan`（底部 `KEYBOARD` 键的 2×25dp 跨行）与字号定制；
4. 依然要保留一份本地补丁才能拿回 3 —— 那就等于换个方式继续 vendoring。

**结论：继续 vendoring 是对的**，但应就地裁剪（§6）。

### 4.1 顺带要修的文档/许可证表述

| 说法 | 现状 | 实际 |
|---|---|---|
| 7 个头注释都写 "GPL-3.0" | `…/terminal/io/**/*.java` 头部 | `termux-shared/LICENSE.md`：**MIT**，GPLv3-only 只限 `com/termux/shared/termux/*` —— 这些文件不在该目录下 |
| 头注释 + `README.md:193-202`：与 `terminal-view` jar "同包同名、源码覆盖 jar" | 6 个文件都这么写 | **不成立**：`terminal-view` AAR 里只有 19 个类，全部 `com/termux/view/**`，没有这些类。真实原因是上游从未把它发布进 `terminal-view`；所以"升级时需手工合并"的告警也是空的 |
| `README.md:193`：说 "6 个文件" | — | 实为 **7 个**（`extrakeys/` 下 6 个 + `terminal/io/TerminalExtraKeys.java`） |
| `README.md:262-270`：整个项目因 Termux 而 GPL-3.0 | — | 依据存疑（termux-app 自己的 LICENSE.md 把 `terminal-view`/`terminal-emulator` 列为 Apache-2.0 例外）。**改不改项目的许可证是单独决策，本审计不建议顺手改**；但上面两条事实性错误应当修正 |
| 仓库无 `NOTICE` | — | vendored 目录里只有 7 个 `.java`，归属信息全在头注释里（69 行 boilerplate 重复 7 遍，可收敛为一行 + 一个 `NOTICE`） |

---

## 5. 文档精简（~500 行）

| 文件 | 行数 | 处置 |
|---|---|---|
| `docs/terminal-rewrite-plan.md` | 325 | 中止已成定局，260 行"原计划全文"无执行价值 → 压到 ~60 行（保留中止记录 + 附录 A 的 6 个 API 陷阱 + 附录 B 的许可证核对结论），原计划移入 git 历史 |
| `docs/dsh-protocol.md` | 320 | 描述的是**已删除**的原生客户端协议（自述"历史存档"），320 行里没有任何代码引用 → 移 `docs/archive/`，或压成"结论 + 为什么不做"的一页 |
| `docs/known-issues.md` | 125 | 三处过时：开头仍写"记录时间 0.1.2（versionCode 29）"（现 31）；§四的行数表（1376/409 行、28 处日志）与现状不符；"仍未做 → 清理死代码 `DshClient`/`Models`/`Rpc`"在 0.1.2 已完成 → 标注完成或删除 |
| `docs/release-notes-0.1.{2,3,4}.md` | 38+64+45 | "安装"段三步逐字重复、结尾"许可"段三份逐字重复（各约 12 行）→ 抽 `docs/releasing.md`，notes 只留本版差异 |
| 依赖升级理由 | 3 处 | `README.md:165-181`、`app/build.gradle.kts:84-98`、`release-notes-0.1.3.md` 各写一遍 `minCompileSdk` + `kotlin-stdlib metadata` 的理由 → 保留 build.gradle.kts 一处权威版，其余引用它 |
| `README.md` 项目结构树 | `:103-132` | 已与现状不符（漏了 `terminal-conformance/`、`docs/*` 多数文件、`.github/`）→ 要么修正，要么删掉结构树（目录本身自解释） |

---

## 6. vendored Termux 就地裁剪（~450 行，需真机）

只保留当前布局用得到的路径。**12 个 public accessor 在 `com.dshhandheld.*` 里零调用**
（每个都验证过"全仓只出现一次 = 声明处"）：

| 死 accessor | 行 |
|---|---|
| `getExtraKeysViewClient` / `getRepetitiveKeys` / `getSpecialButtons` / `getSpecialButtonsKeys` | `ExtraKeysView.java:222,233,245,251` |
| `setButtonTextColor` / `setButtonActiveTextColor` | `:285,296` |
| `get/setButtonBackgroundColor`、`get/setButtonActiveBackgroundColor` | `:302,307,313,318` |
| `getLongPressTimeout` / `getLongPressRepeatDelay` | `:329,343` |
| `ExtraKeysInfo(String,Map,Map)` 这个无用重载、`SpecialButton.toString()` | `ExtraKeysInfo.java:136`、`SpecialButton.java:56` |

合计 ~60 行代码 + ~14 行空行分隔。另外：

- `ExtraKeysView.java:47-84` 是 Termux app 专属的 38 行 javadoc（讲怎么在 Termux 的
  layout 里 inflate、以及 TermuxTerminalExtraKeys 怎么用）→ 与本项目无关，可删；
- 宏（macro）路径（`ExtraKeyButton` ~25 行 + `TerminalExtraKeys.java:32-51` ~20 行）当前
  布局从不触发 → 可删，但**代价是失去配置能力**，建议保留；
- `SHIFT`/`FN` 两个特殊键状态永远 `isCreated=false`（布局里没有）→ 可保留，成本极低；
- 滑动上弹的 `popup`（`-` → `|`）**在用**，删它要同时改 `TuiActivity.kt:67` 的布局，
  收益 ~58 行，不建议为这点收益动交互。

**结论**：`ExtraKeysView` 674 → ~470–500 行、整包 1 493 → ~1 250 行，行为零变化。
若连 popup/宏一起砍，则 ~1 000 行，但会改交互。**这一批必须真机回归**（键排高度、
长按锁定、方向键连发、`KEYBOARD` 跨行）。

---

## 7. 构建 / CI

| 项 | 现状 | 建议 |
|---|---|---|
| Gradle 版本有**三个出处** | wrapper `8.5`、CI `gradle-version: "8.5"`（`ci.yml:34,100`）、`build-apk.sh` 调裸 `gradle` | CI 改用 `./gradlew`，让 wrapper 成为唯一出处（本地与 CI 必然同版本）；`build-apk.sh` 删除（§1） |
| `android.enableJetifier=true` | `gradle.properties` | **实测两个 Termux AAR 里 `android/support` 出现 0 次**，其余依赖全是 androidx → 可去掉，省掉每次构建的字节码重写 |
| `build-dropbear` 每次 run 都重新 clone + 交叉编译 | `ci.yml:55-76` | 按 `DROPBEAR_VERSION` + `localoptions.h` 哈希缓存（当前单个 run 约 4 分钟，其中这条占大头） |
| `paths:` 过滤 | `ci.yml:6-9` | 不含 `docs/**`（文档改动不触发 CI，合理）；`package.json` 也不触发，但那个文件建议删（§1） |
| `.git` 21 MB | 2 347 个松散对象 19.11 MiB，pack 仅 137.81 KiB | `git gc` 可回收大半；这是**本地仓库**卫生，不影响远端 |
| 工作区 121 MB `apk-dl/` | 18 个历史版本 APK 存档（已 ignore） | 建议清理；里面 `v170/shot.sh` 还引用改名前路径 `/home/xsj/dsh-mobile/…` |

---

## 8. 明确**不要**合并的地方

1. **`MainActivity` 里的 `WebViewClient`/`WebChromeClient` 匿名类**（`:220-291`）——它们读写
   `sshTokenAck`、调 `connectWeb`/`handleUnauthorized`/错误页；抽出去要先造接口，成本大于收益。
2. **令牌/隧道流程**（`:971-1072`、`:1226-1247`）——共享 `sshTokenAck`/`unauthorizedCleanTried`/
   `lastUrl` 三个可变状态，属于同一临界区。
3. **`createConnectView`（481 行）不要现在拆**。它是单文件里最大的一块（可省 ~480 行），
   但它独占 8 个字段（`:87-98`）且回调横跨连接/持久化/跳转，风险最高。
   等 §1、§2 做完、契约缩小之后再动。
4. **不改用 `termux-shared` 依赖**（§4）。
5. **`push-via-api.py` 与 `mirror-via-api.py` 只抽公共 `gh()`，不合并成一个脚本**——一个做
   增量补丁、一个做整树镜像，语义不同（后者会删掉远端多出来的文件），合并会埋雷。
6. **`terminal-conformance/` 不并进 app**——它是纯 JVM、带 `android.*` 桩类，进了 APK 会与
   框架类冲突；CI 里已有不变量在守这件事（`ci.yml:45-53`）。

---

## 9. 建议执行批次

| 批次 | 内容 | 预期 | 前置 |
|---|---|---|---|
| **A** | §1 全部 + §5 文档精简 + `git gc` | 仓库少 ~750 行、少 7 个文件；零行为变化 | 无 |
| **B** | §2（UiKit / SshPrefs / 推送脚本公共段）+ §3 两个 bug | 少 ~180 行，修 1 个 UI bug + 1 个配置不一致 | CI 绿即可 |
| **C** | §7 构建与 CI | 构建更快、Gradle 版本单一出处 | 无 |
| **D** | §6 vendored 裁剪 | 少 ~450 行 | **真机回归** |
| **E** | `createConnectView` 拆分 | 少 ~480 行 | B 完成后 |
| **单独决策** | §4.1 许可证表述（是否重判项目许可证） | — | 需要专门讨论，不建议顺手改 |
