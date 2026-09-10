package com.dshmobile.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.HttpAuthHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.dshmobile.protocol.SshTunnel
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * dsh-mobile 连接屏 —— 纯 WebView 版：
 *
 *  全屏 WebView 加载 dsh web 前端，功能与桌面 100% 一致
 *  （Markdown/代码高亮/设置页……），自带：
 *    - crypto.randomUUID 文档启动注入（局域网明文 HTTP 防白屏，与 dsh-lan-access 幂等同款）
 *    - Basic Auth 弹窗（隧道/反代场景）
 *    - 错误页（重试 / 换服务器），retainedWebView 跨重建保活
 *
 *  SSH 隧道可选：经 sshd 本地端口转发访问，服务端视角为回环，
 *  配置平面（settings/credentials/…）全解锁 —— 官方文档认可的合规远程路径。
 *  SSH 配置持久化，App 重启自动恢复；断线重连后 WebView 自动跟随新端口。
 *
 *  视觉：全 App 黑白色调 —— 与启动图标一致。
 */
class MainActivity : Activity() {
    private var webView: WebView? = null
    private var connectView: View? = null
    private var errorView: View? = null
    private var progressBar: ProgressBar? = null
    private var lastUrl: String? = null
    private var pendingAuth: HttpAuthHandler? = null
    private var statusView: TextView? = null
    private var sshKeyPathInput: EditText? = null
    private var disconnectButton: Button? = null

    // Step3 连接进度行（引导流程；null = 界面未构造/非引导状态）
    private var stepGuideLine1: TextView? = null
    private var stepGuideLine2: TextView? = null
    private var stepGuideLine3: TextView? = null
    private var stepGuideCard: View? = null
    private var stepGuideStep2: View? = null

    /** 连接进行中守卫：防连点「连接」并发多个隧道/多次设置回调。 */
    private val connecting = AtomicBoolean(false)

    /** 通知权限询问进行中（权限对话框本身会触发 onPause，防重入再弹）。 */
    private var notificationAskInFlight = false

    /** 页面加载失败后的自动重试余量（WiFi 断连/隧道重建窗口期自动恢复，无需手动点重试）。 */
    private var loadRetriesLeft = 0

    /**
     * 当前 URL 是否已成功完成过 token 交换（cookie 已种下）。
     * cookie 按 host:port 绑定：SSH 重连换端口后必须置 false 重新认证。
     * 直连模式端口不变，仅首次需要。
     */
    private var sshTokenAck = false

    /** 401 时是否已尝试过回退干净 URL（防重复回退循环；每次 connectWeb 重置）。 */
    private var unauthorizedCleanTried = false
    private lateinit var prefs: android.content.SharedPreferences

