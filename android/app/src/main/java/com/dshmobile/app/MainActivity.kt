package com.dshmobile.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.dshmobile.protocol.DshClient
import com.dshmobile.protocol.SshTunnel
import org.json.JSONObject

/**
 * dsh-mobile 连接屏 —— 双模式：
 *
 *  ◆ 完整网页（默认）：全屏 WebView 加载 dsh web 前端，功能与桌面 100% 一致
 *    （Markdown/代码高亮/设置页……），自带：
 *      - crypto.randomUUID 文档启动注入（局域网明文 HTTP 防白屏，与 dsh-lan-access 幂等同款）
 *      - Basic Auth 弹窗（隧道/反代场景）
 *      - 错误页（重试 / 换服务器），retainedWebView 跨重建保活
 *
 *  ◆ 原生简版：内置协议客户端（/api RPC + 双 WebSocket downlink），轻量遥控
 *
 *  ◆ SSH 隧道（两种模式可用）：经 sshd 本地端口转发访问，服务端视角为回环，
 *    配置平面（settings/credentials/…）全解锁 —— 官方文档认可的合规远程路径。
 */
class MainActivity : Activity() {
    private var webView: WebView? = null
    private var connectView: View? = null
    private var errorView: View? = null
    private var lastUrl: String? = null
    private var pendingAuth: HttpAuthHandler? = null
    private var statusView: TextView? = null
    private lateinit var prefs: android.content.SharedPreferences

    private companion object {
        const val COL_BG = 0xFF1A1A2E.toInt()
        const val COL_ACCENT = 0xFFE94560.toInt()
        const val COL_TITLE = 0xFF0F3460.toInt()
        const val COL_TEXT = 0xFFFFFFFF.toInt()
        const val COL_HINT = 0x80FFFFFF.toInt()
        const val COL_INPUT_BG = 0x33FFFFFF.toInt()
        const val COL_MUTED = 0xB3FFFFFF.toInt()
        const val COL_DIM = 0x80FFFFFF.toInt()
        const val UA_MARKER = "DshMobile/1.0"
        const val DEFAULT_PORT = "3080"

        const val PREF_MODE = "mode" // "web" | "native"
        const val PREF_SSH_ENABLED = "ssh_enabled" // 上次是否通过 SSH 隧道连接

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
        }
        root.addView(webView)

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

