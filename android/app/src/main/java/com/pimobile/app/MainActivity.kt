package com.pimobile.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
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

class MainActivity : Activity() {
    private var webView: WebView? = null
    private var connectView: View? = null
    private var errorView: View? = null
    private var lastUrl: String? = null
    private var pendingAuth: HttpAuthHandler? = null

    private companion object {
        const val COL_BG = 0xFF1A1A2E.toInt()
        const val COL_ACCENT = 0xFFE94560.toInt()
        const val COL_TITLE = 0xFF0F3460.toInt()
        const val COL_TEXT = 0xFFFFFFFF.toInt()
        const val COL_HINT = 0x80FFFFFF.toInt()
        const val COL_INPUT_BG = 0x33FFFFFF.toInt()
        const val COL_MUTED = 0xB3FFFFFF.toInt()
        const val COL_DIM = 0x80FFFFFF.toInt()
        const val UA_MARKER = "PiMobile/1.0"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("pi-mobile", Context.MODE_PRIVATE)
        val app = application as PiApp

        val root = FrameLayout(this).apply {
            setBackgroundColor(COL_BG)
        }

        val reused = app.retainedWebView != null
        webView = (app.retainedWebView ?: WebView(this).also { app.retainedWebView = it }).apply {
            (parent as? ViewGroup)?.removeView(this)
            // 幂等：只叠加一次 UA 标识，不改写原始 UA
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
            // 每次都重设 client，绑定到当前 Activity（retainedWebView 复用时旧 client 失效）
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    hideErrorPage()
                }
                override fun onReceivedError(
                    view: WebView?, request: WebResourceRequest?, error: WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        showErrorPage(error?.description?.toString() ?: "网络错误")
                    }
                }
                override fun onReceivedHttpError(
                    view: WebView?, request: WebResourceRequest?, errorResponse: android.webkit.WebResourceResponse?
                ) {
                    if (request?.isForMainFrame == true) {
                        showErrorPage("HTTP ${errorResponse?.statusCode}")
                    }
                }
                // opencode server 设了 OPENCODE_SERVER_PASSWORD 时走这里，否则 401 白屏打不开
                override fun onReceivedHttpAuthRequest(
                    view: WebView?, handler: HttpAuthHandler?, host: String?, realm: String?
                ) {
                    handler ?: return
                    showAuthDialog(handler, host)
                }
            }
        }
        root.addView(webView)

        CookieManager.getInstance().setAcceptCookie(true)

        connectView = createConnectView(prefs)
        root.addView(connectView)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        setContentView(root)

        // 自动连接：savedUrl 存在且当前 WebView 没有在加载它时才 loadUrl，
        // 否则保留 retainedWebView 的页面状态。
        val savedUrl = prefs.getString("url", null)
        val currentUrl = webView?.url
        val baseMatch = savedUrl != null && currentUrl != null &&
            currentUrl.trimEnd('/').startsWith(savedUrl.trimEnd('/'))
        if (savedUrl != null && !baseMatch) {
            connectView?.visibility = View.GONE
            lastUrl = savedUrl
            webView?.loadUrl(savedUrl)
        } else if (!currentUrl.isNullOrBlank()) {
            connectView?.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()
        webView?.onResume()
        webView?.resumeTimers()
    }

    override fun onPause() {
        super.onPause()
        webView?.onPause()
        webView?.pauseTimers()
    }

    private fun createConnectView(prefs: android.content.SharedPreferences): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COL_BG)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
        }
        val wrapper = FrameLayout(this).apply {
            setBackgroundColor(COL_BG)
            addView(column)
        }

        column.addView(TextView(this).apply {
            text = "Pi Mobile"; textSize = 28f; setTextColor(COL_TITLE)
        })
        column.addView(TextView(this).apply {
            text = "Connect to opencode web on your computer"; textSize = 14f; setTextColor(COL_MUTED)
        }, rowParams(top = dp(8)))

        column.addView(spacer(dp(32)))

        val httpBtn = RadioButton(this).apply { id = View.generateViewId(); text = "http"; setTextColor(COL_TEXT) }
        val httpsBtn = RadioButton(this).apply { id = View.generateViewId(); text = "https"; setTextColor(COL_TEXT) }
        val protocolGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(httpBtn); addView(httpsBtn)
            check(httpBtn.id)
        }
        column.addView(protocolGroup, rowParams(top = dp(8), width = dp(300)))

        val hostInput = column.addEditText("100.x.x.x or hostname.ts.net")
        val portInput = column.addEditText("4096", "4096")
        column.addView(spacer(dp(24)))

        column.addView(Button(this).apply {
            text = "Connect"; setTextColor(COL_TEXT); setBackgroundColor(COL_ACCENT)
            setOnClickListener {
                val host = hostInput.text.toString().trim()
                if (host.isBlank()) return@setOnClickListener
                val port = portInput.text.toString().trim().ifEmpty { "4096" }
                val proto = if (protocolGroup.checkedRadioButtonId == httpsBtn.id) "https" else "http"
                val url = "$proto://$host:$port"
                hideErrorPage()
                connectView?.visibility = View.GONE
                prefs.edit().putString("url", url).apply()
                lastUrl = url
                webView?.loadUrl(url)
            }
        }, rowParams(top = dp(12), height = dp(48), width = dp(300)))

        column.addView(TextView(this).apply {
            text = "Enter your opencode web server address"
            textSize = 12f; setTextColor(COL_DIM); gravity = Gravity.CENTER
        }, rowParams(top = dp(24)))
        return wrapper
    }

    // ── 认证（opencode server 的 Basic Auth）──────────────────────

    private fun showAuthDialog(handler: HttpAuthHandler, host: String?) {
        if (pendingAuth != null) { handler.cancel(); return }
        pendingAuth = handler

        val usernameInput = EditText(this).apply {
            hint = "username"; setText("opencode")
            setTextColor(COL_TEXT); setHintTextColor(COL_HINT); setBackgroundColor(COL_INPUT_BG)
        }
        val passwordInput = EditText(this).apply {
            hint = "password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(COL_TEXT); setHintTextColor(COL_HINT); setBackgroundColor(COL_INPUT_BG)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
            addView(usernameInput)
            addView(passwordInput, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) })
        }

        AlertDialog.Builder(this)
            .setTitle("${host ?: "服务器"}需要登录")
            .setView(layout)
            .setPositiveButton("登录") { _, _ ->
                pendingAuth?.proceed(usernameInput.text.toString(), passwordInput.text.toString())
                pendingAuth = null
            }
            .setNegativeButton("取消") { _, _ ->
                pendingAuth?.cancel()
                pendingAuth = null
            }
            .setOnDismissListener { pendingAuth = null }
            .show()
    }

    // ── 错误页（可重试 / 换服务器，避免白屏卡死）────────────────

    private fun showErrorPage(message: String) {
        if (errorView == null) {
            errorView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(COL_BG)
                gravity = Gravity.CENTER
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                addView(TextView(this@MainActivity).apply {
                    text = "连接失败"; textSize = 20f; setTextColor(COL_TEXT)
                    gravity = Gravity.CENTER
                })
                addView(TextView(this@MainActivity).apply {
                    text = "检查地址、端口和网络后重试"; textSize = 14f; setTextColor(COL_MUTED)
                    gravity = Gravity.CENTER
                }, rowParams(top = dp(8)))
                addView(Button(this@MainActivity).apply {
                    text = "重试"; setTextColor(COL_TEXT); setBackgroundColor(COL_ACCENT)
                    setOnClickListener {
                        hideErrorPage()
                        lastUrl?.let { webView?.loadUrl(it) }
                    }
                }, rowParams(top = dp(24), height = dp(48), width = dp(160)))
                addView(Button(this@MainActivity).apply {
                    text = "换服务器"; setTextColor(COL_TEXT); setBackgroundColor(COL_INPUT_BG)
                    setOnClickListener {
                        hideErrorPage()
                        connectView?.visibility = View.VISIBLE
                    }
                }, rowParams(top = dp(12), height = dp(48), width = dp(160)))
            }
            (webView?.parent as? ViewGroup)?.addView(errorView)
        }
        // 更新错误详情
        val msgView = (errorView as? LinearLayout)?.getChildAt(1) as? TextView
        msgView?.text = message
        errorView?.visibility = View.VISIBLE
    }

    private fun hideErrorPage() {
        errorView?.visibility = View.GONE
    }

    // ── 小工具 ──────────────────────────────────────────────────────

    private fun spacer(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, h) }

    private fun LinearLayout.addEditText(hint: String, default: String? = null): EditText =
        EditText(this.context).apply {
            this.hint = hint
            setTextColor(COL_TEXT); setHintTextColor(COL_HINT); setBackgroundColor(COL_INPUT_BG)
            default?.let { setText(it) }
        }.also { addView(it, rowParams(top = dp(12), width = dp(300))) }

    private fun rowParams(top: Int = 0, width: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
                          height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(width, height).apply { topMargin = top }

    private fun dp(n: Int) = (n * resources.displayMetrics.density + 0.5f).toInt()

    private fun applyFullscreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            connectView?.visibility == View.VISIBLE -> moveTaskToBack(true)
            webView?.canGoBack() == true -> webView?.goBack()
            else -> moveTaskToBack(true)
        }
    }
}
