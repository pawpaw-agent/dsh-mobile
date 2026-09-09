# dsh web 前端插件 404（Failed to load plugins）排查与修复

2026-09-09 定论。现象：dsh web 页面加载成功（HARNESS 顶栏出现、无 401），
但报 `Failed to load plugins`，一长串 `@deepseek-ai/dsh-*` 插件 `failed to load`。
**任何客户端（浏览器 / 手机 App / curl）都一样** —— 服务端问题。

## 根因（dsh 0.1.2-rc.1 client-modules 缺陷）

1. 服务端 `/plugins/??<id 列表>&rev=X` 是 esbuild combo（一次打包多个插件 client）。
   - 单条目 URL（`/plugins/??dsh-pocket/client.js&rev=…`）→ **200**
   - 全量批次 URL（所有插件 id + rev）→ **404**（在注入 HTML 里就是这个名字）
2. 注入 HTML 的批次 URL 与 `serveBundle` 的内存响应表来自两条构建路径，
   新启动的进程 batch 必然 miss（表内容与注入不一致/或批次响应未注册）。
3. 逐配置穷举（原始插件集 / 移除 dshmarket / 加 anysearch / 单 pocket）
   **全部复现 404** → 与插件配置无关；同版本代码（文件 mtime 全部早于
   工作的旧进程）下，旧进程“正常”只是它启动时恰好命中。

## 修复（本地小补丁，已验证）

`dsh-client-modules/lib/index.js` 的 `serveBundle`：内存表 miss 时，
按请求 URL 中的 id 列表从活动 `table` 现场组合 combo 兜底。
客户端永远请求全量 id 列表，扫描完成后 table 一定齐全 → 兜底必命中。

实测：`dsh --profile web --port 38081` 补丁实例
`/plugins/??<48 ids>&rev=…` → **200，4.48MB**。

### 应用 / 重放

```bash
scripts/patch-dsh-client-modules.sh        # 幂等；已打则跳过
```

⚠️ `npm i -g` 重装/升级 `@deepseek-ai/dsh` 会覆盖 node_modules → 必须重新
运行上面的脚本。

### 生效

重启服务（新进程加载补丁）：

```bash
XDG_RUNTIME_DIR=/run/user/1000 systemctl --user restart dsh-web.service
# 或：pkill -f "dsh --profile web"   （systemd Restart=always 5s 后自动拉起）
```

重启后 token 变化：手机 App 会自动重新获取（1.6.0+），
浏览器端重新打开 `http://<host>:3080/?token=<新值>` 即可。

## 验证未修复 / 已修复

```bash
# 1. 取 token（服务端）
TOKEN=$(journalctl _SYSTEMD_USER_UNIT=dsh-web.service -n 200 --no-pager | \
  grep -oE 'token=[A-Za-z0-9_-]+' | tail -1 | cut -d= -f2)
# 2. 换 cookie
curl -s -c /tmp/ck "http://127.0.0.1:3080/?token=$TOKEN" -o /dev/null
# 3. 取出页面注入的批次 URL
curl -s -b /tmp/ck http://127.0.0.1:3080/ | \
  grep -oE '(script src|href)="(/plugins/\?\?[^"]+)' | head -1
# 4. 请求它（&amp; → &）
curl -s -b /tmp/ck "http://127.0.0.1:3080/<上一步URL>（&amp;换成&）" -o /dev/null -w "%{http_code}\n"
# 修复后应 200（约 4-5MB body）
```
