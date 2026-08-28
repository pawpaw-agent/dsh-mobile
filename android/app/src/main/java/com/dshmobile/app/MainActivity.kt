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
import com.dshmobile.protocol.DshClient

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

        val hostInput = EditText(this).apply {
            hint = "192.168.1.100 / 100.x.x.x / tunnel domain"
            setTextColor(COL_TEXT); setHintTextColor(COL_DIM); setBackgroundColor(0x33FFFFFF)
        }
        column.addView(hostInput, rowParams(top = dp(12), width = dp(300), height = dp(46)))

        val portInput = EditText(this).apply {
            hint = "port"; setText(DEFAULT_PORT)
            setTextColor(COL_TEXT); setHintTextColor(COL_DIM); setBackgroundColor(0x33FFFFFF)
        }
        column.addView(portInput, rowParams(top = dp(12), width = dp(300), height = dp(46)))

        column.addView(spacer(dp(24)))

        column.addView(Button(this).apply {
            text = "Connect"; setTextColor(COL_TEXT); setBackgroundColor(COL_ACCENT)
            setOnClickListener {
                val host = hostInput.text.toString().trim()
                if (host.isBlank()) { status("host 不能为空"); return@setOnClickListener }
                val port = portInput.text.toString().trim().ifEmpty { DEFAULT_PORT }
                val proto = if (protocolGroup.checkedRadioButtonId == httpsBtn.id) "https" else "http"
                connect("$proto://$host:$port")
            }
        }, rowParams(top = dp(8), height = dp(48), width = dp(300)))

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
        dsh.onStateChange = { s -> runOnUiThread { status("状态: $s") } }
        val app = application as DshApp
        app.client = dsh
        app.clientStarted = false

        // 在后台线程做就绪握手，避免阻塞主线程
        Thread {
            val desc = dsh.hostDescribe()
            runOnUiThread {
                if (desc.isOk) {
                    prefs.edit().putString("url", baseUrl).apply()
                    app.clientStarted = true
                    status("已连接，打开会话…")
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
