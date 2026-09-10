**小修版。** 摘掉了手机端一个「点了必然报错」的菜单项，并把「会话删除」的官方口径写进文档。

## 变更

- **移除「删除会话」菜单项。** 上游移动端适配插件 v2.4.0 新增的这项功能需要**宿主半边**才能工作：点击后它会 `POST /api/mobile-nav.session.delete`，而这条路由要由插件的服务端部分注册。本项目的设计是「纯 App 侧注入，服务端零改动」，宿主半边从不安装，因此该接口恒为 `HTTP 404` —— 点下去只会看到「删除失败：HTTP 404」。现在这一项不再注入，会话行「⋯」菜单恢复为官方三项：重命名 / 分叉会话 / 归档会话。
- 插件缓存键 `rev` 随内容升级为 `dsh-web-mobile-2.4.0-dsh1`（内容变了 rev 必须变，否则 WebView 可能命中旧缓存）。
- 新增 [`docs/vendored-plugin-patches.md`](https://github.com/pawpaw-agent/dsh-handheld/blob/main/docs/vendored-plugin-patches.md)：记录这唯一一处对上游 bundle 的补丁（位置、原因、影响面、重新 vendoring 步骤），以及会话删除的做法。

## 会话到底怎么删（官方口径）

dsh 官方把物理删除定位为**外部动作**，不在接口里提供（持久化后端「已知限制与延期工作」原文：*不删除会话文件——日志在 `root` 下累积，直到外部移除；seam 无删除接口*）。UI 上给的是「归档会话」，**单向且不回收磁盘**。要真删，直接在电脑上删目录即可，索引会跟随外部移除、界面随即干净：

```sh
rm -rf ~/.dsh/sessions/<projectKey>/session-<uuid>/     # 一个目录 = 一个会话
```

前提：该会话没在运行（运行中的会话被宿主持有，收尾时可能把日志目录回写重建）。定位方式、投影缓存与附件说明见补丁文档。

## 版本

| | |
|---|---|
| `package` / `versionCode` / `versionName` | `com.dshhandheld.app` / **31** / **0.1.4** |
| 签名 | 与 0.1.3 相同 → **可直接覆盖安装**（0.1.2 及更早仍需先卸载） |

**真机功能验证仍未完成**（SSH 隧道重建、断线恢复、BACK 语义等尚未在实机跑过）。已知问题见 [`docs/known-issues.md`](https://github.com/pawpaw-agent/dsh-handheld/blob/main/docs/known-issues.md)。

## 安装

1. 下载下方 `dsh-handheld-0.1.4.apk`
2. 0.1.3 用户可直接覆盖安装
3. 在电脑上启动 `dsh --profile web`，打开 App，填电脑地址 / 登录账号 / 电脑登录密码

## 许可

GPL-3.0。SSH 终端模式集成 Termux [terminal-view](https://github.com/termux/termux-app)（GPL-3.0），故整体以 GPL-3.0 发布。另打包 Dropbear `dbclient` 与第三方移动端适配插件 [dsh-web-mobile](https://github.com/mexiaosqwq/dsh-web-mobile)（MIT），各自许可证随附。

---

**SHA-256**（`dsh-handheld-0.1.4.apk`）：构建产物校验后填入。
