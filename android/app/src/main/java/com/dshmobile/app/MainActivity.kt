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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.Switch
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
import java.io.File
import java.io.FileOutputStream

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
    private lateinit var prefs: android.content.SharedPreferences

    private companion object {
        // 黑白色调
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

        const val PREF_SSH_ENABLED = "ssh_enabled" // 上次是否通过 SSH 隧道连接
        const val PREF_SERVER_PROTO = "server_proto" // 用户填写的 dsh web 协议
        const val PREF_SERVER_HOST = "server_host"  // 用户填写的 dsh web 主机（非隧道本地随机端口）
        const val PREF_SERVER_PORT = "server_port"  // 用户填写的 dsh web 端口（远程/隧道目标端口）

        // dsh 前端 RPC 依赖 crypto.randomUUID；WebView 在局域网明文 HTTP
        //（非安全上下文）下访问不到该 API，会导致全部 RPC 失败（白屏）。
        // 标准 UUID v4 polyfill（幂等）：与 dsh-lan-access 插件的兜底一致。
        val CRYPTO_POLYFILL = """ // trimIndent 非编译期常量，故用 val
            (function(){
              try {
                var C = window.Crypto;
                if (C && !C.prototype.randomUUID) {
                  C.prototype.randomUUID = function() {
                    var b = crypto.getRandomValues(new Uint8Array(16));
                    b[6] = (b[6] & 0x0f) | 0x40;
                    b[8] = (b[8] & 0x3f) | 0x80;
                    var h = [];
                    for (var i = 0; i < 16; i++) h.push((b[i] + 256).toString(16).slice(1));
                    return h.slice(0,4).join('') + '-' + h.slice(4,6).join('') + '-' +
                           h.slice(6,8).join('') + '-' + h.slice(8,10).join('') + '-' + h.slice(10,16).join('');
                  };
                }
              } catch(e) {}
            })();
        """.trimIndent()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("dsh-mobile", Context.MODE_PRIVATE)

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
                databaseEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                builtInZoomControls = true
                displayZoomControls = false
                setSupportZoom(true)
                setInitialScale(75)
                loadWithOverviewMode = true
                useWideViewPort = true
            }
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(this, CRYPTO_POLYFILL, setOf("*"))
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) { hideErrorPage() }
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    if (request?.isForMainFrame == true) showErrorPage(error?.description?.toString() ?: "网络错误")
                }
                override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, resp: android.webkit.WebResourceResponse?) {
                    if (request?.isForMainFrame == true) showErrorPage("HTTP ${resp?.statusCode}")
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

        // 自动连接（纯 WebView + SSH 优先）：上次带 SSH 的先恢复隧道，再加载本地回环 URL。
        // 直连模式仍直接加载保存的 URL，避免每次启动重新输入。
        val savedUrl = prefs.getString("url", null)
        val currentUrl = webView?.url
        val baseMatch = savedUrl != null && currentUrl != null &&
            currentUrl.trimEnd('/').startsWith(savedUrl.trimEnd('/'))
        // 向后兼容：旧版本没有 ssh_enabled 标志，但保存的 URL 是隧道回环地址、
        // 且存在完整 SSH 配置时也自动恢复隧道（否则会加载一个早已失效的旧端口）。
        val savedIsSshLoopback = savedUrl?.startsWith("http://127.0.0.1:") == true
        val sshSaved = prefs.getString("ssh_json", null) != null &&
            (prefs.getBoolean(PREF_SSH_ENABLED, false) || savedIsSshLoopback)
        if (savedUrl != null && !baseMatch) {
            if (sshSaved) {
                autoConnectSsh(savedUrl)
            } else {
                connectView?.visibility = View.GONE
                lastUrl = savedUrl
                webView?.loadUrl(savedUrl)
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
        status("自动重建 SSH 隧道…")
        connectView?.visibility = View.VISIBLE
        Thread {
            val auth = if (savedSsh.optString("authType", "password") == "key") {
                val keyPath = savedSsh.optString("keyPath", "")
                if (keyPath.isBlank()) {
                    runOnUiThread { status("SSH 私钥路径为空，请在连接屏重新配置") }
                    return@Thread
                }
                SshTunnel.Auth.KeyPair(
                    java.io.File(keyPath),
                    savedSsh.optString("keyPass").ifEmpty { null }
                )
            } else {
                SshTunnel.Auth.Password(savedSsh.optString("password", ""))
            }
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
                    webView?.loadUrl(newBase)
                }
            }
            tunnel.start()
            val base = tunnel.localBaseUrl
            runOnUiThread {
                if (base == null) {
                    tunnel.close()
                    status("自动连接失败，请在连接屏手动重试")
                    return@runOnUiThread
                }
                app.sshTunnel = tunnel
                prefs.edit().putString("url", base).apply()
                connectView?.visibility = View.GONE
                lastUrl = base
                webView?.loadUrl(base)
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

        fun input(hint: String, prefill: String = "", pwd: Boolean = false): EditText =
            EditText(this).apply {
                this.hint = hint
                textSize = 14f
                setTextColor(COL_TEXT)
                setHintTextColor(COL_HINT)
                setBackgroundResource(R.drawable.bg_input)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setSingleLine(true)
                setHorizontallyScrolling(true)
                if (pwd) transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
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

        // Logo + 标题
        card.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        })
        card.addView(TextView(this).apply {
            text = "DSH Mobile"
            textSize = 21f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setTextColor(COL_TITLE)
            gravity = Gravity.CENTER
        }, rowParams(top = dp(6), width = ViewGroup.LayoutParams.MATCH_PARENT))
        card.addView(TextView(this).apply {
            text = "DeepSeek Harness · 手机端"
            textSize = 11f
            setTextColor(COL_MUTED)
            gravity = Gravity.CENTER
        }, rowParams(top = dp(4), width = ViewGroup.LayoutParams.MATCH_PARENT))

        card.addView(label("服务器地址"), rowParams(top = dp(16)))

        // 协议分段：优先使用独立保存的服务器信息，避免被 SSH 随机本地端口覆盖
        val savedUrl = prefs.getString("url", null)
        val savedSshForServer = prefs.getString("ssh_json", null)?.let {
            try { JSONObject(it) } catch (_: Exception) { null }
        }
        var prefillProto = prefs.getString(PREF_SERVER_PROTO, "http") ?: "http"
        var prefillHost = prefs.getString(PREF_SERVER_HOST, "") ?: ""
        var prefillPort = prefs.getString(PREF_SERVER_PORT, DEFAULT_PORT) ?: DEFAULT_PORT
        if (prefillHost.isBlank()) {
            // 老版本/首次升级：从旧的完整 URL 反向解析
            savedUrl?.let {
                Regex("^(https?)://([^:]+)(?::(\\d+))?$").find(it.trim())?.let { m ->
                    prefillProto = m.groupValues[1]; prefillHost = m.groupValues[2]
                    if (m.groupValues[3].isNotEmpty()) prefillPort = m.groupValues[3]
                }
            }
            // 如果是 SSH 留下的 127.0.0.1:随机端口，就换成稳定的隧道目标端口
            if (prefillHost == "127.0.0.1") {
                prefillHost = savedSshForServer?.optString("remoteHost") ?: prefillHost
                prefillPort = (savedSshForServer?.optInt("remotePort", DEFAULT_PORT.toInt()) ?: DEFAULT_PORT.toInt()).toString()
            }
        }
        val httpBtn = segment("http", prefillProto != "https")
        val httpsBtn = segment("https", prefillProto == "https")
        val protocolGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(httpBtn, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginEnd = dp(6) })
            addView(httpsBtn, LinearLayout.LayoutParams(0, dp(38), 1f).apply { marginStart = dp(6) })
        }
        card.addView(protocolGroup, rowParams(top = dp(8), width = ViewGroup.LayoutParams.MATCH_PARENT))

        val hostInput = EditText(this).apply {
            hint = "服务器 IP / 域名"
            textSize = 14f
            setText(prefillHost)
            setTextColor(COL_TEXT); setHintTextColor(COL_HINT)
            setBackgroundResource(R.drawable.bg_input)
            setPadding(dp(12), dp(11), dp(12), dp(11)); setSingleLine(true)
            setHorizontallyScrolling(true)
        }
        val portInput = EditText(this).apply {
            hint = "端口"; textSize = 14f; setText(prefillPort)
            setTextColor(COL_TEXT); setHintTextColor(COL_HINT)
            setBackgroundResource(R.drawable.bg_input)
            setPadding(dp(12), dp(11), dp(12), dp(11)); setSingleLine(true)
            setHorizontallyScrolling(true)
        }
        val serverRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(hostInput, LinearLayout.LayoutParams(0, dp(42), 3f).apply { marginEnd = dp(8) })
            addView(portInput, LinearLayout.LayoutParams(0, dp(42), 1f))
        }
        card.addView(serverRow, rowParams(top = dp(8), width = ViewGroup.LayoutParams.MATCH_PARENT))

        // SSH 隧道
        val savedSsh = prefs.getString("ssh_json", null)?.let {
            try { JSONObject(it) } catch (_: Exception) { null }
        }
        card.addView(label("SSH 隧道"), rowParams(top = dp(14)))
        val sshToggle = Switch(this).apply {
            text = "启用 SSH"
            setTextColor(COL_TEXT)
            thumbTintList = ColorStateList.valueOf(COL_ACCENT)
            trackTintList = ColorStateList.valueOf(0x22FFFFFF.toInt())
            isChecked = prefs.getBoolean(PREF_SSH_ENABLED, false)
        }
        card.addView(sshToggle, rowParams(top = dp(6)))

        val sshHostInput = input("SSH 主机", savedSsh?.optString("sshHost") ?: "")
        val sshPortInput = input("SSH 端口", savedSsh?.optString("sshPort") ?: "22")
        val sshUserInput = input("用户名", savedSsh?.optString("sshUser") ?: "")
        val sshPassInput = input("SSH 密码", savedSsh?.optString("password") ?: "", pwd = true)

        // 认证方式：密码 / 私钥
        val authPassBtn = segment("密码", true)
        val authKeyBtn = segment("私钥", false)
        val authGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(authPassBtn, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginEnd = dp(6) })
            addView(authKeyBtn, LinearLayout.LayoutParams(0, dp(36), 1f).apply { marginStart = dp(6) })
        }
        val keyPathInput = input("私钥路径（可导入）", savedSsh?.optString("keyPath") ?: "")
        keyPathInput.isFocusable = true
        sshKeyPathInput = keyPathInput
        val keyPassInput = input("私钥口令（可选）", savedSsh?.optString("keyPass") ?: "", pwd = true)
        val browseKeyBtn = Button(this).apply {
            text = "浏览导入私钥…"
            isAllCaps = false
            setTextColor(COL_TEXT)
            setBackgroundResource(R.drawable.bg_button_secondary)
            setOnClickListener { pickSshKey() }
        }

        val sshFields = listOf(sshHostInput, sshPortInput, sshUserInput, sshPassInput,
            authGroup, keyPathInput, keyPassInput, browseKeyBtn)
        sshFields.forEach {
            it.visibility = View.GONE
            card.addView(it, rowParams(
                top = dp(6), height = dp(40), width = ViewGroup.LayoutParams.MATCH_PARENT
            ))
        }

        // 关键：认证方式控制密码/私钥字段显隐
        val savedAuthType = savedSsh?.optString("authType", "password") ?: "password"
        if (savedAuthType == "key") authKeyBtn.isChecked = true else authPassBtn.isChecked = true
        fun syncAuthFields() {
            val key = authKeyBtn.isChecked
            keyPathInput.visibility = if (key) View.VISIBLE else View.GONE
            keyPassInput.visibility = if (key) View.VISIBLE else View.GONE
            browseKeyBtn.visibility = if (key) View.VISIBLE else View.GONE
            sshPassInput.visibility = if (key) View.GONE else if (sshToggle.isChecked) View.VISIBLE else View.GONE
        }
        authGroup.setOnCheckedChangeListener { _, _ -> syncAuthFields() }

        fun syncSshVisibility() {
            val on = sshToggle.isChecked
            listOf(sshHostInput, sshPortInput, sshUserInput, authGroup).forEach { it.visibility = if (on) View.VISIBLE else View.GONE }
            if (on) {
                syncAuthFields()
            } else {
                listOf(keyPathInput, keyPassInput, browseKeyBtn).forEach { it.visibility = View.GONE }
            }
        }
        sshToggle.setOnCheckedChangeListener { _, _ -> syncSshVisibility() }
        syncAuthFields()
        syncSshVisibility()

        card.addView(spacer(dp(10)))

        statusView = TextView(this).apply {
            textSize = 12f
            setTextColor(COL_MUTED)
            gravity = Gravity.CENTER
            minHeight = dp(28)
            setBackgroundResource(R.drawable.bg_status_pill)
            setPadding(dp(14), dp(4), dp(14), dp(4))
            visibility = View.GONE
        }
        card.addView(statusView, rowParams(top = dp(10), width = ViewGroup.LayoutParams.MATCH_PARENT))

        card.addView(Button(this).apply {
            text = "连接"
            isAllCaps = false
            setTextColor(COL_ACCENT_TEXT)
            setBackgroundResource(R.drawable.bg_button_primary)
            setOnClickListener {
                val useSsh = sshToggle.isChecked
                prefs.edit().putBoolean(PREF_SSH_ENABLED, useSsh).apply()

                val remotePort = portInput.text.toString().trim().ifEmpty { DEFAULT_PORT }.toIntOrNull() ?: 3080
                if (useSsh) {
                    val sh = sshHostInput.text.toString().trim()
                    val su = sshUserInput.text.toString().trim()
                    val sport = sshPortInput.text.toString().trim().toIntOrNull() ?: 22
                    if (sh.isBlank() || su.isBlank()) { status("SSH 主机/用户名不能为空"); return@setOnClickListener }
                    val auth = if (authKeyBtn.isChecked) {
                        val path = keyPathInput.text.toString().trim()
                        if (path.isBlank()) { status("请选择 SSH 私钥"); return@setOnClickListener }
                        SshTunnel.Auth.KeyPair(File(path), keyPassInput.text.toString().ifEmpty { null })
                    } else {
                        SshTunnel.Auth.Password(sshPassInput.text.toString())
                    }
                    connectViaSsh(sh, sport, su, remotePort, auth)
                } else {
                    val host = hostInput.text.toString().trim()
                    if (host.isBlank()) { status("host 不能为空"); return@setOnClickListener }
                    val proto = if (protocolGroup.checkedRadioButtonId == httpsBtn.id) "https" else "http"
                    prefs.edit()
                        .putString(PREF_SERVER_PROTO, proto)
                        .putString(PREF_SERVER_HOST, host)
                        .putString(PREF_SERVER_PORT, remotePort.toString())
                        .apply()
                    val url = "$proto://$host:$remotePort"
                    connectWeb(url)
                }
            }
        }, rowParams(top = dp(12), height = dp(44), width = ViewGroup.LayoutParams.MATCH_PARENT))

        card.addView(TextView(this).apply {
            text = "网页=桌面级 · SSH=解锁本机配置"
            textSize = 10f
            setTextColor(COL_DIM)
            gravity = Gravity.CENTER
        }, rowParams(top = dp(8)))

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

    private fun status(msg: String) {
        statusView?.text = msg
        statusView?.visibility = if (msg.isBlank()) View.GONE else View.VISIBLE
    }

    // ── WebView 直连 ─────────────────────────────────────────
    private fun connectWeb(url: String) {
        status("连接中… $url")
        connectView?.visibility = View.GONE
        lastUrl = url
        prefs.edit().putString("url", url).apply()
        webView?.loadUrl(url)
    }

    // ── SSH 隧道（纯 WebView 用）─────────────────────────────
    private fun connectViaSsh(sshHost: String, sshPort: Int, sshUser: String, remotePort: Int, auth: SshTunnel.Auth) {
        status("SSH 隧道建立中… $sshUser@$sshHost")
        val app = application as DshApp
        Thread {
            val tunnel = SshTunnel(
                sshHost = sshHost, sshPort = sshPort, sshUser = sshUser,
                remoteHost = "127.0.0.1", remotePort = remotePort,
                auth = auth
            )
            tunnel.onStateChange = { s -> runOnUiThread { status("隧道: $s") } }
            tunnel.onLocalBaseChanged = { newBase ->
                runOnUiThread { connectWeb(newBase) }
            }
            tunnel.start()
            val base = tunnel.localBaseUrl
            runOnUiThread {
                if (base == null) { tunnel.close(); status("隧道建立失败（检查 SSH 主机/端口/用户/认证）"); return@runOnUiThread }
                persistSshConfig(sshHost, sshPort, sshUser, remotePort, auth)
                prefs.edit()
                    .putString(PREF_SERVER_PROTO, "http")
                    .putString(PREF_SERVER_HOST, "127.0.0.1")
                    .putString(PREF_SERVER_PORT, remotePort.toString())
                    .apply()
                app.sshTunnel = tunnel
                connectWeb(base)
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
                    setOnClickListener { hideErrorPage(); lastUrl?.let { webView?.loadUrl(it) } }
                }, rowParams(top = dp(24), height = dp(48), width = dp(200)))
                addView(Button(this@MainActivity).apply {
                    text = "换服务器"
                    isAllCaps = false
                    setTextColor(COL_TEXT)
                    setBackgroundResource(R.drawable.bg_button_secondary)
                    setOnClickListener { hideErrorPage(); connectView?.visibility = View.VISIBLE }
                }, rowParams(top = dp(12), height = dp(48), width = dp(200)))
            }
            (webView?.parent as? ViewGroup)?.addView(errorView)
        }
        val msgView = (errorView as? LinearLayout)?.getChildAt(1) as? TextView
        msgView?.text = message
        errorView?.visibility = View.VISIBLE
    }

    private fun hideErrorPage() { errorView?.visibility = View.GONE }

    // ── 生命周期 ──────────────────────────────────────────────
    override fun onResume() {
        super.onResume()
        webView?.onResume(); webView?.resumeTimers()
        AgentMonitorService.stop(this) // 回前台：页面自己能看到，停掉监听服务
    }

    override fun onPause() {
        super.onPause()
        webView?.onPause(); webView?.pauseTimers()
        // 退后台：若已连接过（有 url），启动 agent 完成通知监听
        if (prefs.getString("url", null) != null) requestNotifyPermissionThenMonitor()
    }

    /** Android 13+ 通知运行时权限；已授权/被拒都尝试启动（无权限时通知静默不响，不影响 FGS）。 */
    private fun requestNotifyPermissionThenMonitor() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
        AgentMonitorService.start(this)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            connectView?.visibility == View.VISIBLE -> moveTaskToBack(true)
            webView?.canGoBack() == true -> webView?.goBack()
            webView?.url?.startsWith("http") == true -> connectView?.visibility = View.VISIBLE
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
    private fun spacer(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, h) }
    private fun rowParams(top: Int = 0, width: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
                          height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(width, height).apply { topMargin = top }
    private fun dp(n: Int) = (n * resources.displayMetrics.density + 0.5f).toInt()
}