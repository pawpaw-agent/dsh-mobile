#!/usr/bin/env bash
# Patch @deepseek-ai/dsh-client-modules: serveBundle on-demand combo fallback.
#
# 背景（dsh 0.1.2-rc.1 缺陷，2026-09-09 实测）：
#   新启动的 dsh web 进程中，注入 HTML 的 /plugins/??<全量id>&rev=X 批次 URL
#   不在 serveBundle 的内存响应表里（表与注入来源是两条构建路径）→ batch 404
#   → 前端 "Failed to load plugins"。逐配置穷举 + 单条目 200 / 批次 404 确认：
#   与插件配置无关，任何新进程必现。
#
# 补丁：serveBundle 未命中时，从活动 table 按 URL 中的 id 列表现场组合 combo
#   （buildCombo + table.get），客户端请求的永远是全量 id 列表，扫描完成后
#   table 一定齐 — 兜底可命中。单条目 URL 仍走原 responses 表（缓存语义不变）。
#
# 注意：npm 重装/升级 @deepseek-ai/dsh 会覆盖 node_modules，需重新执行本脚本。
set -euo pipefail

PKG=/home/xsj/.npm-global/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-client-modules/lib/index.js
if grep -q "LOCAL PATCH" "$PKG" 2>/dev/null; then
  echo "already patched: $PKG"
  exit 0
fi

python3 - "$PKG" <<'EOF'
import sys
p = sys.argv[1]
src = open(p, encoding='utf-8').read()
if 'LOCAL PATCH' in src:
    print('already patched'); raise SystemExit(0)
old = '\t\tres.end(req.method === "HEAD" ? void 0 : response.body);\n\t\treturn;\n\t}\n\tres.writeHead(404);\n\tres.end();'
new = '\t\tres.end(req.method === "HEAD" ? void 0 : response.body);\n\t\treturn;\n\t}\n\t/* LOCAL PATCH (client-modules batch race): the injected HTML advertises a\n\t * batch URL whose rev comes from one compose pass while this.responses may\n\t * be keyed by another (boot watcher race). Fall back to composing the combo\n\t * from the live table; the client always requests the full entry list,\n\t * which the table satisfies once the scan is complete. */\n\tif (!resourceUrl.startsWith("/plugins/??") || resourceUrl.includes(".map")) {\n\t\tres.writeHead(404);\n\t\tres.end();\n\t\treturn;\n\t}\n\tconst __qs = resourceUrl.split("??")[1] ?? resourceUrl.split("?")[1] ?? "";\n\tconst __ids = (__qs.split("&")[0] ?? "")\n\t\t.split(",")\n\t\t.map((part) => part.replace(/\\/client\\.js$/, ""))\n\t\t.filter((id) => id.length > 0);\n\tif (__ids.length > 0 && __ids.every((id) => this.table.has(id))) {\n\t\ttry {\n\t\t\tconst __artifact = buildCombo(__ids.map((id) => this.table.get(id)));\n\t\t\tres.writeHead(200, {\n\t\t\t\t"content-type": "text/javascript; charset=utf-8",\n\t\t\t\t"cache-control": IMMUTABLE_CACHE\n\t\t\t});\n\t\t\tres.end(__artifact.script);\n\t\t\treturn;\n\t\t} catch (__e) {\n\t\t\ttry { this.ctx.logger.error(__e); } catch (_) {}\n\t\t}\n\t}\n\tres.writeHead(404);\n\tres.end();'
if old not in src:
    raise SystemExit('anchor not found — dsh version changed; manual review needed')
src = src.replace(old, new, 1)
open(p, 'w', encoding='utf-8').write(src)
print('patched:', p)
EOF

node --check "$PKG" && echo "SYNTAX OK"
echo "done — restart dsh-web.service to load the patch"