    private companion object {
        // 黑白色调
        const val TAG = "DshMobile"
        const val COL_BG = 0xFF0A0A0E.toInt()
        const val COL_SURFACE = 0xFF101015.toInt()
        const val COL_TEXT = 0xFFF5F5F7.toInt()
        const val COL_TITLE = 0xFFF5F5F7.toInt()
        const val COL_MUTED = 0x99FFFFFF.toInt()
        const val COL_DIM = 0x55FFFFFF.toInt()
        const val COL_HINT = 0x66FFFFFF.toInt()
        const val COL_INPUT_BG = 0x14FFFFFF.toInt()
        const val COL_ACCENT = 0xFFF5F5F7.toInt()
        const val COL_ACCENT_TEXT = 0xFF0A0A0E.toInt()
        const val COL_ERROR = 0xFFFF6B6B.toInt()
        const val UA_MARKER = "DshMobile/1.0"
        const val DEFAULT_PORT = "3080"
        const val REQ_PICK_KEY = 2001

        const val PREF_SSH_ENABLED = "ssh_enabled" // 是否经 SSH 隧道连接（唯一模式）
        const val PREF_SERVER_TOKEN = "server_token" // dsh 0.1.2+ 一次性启动 token（服务重启后自动更新）

        // ── 移动端适配插件（dsh-web-mobile, MIT, github.com/mexiaosqwq/dsh-web-mobile）──
        // 纯 App 侧注入，服务端零改动：doc-start 时用 setter 钩住 window.__DSH_BOOT__，
        // 在服务端写入的启动图里补一条 dsh-web-mobile 插件项（entry + batch，URL 指向
        // 我们自己的 agent 数据 URL）；WebView 引导循环按清单 create 该插件时，
        // shouldInterceptRequest 命中该 URL 返回 APK assets 里的插件 bundle（283KB）。
        // 插件运行时外部依赖仅 react/jsx-runtime + dsh-client-ui-primitives，
        // 均已在前端壳的 staticModules 种子里（已验证），无需额外注入。
        const val MOBILE_PLUGIN_ID = "dsh-web-mobile"
        const val MOBILE_PLUGIN_REV = "dshmobile-2.4.0"
        const val MOBILE_PLUGIN_URL = "/plugins/??$MOBILE_PLUGIN_ID/client.js&rev=$MOBILE_PLUGIN_REV"
        private val MOBILE_PLUGIN_JS = """
            (function(){
              try {
                var stored;
                Object.defineProperty(window, "__DSH_BOOT__", {
                  configurable: true,
                  get: function(){ return stored; },
                  set: function(v){
                    try {
                      if (v && Array.isArray(v.entries) && Array.isArray(v.batches)) {
                        v.entries.push({
                          id: "$MOBILE_PLUGIN_ID",
                          url: "$MOBILE_PLUGIN_URL",
                          rev: "$MOBILE_PLUGIN_REV",
                          inject: [],
                          external: []
                        });
                        v.batches.push({
                          phase: "application",
                          url: "$MOBILE_PLUGIN_URL",
                          rev: "$MOBILE_PLUGIN_REV",
                          entries: ["$MOBILE_PLUGIN_ID"]
                        });
                      }
                    } catch(e) {}
                    stored = v;
                  }
                });
              } catch(e) {}
            })();
        """.trimIndent()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("dsh-mobile", Context.MODE_PRIVATE)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT &&
            (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            // 仅 debug 包开启 WebView 远程调试，方便真机调试；release 不暴露。
            WebView.setWebContentsDebuggingEnabled(true)
        }

        val root = FrameLayout(this).apply { setBackgroundColor(COL_BG) }

        // ── WebView（保留实例，跨重建保活）────────────────────────
        val app = application as DshApp
        webView = (app.retainedWebView ?: WebView(this).also { app.retainedWebView = it }).apply {
            (parent as? ViewGroup)?.removeView(this)
            if (!settings.userAgentString.contains(UA_MARKER)) {
                settings.userAgentString = settings.userAgentString + " " + UA_MARKER
            }
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                builtInZoomControls = false
                displayZoomControls = false
                setSupportZoom(false)
                loadWithOverviewMode = true
                useWideViewPort = true
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(this, MOBILE_PLUGIN_JS, setOf("*"))
            }
            webViewClient = object : WebViewClient() {
                // 拦截 dsh-web-mobile 插件 bundle：返回 APK assets 里的客户端脚本
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?
                ): android.webkit.WebResourceResponse? {
                    val u = request?.url?.toString() ?: return null
                    // 兜底匹配：?? 可能被编码为 %3F%3F，只锚定 /plugins/ 前缀 + 插件 id
                    if (!u.contains("/plugins/") || !u.contains("$MOBILE_PLUGIN_ID/client.js")) return null
                    val bytes = try {
                        assets.open("plugins/dsh-web-mobile-client.js").use { it.readBytes() }
                    } catch (e: Exception) { return null }
                    return android.webkit.WebResourceResponse(
                        "text/javascript", "utf-8", ByteArrayInputStream(bytes)
                    )
                }
                override fun onPageFinished(view: WebView?, url: String?) {
                    // 带 ?token= 的成功页面：服务端已 303 换 cookie 并落地干净 URL；
                    // 后续加载成功也记住认证态（cookie 有效期内无需重复 token 交换）。
                    // 只对 http(s) 正式页面置 ack：about:blank 等内部加载不能污染状态
                    //（1.5.2 断开连接加载 about:blank 会把 ack 误置 true → 省掉 token 交换 → 401）。
                    val u = webView?.url
                    if (u?.startsWith("http") == true && !u.contains("?token=")) {
                        sshTokenAck = true
                        guideLineDone(3, "③ 打开 dsh 网页 ✓ 已打开")
                    }
                    hideErrorPage()
                }
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    // ERR_ABORTED(-3) = 导航被取消（重载/加载 about:blank 打断上一请求），不算失败
                    if (request?.isForMainFrame == true && error?.errorCode != -3) {
                        showErrorPage(error?.description?.toString() ?: "网络错误")
                        scheduleLoadRetry()
                    }
                }
                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, resp: android.webkit.WebResourceResponse?) {
                    if (request?.isForMainFrame == true) {
                        val code = resp?.statusCode ?: 0
                        if (code == 401) {
                            handleUnauthorized(view)
                        } else {
                            showErrorPage("HTTP $code")
                            scheduleLoadRetry()
                        }
                    }
                }
                override fun onReceivedHttpAuthRequest(view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?) {
                    handler ?: return
                    showAuthDialog(handler, host)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (newProgress >= 100) {
                        progressBar?.visibility = View.GONE
                    } else {
                        progressBar?.visibility = View.VISIBLE
                        progressBar?.progress = newProgress
                    }
                }
            }
        }
        root.addView(webView)

        // 细进度条（WebView 加载时顶部一条白线，保持沉浸、不遮挡内容）
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(COL_ACCENT)
            progressBackgroundTintList = ColorStateList.valueOf(0x22FFFFFF.toInt())
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)
            ).apply { gravity = Gravity.TOP }
            visibility = View.GONE
        }
        root.addView(progressBar)

        // ── 连接屏 ───────────────────────────────────────────────
        connectView = createConnectView()
        root.addView(connectView)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        setContentView(root)
        applyImmersive()

        // 自动连接（仅 SSH 模式）：存在完整 SSH 配置即恢复隧道 + 加载回环 URL；
        // 否则停留在连接屏。直连模式已移除。
        val savedUrl = prefs.getString("url", null)
        val currentUrl = webView?.url
        val baseMatch = savedUrl != null && currentUrl != null &&
            currentUrl.trimEnd('/').startsWith(savedUrl.trimEnd('/'))
        val sshSaved = prefs.getString("ssh_json", null)?.let {
            runCatching { JSONObject(it) }.getOrNull()
        }?.takeIf { it.optString("sshHost").isNotBlank() && it.optString("sshUser").isNotBlank() }
        if (savedUrl != null && !baseMatch) {
            if (sshSaved != null) {
                autoConnectSsh(savedUrl)
            } else {
                connectView?.visibility = View.VISIBLE
            }
        } else if (!currentUrl.isNullOrBlank()) {
            connectView?.visibility = View.GONE
        }
    }

    /** 启动时从保存的 SSH 配置恢复隧道，成功后把 WebView 指向新的本地端口 URL。 */
    private fun autoConnectSsh(@Suppress("UNUSED_PARAMETER") savedUrl: String?) {
        val savedSsh = prefs.getString("ssh_json", null)?.let {
            try { JSONObject(it) } catch (_: Exception) { null }
        } ?: run { connectView?.visibility = View.VISIBLE; return }
        val host = savedSsh.optString("sshHost")
        val user = savedSsh.optString("sshUser")
        if (host.isBlank() || user.isBlank()) { connectView?.visibility = View.VISIBLE; return }
        val port = savedSsh.optInt("sshPort", 22)
        val remotePort = savedSsh.optInt("remotePort", DEFAULT_PORT.toInt())
        val app = application as DshApp
        // 用户可能已在连接屏手动点了「连接」：自动恢复不抢占
        if (!beginConnect()) return
        status("自动重建 SSH 隧道…")
        connectView?.visibility = View.VISIBLE
        Thread {
            val auth = if (savedSsh.optString("authType", "password") == "key") {
                val keyPath = savedSsh.optString("keyPath", "")
                if (keyPath.isBlank()) {
                    runOnUiThread { status("SSH 私钥路径为空，请在连接屏重新配置"); endConnect() }
                    return@Thread
                }
                SshTunnel.Auth.KeyPair(
                    java.io.File(keyPath),
                    savedSsh.optString("keyPass").ifEmpty { null }
                )
            } else {
                SshTunnel.Auth.Password(savedSsh.optString("password", ""))
            }
            // 旧隧道先关（重复启动/切模式的泄漏点）
            closeCurrentTunnel()
            val tunnel = SshTunnel(
                sshHost = host, sshPort = port, sshUser = user,
                remoteHost = savedSsh.optString("remoteHost", "127.0.0.1"),
                remotePort = remotePort,
                auth = auth
            )
            tunnel.onStateChange = { s -> runOnUiThread { status("隧道: $s") } }
            tunnel.onLocalBaseChanged = { newBase ->
                runOnUiThread {
                    lastUrl = newBase
                    prefs.edit().putString("url", newBase).apply()
                    // 重连换了本地端口：cookie 已失效，必须用 token 重新认证
                    sshTokenAck = false
                    connectWeb(newBase)
                }
            }
            tunnel.start()
            val base = tunnel.localBaseUrl
            // 失败先退：隧道没起来就不再干跑 token 探测（4×8s 白等）
            if (base == null) {
                tunnel.close()
                runOnUiThread { status("自动连接失败，请在连接屏手动重试"); endConnect() }
                return@Thread
            }
            // 自动获取最新 token（服务重启后旧 token 失效；失败静默回退）
            autoFetchToken(tunnel)
            runOnUiThread {
                app.sshTunnel = tunnel
                prefs.edit().putString("url", base).apply()
                connectView?.visibility = View.GONE
                lastUrl = base
                sshTokenAck = false
                connectWeb(base)
                refreshConnectState()
                endConnect()
            }
        }.start()
    }

    // ── 连接屏 UI ─────────────────────────────────────────────
    private fun createConnectView(): View {
        val scroll = ScrollView(this).apply {
            setBackgroundColor(COL_BG)
            isFillViewport = true
        }
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        scroll.addView(outer, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(20), dp(22), dp(20), dp(18))
            layoutParams = LinearLayout.LayoutParams(dp(320), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        outer.addView(card)

        fun label(text: String): TextView = TextView(this).apply {
            this.text = text
            textSize = 11f
            setTextColor(COL_DIM)
            letterSpacing = 0.12f
        }

        fun input(hint: String, prefill: String = "", pwd: Boolean = false, number: Boolean = false): EditText =
            EditText(this@MainActivity).apply {
                this.hint = hint
                textSize = 14f
                setTextColor(COL_TEXT)
                setHintTextColor(COL_HINT)
                setBackgroundResource(R.drawable.bg_input)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setSingleLine(true)
                setHorizontallyScrolling(true)
                when {
                    pwd -> inputType = (InputType.TYPE_CLASS_TEXT
                        or InputType.TYPE_TEXT_VARIATION_PASSWORD
                        or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
                    number -> inputType = InputType.TYPE_CLASS_NUMBER
                    else -> inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
                if (prefill.isNotEmpty()) setText(prefill)
            }

        fun segment(text: String, initial: Boolean = false): RadioButton =
            RadioButton(this).apply {
                id = View.generateViewId()
                this.text = text
                textSize = 12f
                isChecked = initial
                buttonDrawable = null
                gravity = Gravity.CENTER
                setIncludeFontPadding(false)
                setSingleLine(true)
                setMinWidth(0)
                setMinimumWidth(0)
                setMinEms(0)
                setMaxEms(5)
                setPadding(dp(4), dp(10), dp(4), dp(10))
                setBackgroundResource(R.drawable.bg_segment)
                setTextColor(if (initial) COL_ACCENT_TEXT else COL_TEXT)
                setOnCheckedChangeListener { _, checked ->
                    setTextColor(if (checked) COL_ACCENT_TEXT else COL_TEXT)
                }
            }

        fun rowParams(top: Int = 0, width: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
                      height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
            LinearLayout.LayoutParams(width, height).apply { topMargin = top }

        /** 密码行：输入框 + 显示/隐藏切换（避免密码框永远黑点）。 */
        fun pwdRow(field: EditText): View {
            val showBtn = Button(this@MainActivity).apply {
                text = "显示"
                isAllCaps = false
                textSize = 12f
                setTextColor(COL_DIM)
                setBackgroundResource(R.drawable.bg_button_secondary)
                setOnClickListener {
                    val wasMasked = field.transformationMethod != null
                    field.transformationMethod = if (wasMasked) null
                        else android.text.method.PasswordTransformationMethod.getInstance()
                    field.text?.let { field.setSelection(it.length) }
                    // 刚揭开（现在可见）→ 按钮变「隐藏」；刚遮上 → 变「显示」
                    this.text = if (wasMasked) "隐藏" else "显示"
                    setTextColor(if (wasMasked) COL_ACCENT else COL_DIM)
                }
            }
            return LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(field, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) })
                addView(showBtn, LinearLayout.LayoutParams(dp(56), dp(42)))
            }
        }

        // ── 头部 ──────────────────────────────────────────────
        val headerRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(ImageView(this@MainActivity).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
        })
        headerRow.addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply {
                text = "DSH Mobile"
                textSize = 18f
                typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
                setTextColor(COL_TITLE)
            })
            addView(TextView(this@MainActivity).apply {
                text = "DeepSeek Harness · 手机端"
                textSize = 10f
                setTextColor(COL_MUTED)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(1) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(10)
        })
        card.addView(headerRow, rowParams(top = dp(2), width = ViewGroup.LayoutParams.MATCH_PARENT))

        // ── 三步连接引导：选模式 → 填连接信息 → 连接中 ──────────────────
        // 关键字段引用保留为类字段（连接流程/状态钩子复用）
        var step1Card: LinearLayout? = null   // 选模式
        var step2Card: LinearLayout? = null   // 填信息
        var step3Card: LinearLayout? = null   // 连接中
        var connectMainBtn: Button? = null    // 底部主按钮（每步复用）
        var webMode = true
        val savedSsh = prefs.getString("ssh_json", null)?.let {
            try { JSONObject(it) } catch (_: Exception) { null }
        }

        // 步骤容器（后续在 card 内按顺序 addView）
        fun stepLabel(text: String): TextView = TextView(this@MainActivity).apply {
            this.text = text
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COL_TITLE)
        }
        fun stepHint(text: String): TextView = TextView(this@MainActivity).apply {
            this.text = text
            textSize = 11f
            setTextColor(COL_MUTED)
        }

        // ── Step 1：你想做什么 ─────────────────────────────────────────
        step1Card = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        step1Card!!.addView(stepLabel("你想做什么？"), rowParams(width = ViewGroup.LayoutParams.MATCH_PARENT))
        step1Card!!.addView(stepHint("手机连上你电脑上的 DeepSeek Harness。"), rowParams(top = dp(2), width = ViewGroup.LayoutParams.MATCH_PARENT))

        fun modeCard(text: String, sub: String): Pair<LinearLayout, (Boolean) -> Unit> {
            val title = TextView(this@MainActivity).apply {
                this.text = text
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(COL_TEXT)
                setIncludeFontPadding(false)
                setSingleLine(true)
            }
            val desc = TextView(this@MainActivity).apply {
                this.text = sub
                textSize = 10f
                setTextColor(COL_MUTED)
                setIncludeFontPadding(false)
                setSingleLine(true)
            }
            val card = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setPadding(dp(10), dp(12), dp(10), dp(10))
                setBackgroundResource(R.drawable.bg_mode_card_off)
                addView(title)
                addView(desc, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2) })
            }
            val paint = { on: Boolean ->
                card.setBackgroundResource(if (on) R.drawable.bg_mode_card_on else R.drawable.bg_mode_card_off)
                title.setTextColor(if (on) COL_ACCENT_TEXT else COL_TEXT)
                desc.setTextColor(if (on) 0x55000000.toInt() else COL_MUTED)
            }
            return card to paint
        }
        val (webModeCard, paintWeb) = modeCard("看 dsh 网页", "浏览会话、聊天")
        val (termModeCard, paintTerm) = modeCard("打开终端", "远程敲命令")
        fun selectMode(web: Boolean) {
            webMode = web
            paintWeb(web)
            paintTerm(!web)
            // 主按钮文案跟模式：创建早于 connectMainBtn，故用可空调用
            connectMainBtn?.text = if (web) "连上并打开 dsh 网页" else "连上并打开终端"
        }
        webModeCard.setOnClickListener { selectMode(true) }
        termModeCard.setOnClickListener { selectMode(false) }
        val modeRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(webModeCard, LinearLayout.LayoutParams(0, dp(64), 1f).apply { marginEnd = dp(6) })
            addView(termModeCard, LinearLayout.LayoutParams(0, dp(64), 1f).apply { marginStart = dp(6) })
        }
        step1Card!!.addView(modeRow, rowParams(top = dp(10), width = ViewGroup.LayoutParams.MATCH_PARENT))
        selectMode(true)
        // 小字：避免选择负担
        step1Card!!.addView(stepHint("· 看 dsh 网页：管理和浏览电脑上的 dsh 界面\n· 打开终端：远程敲命令，像在电脑前一样"),
            rowParams(top = dp(8), width = ViewGroup.LayoutParams.MATCH_PARENT))
        step1Card!!.addView(Button(this@MainActivity).apply {
            text = "下一步"
            isAllCaps = false
            setTextColor(COL_ACCENT_TEXT)
            setBackgroundResource(R.drawable.bg_button_primary)
            setOnClickListener { step2Card?.visibility = View.VISIBLE; step1Card?.visibility = View.GONE }
        }, rowParams(top = dp(12), height = dp(46), width = ViewGroup.LayoutParams.MATCH_PARENT))

        // ── Step 2：连接信息 ───────────────────────────────────────────
        step2Card = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        step2Card!!.addView(stepLabel("连接信息"), rowParams(width = ViewGroup.LayoutParams.MATCH_PARENT))
        step2Card!!.addView(stepHint("你电脑上运行 dsh 的地址和登录账号。"),
            rowParams(top = dp(2), width = ViewGroup.LayoutParams.MATCH_PARENT))

        step2Card!!.addView(label("电脑地址"), rowParams(top = dp(14), width = ViewGroup.LayoutParams.MATCH_PARENT))
        val sshHostInput = input("IP 地址", savedSsh?.optString("sshHost") ?: "")
        val sshPortInput = input("22", savedSsh?.optString("sshPort") ?: "22", number = true)
        val sshHostRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(sshHostInput, LinearLayout.LayoutParams(0, dp(42), 3f).apply { marginEnd = dp(8) })
            addView(sshPortInput, LinearLayout.LayoutParams(0, dp(42), 1f))
        }
        step2Card!!.addView(sshHostRow, rowParams(top = dp(6), width = ViewGroup.LayoutParams.MATCH_PARENT))
        step2Card!!.addView(stepHint("端口一般用 22，不用改。"), rowParams(top = dp(4), width = ViewGroup.LayoutParams.MATCH_PARENT))

        step2Card!!.addView(label("登录账号"), rowParams(top = dp(12), width = ViewGroup.LayoutParams.MATCH_PARENT))
        val sshUserInput = input("用户名", savedSsh?.optString("sshUser") ?: "")
        step2Card!!.addView(sshUserInput, rowParams(top = dp(6), width = ViewGroup.LayoutParams.MATCH_PARENT))

        step2Card!!.addView(label("电脑登录密码"), rowParams(top = dp(12), width = ViewGroup.LayoutParams.MATCH_PARENT))
        val sshPassInput = input("密码", savedSsh?.optString("password") ?: "", pwd = true)
        val passRow = pwdRow(sshPassInput)
        step2Card!!.addView(passRow, rowParams(top = dp(6), width = ViewGroup.LayoutParams.MATCH_PARENT))

        // dsh 端口：与 SSH 无关，但属于「怎么连上电脑的 dsh」，常显不折叠
        step2Card!!.addView(label("dsh 端口"), rowParams(top = dp(12), width = ViewGroup.LayoutParams.MATCH_PARENT))
        val sshTargetPortInput = input(
            "3080",
            (savedSsh?.optInt("remotePort", DEFAULT_PORT.toInt()) ?: DEFAULT_PORT.toInt()).toString(),
            number = true
        )
        step2Card!!.addView(sshTargetPortInput, rowParams(top = dp(6), width = ViewGroup.LayoutParams.MATCH_PARENT))
        step2Card!!.addView(stepHint("dsh 网页的端口，默认 3080，一般不用改。"),
            rowParams(top = dp(4), width = ViewGroup.LayoutParams.MATCH_PARENT))

        // 高级设置（折叠）：登录方式 / 私钥
        val privateKeySection = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }

        privateKeySection.addView(label("登录方式"), rowParams(width = ViewGroup.LayoutParams.MATCH_PARENT))
        val authPassBtn = segment("密码", true)
        val authKeyBtn = segment("私钥", false)
        val authGroup = RadioGroup(this@MainActivity).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(authPassBtn, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(6) })
            addView(authKeyBtn, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginStart = dp(6) })
        }
        privateKeySection.addView(authGroup, rowParams(top = dp(6), width = ViewGroup.LayoutParams.MATCH_PARENT))

        val keyPathInput = input("私钥路径（可导入）", savedSsh?.optString("keyPath") ?: "")
        keyPathInput.isFocusable = true
        sshKeyPathInput = keyPathInput
        val browseKeyBtn = Button(this@MainActivity).apply {
            text = "导入"
            isAllCaps = false
            textSize = 12f
            setTextColor(COL_TEXT)
            setBackgroundResource(R.drawable.bg_button_secondary)
            setOnClickListener { pickSshKey() }
        }
        val keyPathRow = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(keyPathInput, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(6) })
            addView(browseKeyBtn, LinearLayout.LayoutParams(dp(56), dp(42)))
        }
        privateKeySection.addView(keyPathRow, rowParams(top = dp(6), width = ViewGroup.LayoutParams.MATCH_PARENT))

        val keyPassInput = input("私钥口令（可选）", savedSsh?.optString("keyPass") ?: "", pwd = true)
        val keyPassRow = pwdRow(keyPassInput)
        privateKeySection.addView(keyPassRow, rowParams(top = dp(6), width = ViewGroup.LayoutParams.MATCH_PARENT))

        // 认证方式切换：密码 / 私钥字段显隐
        val savedAuthType = savedSsh?.optString("authType", "password") ?: "password"
        if (savedAuthType == "key") authKeyBtn.isChecked = true
        fun syncAuthFields() {
            val key = authKeyBtn.isChecked
            keyPathRow.visibility = if (key) View.VISIBLE else View.GONE
            keyPassRow.visibility = if (key) View.VISIBLE else View.GONE
            passRow.visibility = if (key) View.GONE else View.VISIBLE
        }
        authGroup.setOnCheckedChangeListener { _, _ -> syncAuthFields() }
        syncAuthFields()

        // 高级设置折叠开关
        val advancedWrap = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        val advancedToggle = Button(this@MainActivity).apply {
            text = "高级设置 ▾"
            isAllCaps = false
            textSize = 12f
            setTextColor(COL_DIM)
            setBackgroundResource(R.drawable.bg_button_secondary)
            setOnClickListener {
                val hidden = privateKeySection.visibility == View.GONE
                privateKeySection.visibility = if (hidden) View.VISIBLE else View.GONE
                this.text = if (hidden) "高级设置 ▴" else "高级设置 ▾"
            }
        }
        advancedWrap.addView(advancedToggle, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(36)))
        advancedWrap.addView(privateKeySection.apply { visibility = View.GONE },
            rowParams(top = dp(8), width = ViewGroup.LayoutParams.MATCH_PARENT))
        step2Card!!.addView(advancedWrap, rowParams(top = dp(10), width = ViewGroup.LayoutParams.MATCH_PARENT))

        // Step 2 底部：上一步 + 主按钮（文案跟模式）
        val step2Nav = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
        step2Nav.addView(Button(this@MainActivity).apply {
            text = "上一步"
            isAllCaps = false
            textSize = 12f
            setTextColor(COL_TEXT)
            setBackgroundResource(R.drawable.bg_button_secondary)
            setOnClickListener { step1Card?.visibility = View.VISIBLE; step2Card?.visibility = View.GONE }
        }, LinearLayout.LayoutParams(dp(88), dp(46)).apply { marginEnd = dp(6) })
        connectMainBtn = Button(this@MainActivity).apply {
            isAllCaps = false
            text = if (webMode) "连上并打开 dsh 网页" else "连上并打开终端"
            setTextColor(COL_ACCENT_TEXT)
            setBackgroundResource(R.drawable.bg_button_primary)
            setOnClickListener {
                prefs.edit().putBoolean(PREF_SSH_ENABLED, true).apply()
                sshTokenAck = false

                // 校验
                val sh = sshHostInput.text.toString().trim()
                val su = sshUserInput.text.toString().trim()
                val sport = sshPortInput.text.toString().trim().toIntOrNull()?.coerceIn(1, 65535) ?: 22
                val target = sshTargetPortInput.text.toString().trim().ifEmpty { DEFAULT_PORT }
                    .toIntOrNull()?.coerceIn(1, 65535) ?: 3080
                if (sh.isBlank() || su.isBlank()) { status("请填写电脑地址和登录账号", true); guideBackToStep2(); return@setOnClickListener }
                val auth = if (authKeyBtn.isChecked) {
                    val path = keyPathInput.text.toString().trim()
                    if (path.isBlank()) { status("请选择 SSH 私钥", true); guideBackToStep2(); return@setOnClickListener }
                    SshTunnel.Auth.KeyPair(File(path), keyPassInput.text.toString().ifEmpty { null })
                } else {
                    if (sshPassInput.text.toString().isEmpty()) { status("请填写电脑登录密码", true); guideBackToStep2(); return@setOnClickListener }
                    SshTunnel.Auth.Password(sshPassInput.text.toString())
                }
                if (!webMode) {
                    persistSshConfig(sh, sport, su, target, auth)
                    startActivity(Intent(this@MainActivity, TuiActivity::class.java))
                    return@setOnClickListener
                }
                if (!beginConnect()) { guideBackToStep2(); return@setOnClickListener }
                stepGuideLine1?.text = "① 检查电脑 正在连接"
                connectViaSsh(sh, sport, su, target, auth)
            }
        }
        step2Nav.addView(connectMainBtn!!, LinearLayout.LayoutParams(0, dp(46), 1f))
        step2Card!!.addView(step2Nav, rowParams(top = dp(14), width = ViewGroup.LayoutParams.MATCH_PARENT))

        // ── Step 3：连接中 ─────────────────────────────────────────────
        // 行内容由类级 guideLine* 更新（connectViaSsh/autoConnectSsh/onPageFinished 共用）
        val line1 = TextView(this@MainActivity).apply {
            textSize = 13f
            text = "① 检查电脑 等待连接…"
            setTextColor(COL_MUTED)
            setIncludeFontPadding(false)
        }
        val line2 = TextView(this@MainActivity).apply {
            textSize = 13f
            text = "② 建立安全通道"
            setTextColor(COL_MUTED)
            setIncludeFontPadding(false)
        }
        val line3 = TextView(this@MainActivity).apply {
            textSize = 13f
            text = "③ 打开 dsh 网页"
            setTextColor(COL_MUTED)
            setIncludeFontPadding(false)
        }
        stepGuideLine1 = line1
        stepGuideLine2 = line2
        stepGuideLine3 = line3

        step3Card = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        step3Card!!.addView(stepLabel("连接中…"), rowParams(width = ViewGroup.LayoutParams.MATCH_PARENT))
        step3Card!!.addView(line1, rowParams(top = dp(12), width = ViewGroup.LayoutParams.MATCH_PARENT))
        step3Card!!.addView(line2, rowParams(top = dp(8), width = ViewGroup.LayoutParams.MATCH_PARENT))
        step3Card!!.addView(line3, rowParams(top = dp(8), width = ViewGroup.LayoutParams.MATCH_PARENT))
        step3Card!!.addView(Button(this@MainActivity).apply {
            text = "返回修改"
            isAllCaps = false
            textSize = 12f
            setTextColor(COL_TEXT)
            setBackgroundResource(R.drawable.bg_button_secondary)
            setOnClickListener { guideBackToStep2() }
        }, rowParams(top = dp(14), width = ViewGroup.LayoutParams.MATCH_PARENT))
        stepGuideCard = step3Card
        stepGuideStep2 = step2Card

        // ── 组装：header → step1 → step2 → step3 ──────────────────────
        card.addView(step1Card, rowParams(top = dp(16), width = ViewGroup.LayoutParams.MATCH_PARENT))
        card.addView(step2Card!!.apply { visibility = View.GONE }, rowParams(top = dp(16), width = ViewGroup.LayoutParams.MATCH_PARENT))
        card.addView(step3Card!!.apply { visibility = View.GONE }, rowParams(top = dp(16), width = ViewGroup.LayoutParams.MATCH_PARENT))

        card.addView(spacer(dp(10)))

        // 状态条（全局错误/连接进度；Step3 行内也有状态）
        statusView = TextView(this@MainActivity).apply {
            textSize = 12f
            setTextColor(COL_MUTED)
            gravity = Gravity.CENTER
            minHeight = dp(28)
            setBackgroundResource(R.drawable.bg_status_pill)
            setPadding(dp(14), dp(4), dp(14), dp(4))
            visibility = View.GONE
        }
        card.addView(statusView, rowParams(top = dp(10), width = ViewGroup.LayoutParams.MATCH_PARENT))

        // 断开连接（仅隧道运行时显示）
        disconnectButton = Button(this@MainActivity).apply {
            text = "断开连接"
            isAllCaps = false
            textSize = 12f
            setTextColor(COL_ERROR)
            setBackgroundResource(R.drawable.bg_button_secondary)
            setOnClickListener { disconnectCurrent() }
        }
        card.addView(disconnectButton!!, rowParams(top = dp(10), height = dp(36), width = ViewGroup.LayoutParams.MATCH_PARENT))
        disconnectButton!!.visibility = View.GONE

        // 上次连接摘要（单行，非空时显示）
        savedSsh?.takeIf { it.optString("sshHost").isNotBlank() }?.let {
            card.addView(TextView(this@MainActivity).apply {
                text = "上次连接: ${it.optString("sshUser")}@${it.optString("sshHost")}:${it.optInt("sshPort", 22)}" +
                    " → ${it.optString("remoteHost", "127.0.0.1")}:${it.optInt("remotePort", 3080)}"
                textSize = 10f
                setTextColor(COL_DIM)
                gravity = Gravity.CENTER
                maxLines = 1
                setHorizontallyScrolling(true)
            }, rowParams(top = dp(10), width = ViewGroup.LayoutParams.MATCH_PARENT))
        }

        return scroll
    }

    private fun pickSshKey() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            startActivityForResult(intent, REQ_PICK_KEY)
        } catch (_: Exception) {
            status("无法打开文件选择器")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_KEY || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        try {
            val dest = File(filesDir, "ssh_private_key")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { output -> input.copyTo(output) }
            } ?: run { status("读取私钥失败"); return }
            sshKeyPathInput?.setText(dest.absolutePath)
            status("私钥已导入到应用私有目录")
        } catch (e: Exception) {
            status("导入私钥失败：${e.message ?: "未知错误"}")
        }
    }

    private fun status(msg: String, err: Boolean = false) {
        statusView?.text = msg
        statusView?.setTextColor(if (err) COL_ERROR else COL_MUTED)
        statusView?.visibility = if (msg.isBlank()) View.GONE else View.VISIBLE
    }

    // ── WebView 直连 ─────────────────────────────────────────
    /**
     * 通过 SSH 自动获取 dsh web 的浏览器认证 token（服务端 journald 日志）。
     *
     * dsh 0.1.2+ 的 launch token 每次服务重启会变化，手动查日志很麻烦；
     * SSH 模式下 App 直接代跑 journalctl 提取最新 token 并存入 prefs，
     * 用户不再需要手输。失败（服务端无 journald/Unit 名不同等）时静默
     * 返回 null，连接流程回退到已存/手输 token。
     *
     * 命令选择：优先 user unit；兜底全量 user journal（2 个候选，都会试）。
     * 只认 `?token=` 后 base64url 字符（服务重启后旧 token 行仍在日志里，
     * 取最后一行 = 当前进程的 token）。
     */
    private fun autoFetchToken(tunnel: SshTunnel): String? {
        // SSH 非交互会话通常没有 XDG_RUNTIME_DIR，而 journalctl --user 依赖它
        // 定位用户会话的 systemd 实例 —— 显式导出（UID 1000 为主流桌面/服务器用户）。
        val xdg = "export XDG_RUNTIME_DIR=/run/user/$(id -u 2>/dev/null || echo 1000); "
        // 实测教训（树莓派）：部分宿主 user journal 不在（journalctl --user 报
        // No journal files），但 system journal 可按 _SYSTEMD_USER_UNIT 过滤。
        // 候选顺序：user-unit → system-unit 过滤 → 全量 user → 全量 system → 日志文件。
        val commands = arrayOf(
            // 候选 1：system journal 按用户单元过滤（user journal 缺失时仍可用）
            "$xdg journalctl _SYSTEMD_USER_UNIT=dsh-web.service -n 200 --no-pager 2>/dev/null " +
                "| grep -oE 'token=[A-Za-z0-9_-]+' | tail -1 | cut -d= -f2",
            // 候选 2：dsh-web service（systemd user unit，常见部署）
            "$xdg journalctl --user -u dsh-web.service -n 200 --no-pager 2>/dev/null " +
                "| grep -oE 'token=[A-Za-z0-9_-]+' | tail -1 | cut -d= -f2",
            // 候选 3：全量 system journal 找 dsh（Unit 名不同/非 systemd 时兜底）
            "journalctl -n 500 --no-pager 2>/dev/null " +
                "| grep -oE 'token=[A-Za-z0-9_-]+' | tail -1 | cut -d= -f2",
            // 候选 4：常见日志文件（手动 nohup 等部署）
            "for f in ~/.dsh/web.log ~/.dsh/web_log ~/.dsh/dsh-web.log; do " +
                "grep -oE 'token=[A-Za-z0-9_-]+' \"\$f\" 2>/dev/null; done | tail -1 | cut -d= -f2"
        )
        for ((i, cmd) in commands.withIndex()) {
            val out = tunnel.execOnce(cmd)
            Log.i(TAG, "autoFetchToken: cmd#$i rc=${if (out == null) "null" else "len=" + out.length}")
            val token = out?.trim()
            if (!token.isNullOrEmpty() && token.length >= 40) {
                Log.i(TAG, "autoFetchToken: SUCCESS len=${token.length} prefix=${token.take(8)}…")
                prefs.edit().putString(PREF_SERVER_TOKEN, token).apply()
                return token
            }
        }
        Log.w(TAG, "autoFetchToken: all commands failed/long-empty; fall back to manual token")
        return null
    }

    /**
     * 加载 dsh web。DSH 0.1.2+ 的浏览器认证：
     *  - 有 token 且尚未种下 cookie：加载 `/?token=…`，服务端 303 → 换 `Set-Cookie`
     *    → 自动跳转干净 `/`（cookie 之后由 WebView 持久持有，无需再带 token）；
     *  - 已认证（上次成功加载过 / cookie 仍在）：直接加载干净 URL。
     *
     * 注意：SSH 隧道断线重连会换本地端口（cookie 按 host:port 绑定失效），
     * 此时 [sshTokenAck] 为 false，会重新走 token 交换。
     */
    private fun connectWeb(url: String, forceToken: Boolean = false) {
        status("连接中… $url")
        connectView?.visibility = View.GONE
        lastUrl = url
        unauthorizedCleanTried = false
        loadRetriesLeft = 3
        prefs.edit().putString("url", url).apply()
        val token = prefs.getString(PREF_SERVER_TOKEN, "")?.trim().orEmpty()
        val needsToken = token.isNotEmpty() && (forceToken || !sshTokenAck)
        if (needsToken) {
            // 431 修复（实测根因）：每次 token 交换 WebView 会追加一个 365 天有效的
            // dsh-auth-* cookie（127.0.0.1 同 host），累计 69 个 ≈ 15.5KB 顶到
            // 服务器 maxHeaderSize 16KB → HTTP 431 → 页面永远加载失败。
            // 要重新认证时先清空 cookie jar（本 WebView 唯一用途就是这一页；
            // 服务端会在 /?token= 交换后下发新 cookie）。
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
        }
        webView?.loadUrl(if (needsToken) "$url/?token=$token" else url)
    }

    // ── SSH 隧道（纯 WebView 用）─────────────────────────────
    private fun connectViaSsh(sshHost: String, sshPort: Int, sshUser: String, remotePort: Int, auth: SshTunnel.Auth) {
        status("SSH 隧道建立中… $sshUser@$sshHost")
        guideStep3Show()
        val app = application as DshApp
        Thread {
            // 旧隧道先关（重复连接/模式切换的泄漏点）
            closeCurrentTunnel()
            val tunnel = SshTunnel(
                sshHost = sshHost, sshPort = sshPort, sshUser = sshUser,
                remoteHost = "127.0.0.1", remotePort = remotePort,
                auth = auth
            )
            tunnel.onStateChange = { s -> runOnUiThread { status("隧道: $s") } }
            tunnel.onLocalBaseChanged = { newBase ->
                runOnUiThread {
                    // 重连换了本地端口：cookie 失效，必须用 token 重新认证
                    sshTokenAck = false
                    connectWeb(newBase)
                }
            }
            tunnel.start()
            val base = tunnel.localBaseUrl
            // 失败先退：隧道没起来就不再干跑 token 探测（4×8s 白等）
            if (base == null) {
                tunnel.close()
                runOnUiThread {
                    status("隧道建立失败（检查 SSH 主机/端口/用户/认证）")
                    guideLineFail(1, "① 检查电脑 ✗ 连不上你的电脑（检查地址/账号/密码）")
                    guideLine(2, "② 建立安全通道 未开始", running = true)
                    endConnect()
                }
                return@Thread
            }
            runOnUiThread { guideLineDone(1, "① 检查电脑 ✓ 已连上电脑") }
            // 自动获取最新 token（服务重启后旧 token 失效；失败静默回退）
            val token = autoFetchToken(tunnel)
            runOnUiThread {
                if (token != null) guideLineDone(2, "② 建立安全通道 ✓ 已连通")
                else guideLineFail(2, "② 建立安全通道 ⚠ 未获取令牌，仍尝试打开")
                persistSshConfig(sshHost, sshPort, sshUser, remotePort, auth)
                app.sshTunnel = tunnel
                sshTokenAck = false
                connectWeb(base)
                refreshConnectState()
                endConnect()
            }
        }.start()
    }

    private fun persistSshConfig(
        sshHost: String, sshPort: Int, sshUser: String, remotePort: Int, auth: SshTunnel.Auth
    ) {
        try {
            val json = JSONObject()
                .put("sshHost", sshHost)
                .put("sshPort", sshPort)
                .put("sshUser", sshUser)
                .put("remoteHost", "127.0.0.1")
                .put("remotePort", remotePort)
            when (auth) {
                is SshTunnel.Auth.Password ->
                    json.put("authType", "password").put("password", auth.password)
                is SshTunnel.Auth.KeyPair ->
                    json.put("authType", "key")
                        .put("keyPath", auth.privateKeyFile.absolutePath)
                        .put("keyPass", auth.passphrase ?: "")
            }
            prefs.edit().putString("ssh_json", json.toString()).apply()
        } catch (_: Exception) {}
    }

    // ── Basic Auth（WebView 隧道/反代场景）────────────────────
    private fun showAuthDialog(handler: HttpAuthHandler, host: String?) {
        if (pendingAuth != null) { handler.cancel(); return }
        pendingAuth = handler
        val usernameInput = EditText(this).apply {
            hint = "username"
            setTextColor(COL_TEXT); setHintTextColor(COL_HINT)
            setBackgroundResource(R.drawable.bg_input)
            setPadding(dp(16), dp(13), dp(16), dp(13))
        }
        val passwordInput = EditText(this).apply {
            hint = "password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(COL_TEXT); setHintTextColor(COL_HINT)
            setBackgroundResource(R.drawable.bg_input)
            setPadding(dp(16), dp(13), dp(16), dp(13))
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
            addView(usernameInput)
            addView(passwordInput, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) })
        }
        AlertDialog.Builder(this)
            .setTitle("${host ?: "服务器"}需要登录")
            .setView(layout)
            .setPositiveButton("登录") { _, _ ->
                pendingAuth?.proceed(usernameInput.text.toString(), passwordInput.text.toString()); pendingAuth = null
            }
            .setNegativeButton("取消") { _, _ -> pendingAuth?.cancel(); pendingAuth = null }
            .setOnDismissListener { pendingAuth = null }
            .show()
    }

    // ── 错误页 ────────────────────────────────────────────────
    private fun showErrorPage(message: String) {
        if (errorView == null) {
            errorView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(COL_BG)
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
                addView(TextView(this@MainActivity).apply {
                    text = "连接失败"
                    textSize = 22f
                    setTextColor(COL_TEXT)
                    gravity = Gravity.CENTER
                })
                addView(TextView(this@MainActivity).apply {
                    text = "检查地址、端口和网络后重试"
                    textSize = 14f
                    setTextColor(COL_MUTED)
                    gravity = Gravity.CENTER
                }, rowParams(top = dp(8)))
                addView(Button(this@MainActivity).apply {
                    text = "重试"
                    isAllCaps = false
                    setTextColor(COL_ACCENT_TEXT)
                    setBackgroundResource(R.drawable.bg_button_primary)
                    setOnClickListener { hideErrorPage(); lastUrl?.let { sshTokenAck = false; connectWeb(it) } }
                }, rowParams(top = dp(24), height = dp(48), width = dp(200)))
                addView(Button(this@MainActivity).apply {
                    text = "换服务器"
                    isAllCaps = false
                    setTextColor(COL_TEXT)
                    setBackgroundResource(R.drawable.bg_button_secondary)
                    setOnClickListener { hideErrorPage(); connectView?.visibility = View.VISIBLE; refreshConnectState() }
                }, rowParams(top = dp(12), height = dp(48), width = dp(200)))
            }
            (webView?.parent as? ViewGroup)?.addView(errorView)
        }
        val msgView = (errorView as? LinearLayout)?.getChildAt(1) as? TextView
        msgView?.text = message
        errorView?.visibility = View.VISIBLE
    }

    private fun hideErrorPage() { errorView?.visibility = View.GONE }

    /**
     * 401 处理。分两段：
     *  1. 若当前 URL 带 `?token=`（token 过期，但 cookie 可能仍有效——服务重启
     *     后 token 更新、cookie 签名密钥持久化），先回退加载干净 URL；
     *  2. 干净 URL 也 401（cookie 确实失效）→ 提示用户更新令牌。
     *
     * 幂等通过 [unauthorizedCleanTried] 标志防死循环（每次 connectWeb 重置）。
     */
    private fun handleUnauthorized(view: WebView?) {
        val url = view?.url ?: lastUrl ?: return
        if (!unauthorizedCleanTried && url.contains("?token=")) {
            unauthorizedCleanTried = true
            sshTokenAck = false
            lastUrl?.let { view?.loadUrl(it.substringBefore("?")) }
            return
        }
        showTokenPromptPage()
    }

    /**
     * DSH 0.1.2+ 浏览器认证提示页：401 时说明需要一次性启动 token。
     * 令牌由应用自动从服务端日志获取——提供「自动获取并重连」一键处理。
     */
    private fun showTokenPromptPage() {
        if (errorView == null) {
            errorView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(COL_BG)
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
                addView(TextView(this@MainActivity).apply {
                    text = "需要访问令牌"
                    textSize = 22f
                    setTextColor(COL_TEXT)
                    gravity = Gravity.CENTER
                })
                addView(TextView(this@MainActivity).apply {
                    text = "dsh 0.1.2+ 需要一次性启动令牌（服务重启后旧令牌失效）。\n应用会自动从服务端日志重新获取；\n若仍失败，请检查服务端 dsh-web.service 是否在运行。"
                    textSize = 14f
                    setTextColor(COL_MUTED)
                    gravity = Gravity.CENTER
                }, rowParams(top = dp(8)))
                addView(Button(this@MainActivity).apply {
                    text = "自动获取令牌并重连"
                    isAllCaps = false
                    setTextColor(COL_ACCENT_TEXT)
                    setBackgroundResource(R.drawable.bg_button_primary)
                    setOnClickListener { reFetchTokenAndReload() }
                }, rowParams(top = dp(24), height = dp(48), width = dp(200)))
                addView(Button(this@MainActivity).apply {
                    text = "重试"
                    isAllCaps = false
                    setTextColor(COL_TEXT)
                    setBackgroundResource(R.drawable.bg_button_secondary)
                    setOnClickListener {
                        hideErrorPage()
                        lastUrl?.let { sshTokenAck = false; connectWeb(it) }
                    }
                }, rowParams(top = dp(12), height = dp(48), width = dp(200)))
            }
            (webView?.parent as? ViewGroup)?.addView(errorView)
        }
        errorView?.visibility = View.VISIBLE
    }

    /** 401 令牌页：用当前隧道重新自动获取令牌并重载（失败则回到提示页）。 */
    private fun reFetchTokenAndReload() {
        val tunnel = (application as DshApp).sshTunnel
        if (tunnel == null) {
            status("尚无 SSH 隧道，请先连接", true)
            hideErrorPage(); connectView?.visibility = View.VISIBLE; refreshConnectState()
            return
        }
        hideErrorPage()
        status("自动获取令牌…")
        Thread {
            val token = autoFetchToken(tunnel)
            runOnUiThread {
                if (token == null || token.length < 40) {
                    status("自动获取令牌失败，请检查服务端 dsh-web.service", true)
                    showTokenPromptPage()
                } else {
                    sshTokenAck = false
                    lastUrl?.let { connectWeb(it) }
                }
            }
        }.start()
    }

    // ── 生命周期 ──────────────────────────────────────────────
    override fun onResume() {
        super.onResume()
        webView?.onResume(); webView?.resumeTimers()
        // 监听服务由 AgentMonitorService 经 ActivityLifecycleCallbacks 自停
        //（外部 stopService 会与 startForegroundService 的 5s 窗口竞态 → 崩溃）
        refreshConnectState()
    }

    override fun onPause() {
        super.onPause()
        webView?.onPause(); webView?.pauseTimers()
        // 通知权限只在首次退后台时询问（一次）；监听服务由 DshApp 的可见性回调启动
        //（onPause 触发会在打开 TuiActivity 时误启动，已在 DshApp 改为全 UI 不可见才启动）
        if (prefs.getString("url", null) != null) requestNotifyPermissionOnce()
    }

    /** Android 13+ 通知运行时权限：只询问一次（notify_asked），
     * in-flight 守卫防权限对话框触发 onPause 导致的重复请求。
     * 无权限时通知静默不响，不影响 FGS 本身。 */
    private fun requestNotifyPermissionOnce() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !notificationAskInFlight &&
            !prefs.getBoolean("notify_asked", false)) {
            notificationAskInFlight = true
            prefs.edit().putBoolean("notify_asked", true).apply()
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) notificationAskInFlight = false
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            connectView?.visibility == View.VISIBLE -> moveTaskToBack(true)
            webView?.canGoBack() == true -> webView?.goBack()
            webView?.url?.startsWith("http") == true -> { connectView?.visibility = View.VISIBLE; refreshConnectState() }
            else -> moveTaskToBack(true)
        }
    }

    /**
     * 沉浸式全屏：内容画到状态栏/导航栏后面（含刘海）。
     * 同时使用 WindowInsetsController（API 30+ 正道）和传统 systemUiVisibility 标志，
     * 以兼容三星 One UI / 不同 WebView 版本对沉浸式的处理差异。
     */
    private fun applyImmersive() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        // 让状态栏/导航栏区域透明，WebView 内容从下面一直铺到屏幕边缘
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
        window.decorView.post {
            val c = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
            c.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars() or
                   androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            c.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            // 传统沉浸标志兜底（部分 One UI / 老 WebView 依赖它才能真正全屏）
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersive()
    }

    // ── 小工具 ────────────────────────────────────────────────
    /** 连接守卫：CAS 置位；失败时提示用户等待。 */
    private fun beginConnect(): Boolean {
        if (!connecting.compareAndSet(false, true)) {
            status("正在连接中，请稍候…")
            return false
        }
        return true
    }

    private fun endConnect() { connecting.set(false) }

    // ── 引导流 Step3 状态（类级：connectViaSsh/autoConnectSsh/onPageFinished 共用）────
    private fun guideStep3Show() {
        stepGuideCard?.visibility = View.VISIBLE
        stepGuideStep2?.visibility = View.GONE
        guideLine(0, "① 检查电脑 正在连接…", running = true)
        guideLine(1, "② 建立安全通道", running = true)
        guideLine(2, "③ 打开 dsh 网页", running = true)
    }
    private fun guideLine(index: Int, text: String, running: Boolean = false) {
        val v = when (index) {
            0 -> stepGuideLine1; 1 -> stepGuideLine2; else -> stepGuideLine3
        } ?: return
        v.text = text
        v.setTextColor(if (running) COL_MUTED else COL_ACCENT)
    }
    private fun guideLineDone(index: Int, text: String) {
        val v = when (index) {
            0 -> stepGuideLine1; 1 -> stepGuideLine2; else -> stepGuideLine3
        } ?: return
        v.text = text
        v.setTextColor(COL_ACCENT)
    }
    private fun guideLineFail(index: Int, text: String) {
        val v = when (index) {
            0 -> stepGuideLine1; 1 -> stepGuideLine2; else -> stepGuideLine3
        } ?: return
        v.text = text
        v.setTextColor(COL_ERROR)
    }
    private fun guideBackToStep2() {
        stepGuideStep2?.visibility = View.VISIBLE
        stepGuideCard?.visibility = View.GONE
    }

    /** 关闭并释放当前 SSH 隧道（无则 no-op）。 */
    private fun closeCurrentTunnel() {
        val app = application as DshApp
        app.sshTunnel?.close()
        app.sshTunnel = null
    }

    /** 断开连接：停隧道、清回连 URL、回连接屏。 */
    private fun disconnectCurrent() {
        closeCurrentTunnel()
        prefs.edit().remove("url").apply()
        sshTokenAck = false
        unauthorizedCleanTried = false
        webView?.stopLoading()
        webView?.loadUrl("about:blank")
        connectView?.visibility = View.VISIBLE
        refreshConnectState()
        status("已断开")
    }

    /** 按隧道状态刷新连接屏上的「断开连接」按钮。 */
    private fun refreshConnectState() {
        disconnectButton?.visibility =
            if ((application as DshApp).sshTunnel != null) View.VISIBLE else View.GONE
    }

    /** 失败自动重试：5s 后重载（若隧道仍活；watchdog 会重建死隧道）。每次 connectWeb 重置 3 次余量。 */
    private fun scheduleLoadRetry() {
        if (loadRetriesLeft <= 0) return
        loadRetriesLeft--
        webView?.postDelayed({
            if ((application as DshApp).sshTunnel != null && lastUrl != null) {
                Log.i(TAG, "auto-retry page load (retriesLeft=$loadRetriesLeft) via $lastUrl")
                sshTokenAck = false
                connectWeb(lastUrl!!)
            }
        }, 5000)
    }

    private fun spacer(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, h) }
    private fun rowParams(top: Int = 0, width: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
                          height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(width, height).apply { topMargin = top }
    private fun dp(n: Int) = (n * resources.displayMetrics.density + 0.5f).toInt()
}