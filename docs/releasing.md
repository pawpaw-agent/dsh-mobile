# 发布说明的公共部分

本文件是各版本发布说明（`docs/release-notes-0.1.*.md`）共用内容的**唯一出处**。
发布说明只写本版差异，安装 / 许可 / 依赖上限一律链到此处，避免三份逐字重复而各自漂移。

## 安装

1. 从对应 Release 的 Assets 下载 APK（文件名形如 `dsh-handheld-<版本>.apk`）。
2. 在电脑上启动 dsh web：

   ```sh
   dsh --profile web
   # 默认监听 http://127.0.0.1:3080
   ```

3. 打开 App，填写**电脑地址**（局域网 IP 或 Tailscale IP）、**登录账号**、
   **电脑登录密码**（隧道用）即可。

**签名兼容性**：0.1.2 及更早用 debug 签名，0.1.3 起改用独立 release 签名。签名不同的包
Android 不允许覆盖安装，必须先卸载旧版（已保存的连接配置会一并清除）；0.1.3 与 0.1.4
签名相同，可以直接覆盖安装。

## 许可

GPL-3.0。**原因是 `java/com/termux/shared/terminal/io/` 下那 7 个 vendored 文件为
GPLv3-only**（Termux `v0.118.1` 的 `termux-shared` 主许可；其 MIT 例外逐文件列举，不含
`terminal/io/*`）。Gradle 依赖 `terminal-view` / `terminal-emulator` 本身是 Apache-2.0。
完整核对见 `README.md` 的「Termux 组件的 vendoring」与 `docs/terminal-rewrite-plan.md`
附录 B。

另打包 Dropbear `dbclient` / `dropbearkey`（MIT 风格）与第三方移动端适配插件
[dsh-web-mobile](https://github.com/mexiaosqwq/dsh-web-mobile)（MIT），各自许可证随附。

> **发布前待确认**：Apache-2.0 要求随附许可证文本，当前 APK 内没有 —— CI 把 dropbear 的
> `LICENSE.txt` 拷进 `jniLibs/`，而 AGP 只打包那里的 `.so`，那个 `.txt` 进不了 APK。

> 各版本附带组件与许可结论一致，版本之间没有差异。

## 依赖升级上限（结论）

依赖升级上限受**两个独立约束**，必须同时满足：① AAR 元数据的 `minCompileSdk` ≤ 当前
`compileSdk`（34）；② 传递依赖的 `kotlin-stdlib` metadata 版本 ≤ 本机 Kotlin 编译器可读
上限（Kotlin 1.9.22 → metadata 2.0.0）。第二条更隐蔽：`webkit` 从 1.16.0 起引入
`kotlin-stdlib:2.1.20`（metadata 2.1.0），在 Kotlin 1.9.22 下会直接编译失败
（`Module was compiled with an incompatible version of Kotlin`），而 `core-ktx` 到 1.13.1
为止仍只依赖 `kotlin-stdlib:1.8.22`。因此当前的安全上限是 **core-ktx 1.13.1 +
webkit 1.15.0**，再往上必须连同 Kotlin 2.x 一起升。

> 同一约束在 `android/app/build.gradle.kts` 的 `dependencies` 注释与 `README.md`
> 的「依赖为什么"不是最新"」里各有展开；本节是给发布说明用的短版本，结论以本节为准。
