package com.dshmobile.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import org.json.JSONObject
import com.dshmobile.protocol.DshClient
import com.dshmobile.protocol.SshTunnel

/**
 * dsh-mobile 原生客户端（不用 WebView）：连接屏。
 *
 * 输入 host:port + 协议，创建并启动 [DshClient]（HTTP POST /api + 两个 WebSocket downlink），
 * 做一次 host.describe 就绪握手；成功后进入 [ConversationActivity]。
 * 远程访问注意：服务端配置平面（settings/credentials 等）仅回环可写；远程合规走 SSH 端口转发
 * 或授权 trustedHosts（见 docs/dsh-protocol.md §2.5）。
 */
class MainActivity : Activity() {
    private lateinit var prefs: android.content.SharedPreferences
    private var statusView: TextView? = null

    private companion object {
        const val COL_BG = 0xFF1A1A2E.toInt()
        const val COL_ACCENT = 0xFFE94560.toInt()
        const val COL_TITLE = 0xFF0F3460.toInt()
        const val COL_TEXT = 0xFFFFFFFF.toInt()
        const val COL_MUTED = 0xB3FFFFFF.toInt()
        const val COL_DIM = 0x80FFFFFF.toInt()
        const val DEFAULT_PORT = "3080"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("dsh-mobile", Context.MODE_PRIVATE)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COL_BG)
            gravity = Gravity.CENTER
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(COL_BG)
            addView(column)
        }

        column.addView(TextView(this).apply {
            text = "DSH Mobile"; textSize = 28f; setTextColor(COL_TITLE)
        })
        column.addView(TextView(this).apply {
            text = "Connect to DeepSeek Harness on your computer"; textSize = 14f; setTextColor(COL_MUTED)
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

        // 预填上次连接的地址（自动重连体验）
        val saved = prefs.getString("url", null)
        var prefillProto = "http"; var prefillHost = ""; var prefillPort = DEFAULT_PORT
        if (saved != null) {
            val m = Regex("^(https?)://([^:]+)(?::(\\d+))?$").find(saved.trim())
            if (m != null) {
                prefillProto = m.groupValues[1]; prefillHost = m.groupValues[2]
                if (m.groupValues[3].isNotEmpty()) prefillPort = m.groupValues[3]
            }
        }
        protocolGroup.check(if (prefillProto == "https") httpsBtn.id else httpBtn.id)

        val hostInput = EditText(this).apply {
            hint = "192.168.1.100 / 100.x.x.x / tunnel domain"
            setText(prefillHost)
            setTextColor(COL_TEXT); setHintTextColor(COL_DIM); setBackgroundColor(0x33FFFFFF)
        }
        column.addView(hostInput, rowParams(top = dp(12), width = dp(300), height = dp(46)))

        val portInput = EditText(this).apply {
            hint = "port"; setText(prefillPort)
            setTextColor(COL_TEXT); setHintTextColor(COL_DIM); setBackgroundColor(0x33FFFFFF)
        }
        column.addView(portInput, rowParams(top = dp(12), width = dp(300), height = dp(46)))

        // ── SSH 隧道模式：经 SSH 本地端口转发访问，服务端视角为回环 → 配置平面全解锁 ──
        val sshToggle = android.widget.CheckBox(this).apply {
            text = "SSH 隧道（解锁设置/凭据等本机限制接口）"
            setTextColor(COL_MUTED)
        }
        column.addView(sshToggle, rowParams(top = dp(8), width = dp(300)))

        val savedSsh = prefs.getString("ssh_json", null)?.let {
            try { JSONObject(it) } catch (_: Exception) { null }
        }
        fun sshField(hint: String, key: String, pwd: Boolean = false): EditText = EditText(this).apply {
            this.hint = hint
            setTextColor(COL_TEXT); setHintTextColor(COL_DIM); setBackgroundColor(0x33FFFFFF)
            if (pwd) transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
            savedSsh?.optString(key)?.let { if (it.isNotEmpty()) setText(it) }
        }
        val sshHostInput = sshField("SSH 主机（电脑 IP / 域名）", "sshHost")
        val sshPortInput = sshField("SSH 端口", "sshPort").apply { setText(savedSsh?.optString("sshPort", "22") ?: "22") }
        val sshUserInput = sshField("SSH 用户名", "sshUser")
        val sshPassInput = sshField("SSH 密码 / 私钥口令", "password", pwd = true)
        val sshFields = listOf(sshHostInput, sshPortInput, sshUserInput, sshPassInput)
        sshFields.forEach {
            it.visibility = View.GONE
            column.addView(it, rowParams(top = dp(8), width = dp(300), height = dp(44)))
        }
        sshToggle.setOnCheckedChangeListener { _, checked ->
            sshFields.forEach { it.visibility = if (checked) View.VISIBLE else View.GONE }
        }

        column.addView(spacer(dp(24)))

        column.addView(Button(this).apply {
            text = "Connect"; setTextColor(COL_TEXT); setBackgroundColor(COL_ACCENT)
            setOnClickListener {
                if (sshToggle.isChecked) {
                    val sh = sshHostInput.text.toString().trim()
                    val su = sshUserInput.text.toString().trim()
                    val sp = sshPassInput.text.toString()
                    val sport = sshPortInput.text.toString().trim().toIntOrNull() ?: 22
                    val rport = portInput.text.toString().trim().toIntOrNull() ?: 3080
                    if (sh.isBlank() || su.isBlank()) { status("SSH 主机/用户名不能为空"); return@setOnClickListener }
                    connectViaSsh(sh, sport, su, rport, sp)
                } else {
                    val host = hostInput.text.toString().trim()
                    if (host.isBlank()) { status("host 不能为空"); return@setOnClickListener }
                    val port = portInput.text.toString().trim().ifEmpty { DEFAULT_PORT }
                    val proto = if (protocolGroup.checkedRadioButtonId == httpsBtn.id) "https" else "http"
                    connect("$proto://$host:$port")
                }
            }
        }, rowParams(top = dp(8), height = dp(48), width = dp(300)))

        // 上次连接过：提供一键直连
        if (saved != null && prefillHost.isNotEmpty()) {
            column.addView(Button(this).apply {
                text = "连接上次: $prefillHost:$prefillPort"
                setTextColor(COL_TEXT); setBackgroundColor(0x33FFFFFF)
                setOnClickListener { connect(saved.trim()) }
            }, rowParams(top = dp(8), height = dp(44), width = dp(300)))
        }

        statusView = TextView(this).apply {
            textSize = 13f; setTextColor(COL_MUTED); gravity = Gravity.CENTER
        }
        column.addView(statusView, rowParams(top = dp(16)))

        column.addView(TextView(this).apply {
            text = "原生客户端 · 内置 /api RPC + WebSocket 事件流"
            textSize = 11f; setTextColor(COL_DIM); gravity = Gravity.CENTER
        }, rowParams(top = dp(24)))

        setContentView(root)
    }

    private fun connect(baseUrl: String) {
        status("连接中… ($baseUrl)")
        val dsh = DshClient(baseUrl)
        handshakeAndEnter(dsh, baseUrl)
    }

    /** SSH 隧道模式：先建转发，再对手机本机回环地址握手（服务端视为本机访问）。 */
    private fun connectViaSsh(sshHost: String, sshPort: Int, sshUser: String, remotePort: Int, password: String) {
        status("SSH 隧道建立中… ($sshUser@$sshHost)")
        val app = application as DshApp
        Thread {
            val tunnel = SshTunnel(
                sshHost = sshHost, sshPort = sshPort, sshUser = sshUser,
                remoteHost = "127.0.0.1", remotePort = remotePort,
                auth = SshTunnel.Auth.Password(password)
            )
            tunnel.onStateChange = { s -> runOnUiThread { status("隧道: $s") } }
            tunnel.start()
            val base = tunnel.localBaseUrl
            runOnUiThread {
                if (base == null) {
                    tunnel.close()
                    status("隧道建立失败（检查 SSH 主机/端口/用户/密码）")
                    return@runOnUiThread
                }
                status("隧道就绪 → $base")
                persistSshConfig(sshHost, sshPort, sshUser, remotePort, password)
                val dsh = DshClient(base)
                app.sshTunnel = tunnel
                handshakeAndEnter(dsh, base, saveUrl = null)
            }
        }.start()
    }

    /** 持久化 SSH 连接参数（连接成功后调用）。 */
    private fun persistSshConfig(sshHost: String, sshPort: Int, sshUser: String, remotePort: Int, password: String) {
        try {
            val cfg = JSONObject()
                .put("sshHost", sshHost)
                .put("sshPort", sshPort)
                .put("sshUser", sshUser)
                .put("remoteHost", "127.0.0.1")
                .put("remotePort", remotePort)
                .put("authType", "password")
                .put("password", password)
            prefs.edit().putString("ssh_json", cfg.toString()).apply()
        } catch (_: Exception) {}
    }

    private fun handshakeAndEnter(dsh: DshClient, baseUrl: String, saveUrl: String? = baseUrl) {
        dsh.onStateChange = { s -> runOnUiThread { status("状态: $s") } }
        val app = application as DshApp
        app.client = dsh
        app.clientStarted = false

        // 在后台线程做就绪握手，避免阻塞主线程
        Thread {
            val desc = dsh.hostDescribe()
            runOnUiThread {
                if (desc.isOk) {
                    saveUrl?.let { prefs.edit().putString("url", it).apply() }
                    app.clientStarted = true
                    status("已连接，打开会话…")
                    // 启动事件流（两个 downlink WebSocket + 断线重连）
                    Thread { dsh.start() }.start()
                    val i = Intent(this, ConversationActivity::class.java)
                    startActivity(i)
                } else {
                    val msg = (desc as? com.dshmobile.protocol.Rpc.Result.Err)?.error?.display ?: "连接失败"
                    status("$msg（检查 host:port / 服务端是否在运行）")
                }
            }
        }.start()
    }

    private fun status(msg: String) { statusView?.text = msg }

    private fun spacer(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, h) }

    private fun rowParams(top: Int = 0, width: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
                          height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(width, height).apply { topMargin = top }

    private fun dp(n: Int) = (n * resources.displayMetrics.density + 0.5f).toInt()
}