        // 自动连接（web + SSH 优先）：上次模式带 SSH 的先恢复隧道，再加载本地回环 URL。
        // 直连模式仍直接加载保存的 URL，避免每次启动重新输入。
        val savedUrl = prefs.getString("url", null)
        val savedMode = prefs.getString(PREF_MODE, "web") ?: "web"
        val currentUrl = webView?.url
        val baseMatch = savedUrl != null && currentUrl != null &&
            currentUrl.trimEnd('/').startsWith(savedUrl.trimEnd('/'))
        // 向后兼容：旧版本没有 ssh_enabled 标志，但保存的 URL 是隧道回环地址、
        // 且存在完整 SSH 配置时也自动恢复隧道（否则会加载一个早已失效的旧端口）。
        val savedIsSshLoopback = savedUrl?.startsWith("http://127.0.0.1:") == true
        val sshSaved = prefs.getString("ssh_json", null) != null &&
            (prefs.getBoolean(PREF_SSH_ENABLED, false) || savedIsSshLoopback)
        if (savedMode == "web" && savedUrl != null && !baseMatch) {
            if (sshSaved) {
                autoConnectSsh(savedUrl)
            } else {
                connectView?.visibility = View.GONE
                lastUrl = savedUrl
                webView?.loadUrl(savedUrl)
            }
        } else if (savedMode == "web" && !currentUrl.isNullOrBlank()) {
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
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COL_BG)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
        }
        val wrapper = FrameLayout(this).apply { setBackgroundColor(COL_BG); addView(column) }

        column.addView(TextView(this).apply {
            text = "DSH Mobile"; textSize = 28f; setTextColor(COL_TITLE)
        })
        column.addView(TextView(this).apply {
            text = "Connect to DeepSeek Harness on your computer"; textSize = 14f; setTextColor(COL_MUTED)
        }, rowParams(top = dp(8)))

        // 模式选择：完整网页（默认）/ 原生简版
        val webBtn = RadioButton(this).apply { id = View.generateViewId(); text = "完整网页（桌面全功能）"; setTextColor(COL_TEXT) }
        val nativeBtn = RadioButton(this).apply { id = View.generateViewId(); text = "原生简版（轻量遥控）"; setTextColor(COL_TEXT) }
        val modeGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL; addView(webBtn); addView(nativeBtn) }
        column.addView(modeGroup, rowParams(top = dp(12), width = dp(300)))
        modeGroup.check(if ((prefs.getString(PREF_MODE, "web") ?: "web") == "native") nativeBtn.id else webBtn.id)

        // SSH 隧道开关
        val sshToggle = android.widget.CheckBox(this).apply {
            text = "SSH 隧道（解锁设置/凭据等本机限制接口）"; setTextColor(COL_MUTED)
        }
        column.addView(sshToggle, rowParams(top = dp(8), width = dp(300)))

        val savedSsh = prefs.getString("ssh_json", null)?.let {
            try { JSONObject(it) } catch (_: Exception) { null }
        }
        fun sshField(hint: String, key: String, prefill: String? = null, pwd: Boolean = false): EditText =
            EditText(this).apply {
                this.hint = hint
                setTextColor(COL_TEXT); setHintTextColor(COL_DIM); setBackgroundColor(COL_INPUT_BG)
                if (pwd) transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
                (prefill ?: savedSsh?.optString(key) ?: "").let { if (it.isNotEmpty()) setText(it) }
            }
        val sshHostInput = sshField("SSH 主机（电脑 IP / 域名）", "sshHost")
        val sshPortInput = sshField("SSH 端口", "sshPort", prefill = savedSsh?.optString("sshPort") ?: "22")
        val sshUserInput = sshField("SSH 用户名", "sshUser")
        val sshPassInput = sshField("SSH 密码", "password", pwd = true)
        val sshFields = listOf(sshHostInput, sshPortInput, sshUserInput, sshPassInput)
        sshFields.forEach {
            it.visibility = View.GONE
            column.addView(it, rowParams(top = dp(8), width = dp(300), height = dp(44)))
        }
        // 恢复上次 SSH 开关状态；勾选时立即显示字段
        sshToggle.isChecked = prefs.getBoolean(PREF_SSH_ENABLED, false)
        sshFields.forEach { it.visibility = if (sshToggle.isChecked) View.VISIBLE else View.GONE }
        sshToggle.setOnCheckedChangeListener { _, checked ->
            sshFields.forEach { it.visibility = if (checked) View.VISIBLE else View.GONE }
        }

        column.addView(spacer(dp(16)))

        // dsh web 地址
        val savedUrl = prefs.getString("url", null)
        var prefillProto = "http"; var prefillHost = ""; var prefillPort = DEFAULT_PORT
        savedUrl?.let {
            Regex("^(https?)://([^:]+)(?::(\\d+))?$").find(it.trim())?.let { m ->
                prefillProto = m.groupValues[1]; prefillHost = m.groupValues[2]
                if (m.groupValues[3].isNotEmpty()) prefillPort = m.groupValues[3]
            }
        }
        val httpBtn = RadioButton(this).apply { id = View.generateViewId(); text = "http"; setTextColor(COL_TEXT) }
        val httpsBtn = RadioButton(this).apply { id = View.generateViewId(); text = "https"; setTextColor(COL_TEXT) }
        val protocolGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL; addView(httpBtn); addView(httpsBtn)
            check(if (prefillProto == "https") httpsBtn.id else httpBtn.id)
        }
        column.addView(protocolGroup, rowParams(top = dp(8), width = dp(300)))

        val hostInput = EditText(this).apply {
            hint = "192.168.1.100 / 100.x.x.x / 隧道域名"; setText(prefillHost)
            setTextColor(COL_TEXT); setHintTextColor(COL_DIM); setBackgroundColor(COL_INPUT_BG)
        }
        column.addView(hostInput, rowParams(top = dp(12), width = dp(300), height = dp(46)))

        val portInput = EditText(this).apply {
            hint = "port"; setText(prefillPort)
            setTextColor(COL_TEXT); setHintTextColor(COL_DIM); setBackgroundColor(COL_INPUT_BG)
        }
        column.addView(portInput, rowParams(top = dp(12), width = dp(300), height = dp(46)))

        column.addView(spacer(dp(20)))

        statusView = TextView(this).apply { textSize = 13f; setTextColor(COL_MUTED); gravity = Gravity.CENTER }

        column.addView(Button(this).apply {
            text = "Connect"; setTextColor(COL_TEXT); setBackgroundColor(COL_ACCENT)
            setOnClickListener {
                val useSsh = sshToggle.isChecked
                val mode = if (modeGroup.checkedRadioButtonId == nativeBtn.id) "native" else "web"
                prefs.edit().putString(PREF_MODE, mode).apply()

                val remotePort = portInput.text.toString().trim().ifEmpty { DEFAULT_PORT }.toIntOrNull() ?: 3080
                prefs.edit().putBoolean(PREF_SSH_ENABLED, useSsh).apply()
                if (useSsh) {
                    val sh = sshHostInput.text.toString().trim()
                    val su = sshUserInput.text.toString().trim()
                    val sp = sshPassInput.text.toString()
                    val sport = sshPortInput.text.toString().trim().toIntOrNull() ?: 22
                    if (sh.isBlank() || su.isBlank()) { status("SSH 主机/用户名不能为空"); return@setOnClickListener }
                    connectViaSsh(sh, sport, su, remotePort, sp, mode)
                } else {
                    val host = hostInput.text.toString().trim()
                    if (host.isBlank()) { status("host 不能为空"); return@setOnClickListener }
                    val proto = if (protocolGroup.checkedRadioButtonId == httpsBtn.id) "https" else "http"
                    val url = "$proto://$host:$remotePort"
                    if (mode == "native") connectNative(url) else connectWeb(url)
                }
            }
        }, rowParams(top = dp(8), height = dp(48), width = dp(300)))

        column.addView(statusView, rowParams(top = dp(12)))
        column.addView(TextView(this).apply {
            text = "完整网页=桌面级功能 · 原生简版=轻量遥控 · SSH=解锁本机接口"
            textSize = 11f; setTextColor(COL_DIM); gravity = Gravity.CENTER
        }, rowParams(top = dp(20)))
        return wrapper
    }

    private fun status(msg: String) { statusView?.text = msg }

    // ── 模式 1：WebView 直连 ──────────────────────────────────
    private fun connectWeb(url: String) {
        status("连接中… $url")
        connectView?.visibility = View.GONE
        lastUrl = url
        prefs.edit().putString("url", url).apply()
        webView?.loadUrl(url)
    }

    // ── 模式 2：原生协议客户端 ────────────────────────────────
    private fun connectNative(baseUrl: String) {
        status("连接中… $baseUrl")
        val app = application as DshApp
        val dsh = DshClient(baseUrl)
        app.client = dsh
        Thread {
            val desc = dsh.hostDescribe()
            runOnUiThread {
                if (desc.isOk) {
                    prefs.edit().putString("url", baseUrl).apply()
                    status("已连接，打开会话…")
                    Thread { dsh.start() }.start()
                    startActivity(Intent(this, ConversationActivity::class.java))
                } else {
                    val err = (desc as? com.dshmobile.protocol.Rpc.Result.Err)?.error?.display ?: "连接失败"
                    status(err)
                }
            }
        }.start()
    }

    // ── SSH 隧道（两种模式共用）───────────────────────────────
    private fun connectViaSsh(sshHost: String, sshPort: Int, sshUser: String, remotePort: Int, password: String, mode: String) {
        status("SSH 隧道建立中… $sshUser@$sshHost")
        val app = application as DshApp
        Thread {
            val tunnel = SshTunnel(
                sshHost = sshHost, sshPort = sshPort, sshUser = sshUser,
                remoteHost = "127.0.0.1", remotePort = remotePort,
                auth = SshTunnel.Auth.Password(password)
            )
            tunnel.onStateChange = { s -> runOnUiThread { status("隧道: $s") } }
            tunnel.onLocalBaseChanged = { newBase ->
                runOnUiThread { connectWeb(newBase) }
            }
            tunnel.start()
            val base = tunnel.localBaseUrl
            runOnUiThread {
                if (base == null) { tunnel.close(); status("隧道建立失败（检查 SSH 主机/端口/用户/密码）"); return@runOnUiThread }
                persistSshConfig(sshHost, sshPort, sshUser, remotePort, password)
                app.sshTunnel = tunnel
                if (mode == "native") connectNative(base) else connectWeb(base)
            }
        }.start()
    }

    private fun persistSshConfig(sshHost: String, sshPort: Int, sshUser: String, remotePort: Int, password: String) {
        try {
            prefs.edit().putString("ssh_json", JSONObject()
                .put("sshHost", sshHost).put("sshPort", sshPort).put("sshUser", sshUser)
                .put("remoteHost", "127.0.0.1").put("remotePort", remotePort)
                .put("authType", "password").put("password", password).toString()).apply()
        } catch (_: Exception) {}
    }

    // ── Basic Auth（WebView 隧道/反代场景）────────────────────
    private fun showAuthDialog(handler: HttpAuthHandler, host: String?) {
        if (pendingAuth != null) { handler.cancel(); return }
        pendingAuth = handler
        val usernameInput = EditText(this).apply {
            hint = "username"; setTextColor(COL_TEXT); setHintTextColor(COL_HINT); setBackgroundColor(COL_INPUT_BG)
        }
        val passwordInput = EditText(this).apply {
            hint = "password"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(COL_TEXT); setHintTextColor(COL_HINT); setBackgroundColor(COL_INPUT_BG)
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
                setBackgroundColor(COL_BG); gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
                )
                addView(TextView(this@MainActivity).apply {
                    text = "连接失败"; textSize = 20f; setTextColor(COL_TEXT); gravity = Gravity.CENTER
                })
                addView(TextView(this@MainActivity).apply {
                    text = "检查地址、端口和网络后重试"; textSize = 14f; setTextColor(COL_MUTED); gravity = Gravity.CENTER
                }, rowParams(top = dp(8)))
                addView(Button(this@MainActivity).apply {
                    text = "重试"; setTextColor(COL_TEXT); setBackgroundColor(COL_ACCENT)
                    setOnClickListener { hideErrorPage(); lastUrl?.let { webView?.loadUrl(it) } }
                }, rowParams(top = dp(24), height = dp(48), width = dp(160)))
                addView(Button(this@MainActivity).apply {
                    text = "换服务器"; setTextColor(COL_TEXT); setBackgroundColor(COL_INPUT_BG)
                    setOnClickListener { hideErrorPage(); connectView?.visibility = View.VISIBLE }
                }, rowParams(top = dp(12), height = dp(48), width = dp(160)))
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
     * 用 WindowInsetsController（API 30+ 正道）—— 旧 systemUiVisibility flag
     * 在三星 One UI / Android 16 上会被忽略（实测状态栏仍占位 128px）。
     */
    private fun applyImmersive() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.decorView.post {
            val c = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
            c.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars() or
                   androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            c.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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
