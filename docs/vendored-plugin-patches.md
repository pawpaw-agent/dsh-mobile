# Vendored 插件补丁记录 — dsh-web-mobile

本项目的移动端界面适配靠 App 侧注入第三方插件
[dsh-web-mobile](https://github.com/mexiaosqwq/dsh-web-mobile)（MIT，作者 mexiaosqwq）实现，
bundle 原样放在 `android/app/src/main/assets/plugins/dsh-web-mobile-client.js`，由
`MainActivity` 的 `addDocumentStartJavaScript` + `shouldInterceptRequest` 喂给 WebView。

**默认原则是逐字节保留上游产物**（便于核对来源、升级时直接覆盖）。本文件记录唯一的例外。

## 基线

| | 值 |
|---|---|
| 上游包 | `dsh-web-mobile` **v2.4.0**（npm `latest`，取自包内 `lib/client.js`） |
| 基线大小 / md5 | 283,504 字节 / `51dacb3ddea04129238646c96cece1ff` |
| 当前大小 / md5 | 284,056 字节 / `5037d9d78a6eadf5e397a7fe557985f6` |
| 差异 | 1 个 hunk（仅新增注释 + 注释掉 1 行调用） |

## 补丁清单（共 1 处）

### P1 — 禁用「删除会话」菜单项

**位置**：bundle 内 `index.js` 装配段（上游第 5755 行）。

```js
// 上游原文
    (0, session_menu_ts_1.installSessionMenuDelete)(ctx);
```

**现状**：该行被注释掉，并附上一段说明注释。

**原因**：这是上游 v2.4.0 新增功能里**唯一需要宿主半边**的一项。点击它注入的
「删除会话」会 `POST /api/mobile-nav.session.delete`，而该路由由插件的宿主半边
（`lib/index.js` + `lib/delete-session.js`）注册。本项目的设计是
「**纯 App 侧注入，服务端零改动**」——宿主半边永远不安装，于是：

```
POST /api/mobile-nav.session.delete  →  HTTP 404  not found     （实测）
```

菜单项因此必然报「删除失败：HTTP 404」。与其留一个点了必错的按钮，不如不安装它。

**为什么不改成 CSS 隐藏**：CSS 只遮像素，DOM、事件监听、确认弹窗逻辑仍在运行，
还多出一处 App 侧运行时补丁；直接从装配段摘掉最干净。

**影响面**：仅移除「删除会话」菜单项及其确认弹窗。bundle 内其余 `session-menu`
代码（`resolveSessionId`、`DELETE_ITEM_MARKER`、`TRASH_SVG`、`mobileNav` 字典里的
`delete*` 文案、`[data-mobile-nav="session-delete"]` 样式）保持上游原样，但不再被
执行或使用。插件的抽屉、滑动手势、键盘守卫、响应压缩等其它能力**均未改动**。

## 验证方法

用手机尺寸（384×832、`hasTouch`、触屏 UA）打开真实 dsh 页面，按 App 的方式注入
bundle，点开任意会话行的「⋯」菜单，然后检查菜单项与补丁标记：

| 注入的 bundle | ⋯ 菜单项 | `[data-mobile-nav="session-delete"]` |
|---|---|---|
| 上游 v2.4.0 | 重命名 / 分叉会话 / 归档会话 / **删除会话** | 1 |
| 本项目（P1 已打） | 重命名 / 分叉会话 / 归档会话 | **0** |

两次都无 console 错误，菜单仍是宿主原生的三项。

## 重新 vendoring 步骤

1. 取上游产物：`npm pack dsh-web-mobile@<version>`，用包内 `lib/client.js` 覆盖
   `android/app/src/main/assets/plugins/dsh-web-mobile-client.js`。
2. 校验基线 md5 是否等于上表（换版本则更新本表）。
3. 重新应用 P1：`grep -n "installSessionMenuDelete)(ctx);"` 定位那一行并注释掉。
4. `node --check` 确认语法通过。
5. 同步更新 `MainActivity.MOBILE_PLUGIN_REV` 的后缀（`-dsh1` → `-dsh2` → …）：
   rev 是 WebView 侧的缓存键，内容变了 rev 不变可能命中旧缓存。
6. 若上游已把该功能做成无需宿主半边，或本项目决定安装宿主半边，则删除本补丁。

## 附：会话删除走「外部移除」

dsh 官方把物理删除定位为**外部动作**，不在自己的接口里提供
（`dsh-session-persistence-jsonl`「已知限制与延期工作」原文：*不删除会话文件——日志在
`root` 下累积，直到外部移除；seam 无删除接口*；UI 上给的是「归档会话」，单向且不回收磁盘）。
所以删会话直接在电脑上做即可，索引层会跟随外部移除，界面随即干净：

```sh
# 存储布局：~/.dsh/sessions/<projectKey>/session-<uuid>/
ls -lt ~/.dsh/sessions/*/*/session.jsonl.zstd | head   # 最近活动的会话
du -sh ~/.dsh/sessions/*/*/ | sort -h | tail           # 最占磁盘的会话

rm -rf ~/.dsh/sessions/<projectKey>/session-<uuid>/    # 一个目录 = 一个会话的全部日志世代
```

注意事项：

- **别删正在运行的会话。** 运行中的会话被宿主持有，dispose 阶段可能把日志目录回写重建
  （社区插件的更新日志踩过这个坑）。先确认它没在跑，再删已经结束的会话最稳。
- 投影缓存 `~/.dsh/storages/session_projcache/sessions/session-<id>.json` 是派生数据，
  留着无害（不占多少空间），想一并清掉也可以。
- 附件字节在共享的 content-addressed 存储里，不会随会话删除；只在没有任何日志引用它们时
  才算不可达垃圾。
