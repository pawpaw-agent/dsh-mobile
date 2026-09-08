package com.dshmobile.app

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ToggleButton
import com.dshmobile.protocol.SshTunnel
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import org.json.JSONObject

/**
 * dsh-mobile SSH 终端模式（远程 shell；需要时手动输入 dsh-tui 等命令）。
 *
 * 架构（Termux 官方标准路径 + SSH 字节流桥接）：
 *  - SshTunnel.openShell() 建立 PTY shell 通道（JSch ChannelShell）
 *  - TerminalSession 附带一个无害的本地休眠进程（sleep -c），正常初始化
 *    TerminalEmulator（含 JNI pty），Termux 全部生命周期（updateSize/渲染/
 *    滚动/光标）走原生实现；
 *  - SSH 字节流绕过本地 pty：远端输出 → emulator.append()（Termux 渲染）；
 *    软键盘/键排 → TerminalViewClient.onKeyDown 捕获 → shell.write()
 *    （绝不写 session —— 那会写本地 pty 进程）。
 *
 * 许可：Termux terminal-view/emulator 为 GPL-3.0，本项目对应以 GPL-3.0 分发。
 */
class TuiActivity : Activity() {

    private var terminalView: TerminalView? = null
    private var emulator: TerminalEmulator? = null
    private var shell: SshTunnel.ShellHandle? = null
    private var tunnel: SshTunnel? = null
    private var statusView: TextView? = null

    /** 终端引擎未就绪期间收到的 SSH 输出（就绪后重放，防丢首屏提示符）。 */
    private val pendingBytes = java.io.ByteArrayOutputStream()
    @Volatile private var ctrlDown = false

    private companion object {
        const val COL_BG = 0xFF0A0A0E.toInt()
        const val COL_TEXT = 0xFFF5F5F7.toInt()
        const val COL_MUTED = 0x99FFFFFF.toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(COL_BG) }
        setContentView(root)

        statusView = TextView(this).apply {
            text = "正在连接 SSH…"
            textSize = 14f
            setTextColor(COL_MUTED)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        root.addView(statusView)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        terminalView = TerminalView(this, null).apply {
            setTextSize(11)
            setTypeface(Typeface.MONOSPACE)
            // 官方标准路径：TerminalSession 附带一个无害的本地进程
            // （/system/bin/sh -c 纯 shell 内置循环，不依赖 sleep 等外部命令），
            // 正常初始化 TerminalEmulator（含 JNI pty），Termux 的所有生命周期
            // （updateSize/渲染/滚动/光标）走原生实现。
            // 注意：JNI createSubprocess 是 execvp(cmd, argv) 且 argv 直接来自
            // args 数组 —— 因此 args 的第一个元素必须是程序名本身（argv[0]）。
            // SSH 字节流绕过本地 pty：远端输出直接喂 session.getEmulator().append()，
            // 键盘输入经 TerminalViewClient 桥到 SSH shell（绝不写 session——那会写本地 pty）。
            val session = TerminalSession(
                "/system/bin/sh", "/",
                arrayOf("/system/bin/sh", "-c", "while true; do :; done"),
                null, 5000,
                object : TerminalSessionClient {
                    override fun onTextChanged(changedSession: TerminalSession) {}
                    override fun onTitleChanged(changedSession: TerminalSession) {}
                    override fun onSessionFinished(finishedSession: TerminalSession) {}
                    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {}
                    override fun onPasteTextFromClipboard(session: TerminalSession?) {}
                    override fun onBell(session: TerminalSession) {}
                    override fun onColorsChanged(session: TerminalSession) {}
                    override fun onTerminalCursorStateChange(state: Boolean) {}
                    override fun getTerminalCursorStyle(): Int = 0
                    override fun logError(tag: String, message: String) {}
                    override fun logWarn(tag: String, message: String) {}
                    override fun logInfo(tag: String, message: String) {}
                    override fun logDebug(tag: String, message: String) {}
                    override fun logVerbose(tag: String, message: String) {}
                    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
                    override fun logStackTrace(tag: String, e: Exception) {}
                })
            mTermSession = session
            attachSession(session)
        }
        terminalView?.setTerminalViewClient(object : TerminalViewClient {
            override fun onScale(scale: Float): Float = 1f
            override fun onSingleTapUp(e: android.view.MotionEvent) {}
            override fun shouldBackButtonBeMappedToEscape(): Boolean = false
            override fun shouldEnforceCharBasedInput(): Boolean = false
            override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
            override fun isTerminalViewSelected(): Boolean = true
            override fun copyModeChanged(copyMode: Boolean) {}
            override fun onLongPress(event: android.view.MotionEvent): Boolean = false

            override fun onKeyDown(keyCode: Int, e: android.view.KeyEvent, session: TerminalSession): Boolean {
                // 键盘事件直接桥到 SSH shell（不经 TerminalSession.write——那是本地进程队列）
                val handle = shell ?: return false
                val composed = composeKey(keyCode, e, ctrlDown)
                if (composed != null) {
                    handle.write(composed)
                    return true
                }
                return false
            }
            override fun onKeyUp(keyCode: Int, e: android.view.KeyEvent): Boolean = false
            override fun readControlKey(): Boolean = ctrlDown
            override fun readAltKey(): Boolean = false
            override fun readShiftKey(): Boolean = false
            override fun readFnKey(): Boolean = false

            // v0.118.1 接口方法（未使用则空实现）
            override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean {
                val handle = shell ?: return false
                val bytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)
                handle.write(bytes)
                return true
            }
            override fun onEmulatorSet() {}
            override fun logError(tag: String, message: String) {}
            override fun logWarn(tag: String, message: String) {}
            override fun logInfo(tag: String, message: String) {}
            override fun logDebug(tag: String, message: String) {}
            override fun logVerbose(tag: String, message: String) {}
            override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
            override fun logStackTrace(tag: String, e: Exception) {}
        })
        column.addView(terminalView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        column.addView(buildKeyRow())
        root.addView(column)

        connect()
    }

    /** 把 Android KeyEvent 合成为 ANSI 终端键序列（最小集，覆盖 dsh-tui 常用键）。 */
    private fun composeKey(keyCode: Int, e: android.view.KeyEvent, ctrl: Boolean): ByteArray? {
        when (keyCode) {
            android.view.KeyEvent.KEYCODE_ENTER -> return byteArrayOf('\r'.code.toByte())
            android.view.KeyEvent.KEYCODE_TAB -> return byteArrayOf('\t'.code.toByte())
            android.view.KeyEvent.KEYCODE_ESCAPE -> return byteArrayOf(0x1b.toByte())
            android.view.KeyEvent.KEYCODE_DEL, android.view.KeyEvent.KEYCODE_FORWARD_DEL ->
                return byteArrayOf(0x7f.toByte())
            android.view.KeyEvent.KEYCODE_MOVE_HOME -> return byteArrayOf(0x01)
            android.view.KeyEvent.KEYCODE_MOVE_END -> return byteArrayOf(0x05)
        }
        // Ctrl + 字母（A-Z）：发送控制字符（Ctrl+A=0x01 … Ctrl+Z=0x1A）
        if (ctrl) {
            val letter = keyToLetter(keyCode)
            if (letter != null) {
                return byteArrayOf(((letter - 'a' + 1) and 0x1f).toByte())
            }
        }
        // 普通字符：取 KeyEvent 的字符（软键盘输入路径）
        val ch = e.unicodeChar
        if (ch != 0 && ch in 32..0x10ffff) {
            return String(Character.toChars(ch)).toByteArray(Charsets.UTF_8)
        }
        return null
    }

    /** keyCode → 字母（用于 Ctrl 组合；不含符号键）。 */
    private fun keyToLetter(keyCode: Int): Char? = when (keyCode) {
        android.view.KeyEvent.KEYCODE_A -> 'a'
        android.view.KeyEvent.KEYCODE_B -> 'b'
        android.view.KeyEvent.KEYCODE_C -> 'c'
        android.view.KeyEvent.KEYCODE_D -> 'd'
        android.view.KeyEvent.KEYCODE_E -> 'e'
        android.view.KeyEvent.KEYCODE_F -> 'f'
        android.view.KeyEvent.KEYCODE_G -> 'g'
        android.view.KeyEvent.KEYCODE_H -> 'h'
        android.view.KeyEvent.KEYCODE_I -> 'i'
        android.view.KeyEvent.KEYCODE_J -> 'j'
        android.view.KeyEvent.KEYCODE_K -> 'k'
        android.view.KeyEvent.KEYCODE_L -> 'l'
        android.view.KeyEvent.KEYCODE_M -> 'm'
        android.view.KeyEvent.KEYCODE_N -> 'n'
        android.view.KeyEvent.KEYCODE_O -> 'o'
        android.view.KeyEvent.KEYCODE_P -> 'p'
        android.view.KeyEvent.KEYCODE_Q -> 'q'
        android.view.KeyEvent.KEYCODE_R -> 'r'
        android.view.KeyEvent.KEYCODE_S -> 's'
        android.view.KeyEvent.KEYCODE_T -> 't'
        android.view.KeyEvent.KEYCODE_U -> 'u'
        android.view.KeyEvent.KEYCODE_V -> 'v'
        android.view.KeyEvent.KEYCODE_W -> 'w'
        android.view.KeyEvent.KEYCODE_X -> 'x'
        android.view.KeyEvent.KEYCODE_Y -> 'y'
        android.view.KeyEvent.KEYCODE_Z -> 'z'
        else -> null
    }

    private fun connect() {
        val prefs = getSharedPreferences("dsh-mobile", MODE_PRIVATE)
        val rawSsh = prefs.getString("ssh_json", null)
        val cfg = rawSsh?.let { runCatching { JSONObject(it) }.getOrNull() }
        val host = cfg?.optString("sshHost") ?: ""
        val user = cfg?.optString("sshUser") ?: ""
        if (cfg == null || host.isBlank() || user.isBlank()) {
            statusView?.text = "未配置 SSH，请先回连接屏填写"
            return
        }
        val auth = if (cfg.optString("authType", "password") == "key") {
            val keyPath = cfg.optString("keyPath", "")
            if (keyPath.isBlank()) { statusView?.text = "SSH 私钥路径为空"; return }
            SshTunnel.Auth.KeyPair(java.io.File(keyPath), cfg.optString("keyPass").ifEmpty { null })
        } else {
            SshTunnel.Auth.Password(cfg.optString("password", ""))
        }
        tunnel = SshTunnel(
            sshHost = host,
            sshPort = cfg.optInt("sshPort", 22),
            sshUser = user,
            remoteHost = "127.0.0.1",
            remotePort = cfg.optInt("remotePort", 3080),
            auth = auth
        )
        Thread {
            val handle = tunnel?.openShell(
                termType = "xterm-256color",
                rows = 24, cols = 80,
                onReady = { h ->
                    runOnUiThread {
                        shell = h
                        // 竞态防护：SSH onReady 可能早于 view 布局/session 初始化
                        // （getEmulator() 为 null）。挂在 view 的消息队列上，等
                        // onSizeChanged 完成后再取 emulator；若仍为 null 则轮询几轮。
                        retrySetupEmulator(h, 0)
                        statusView?.visibility = View.GONE
                        // 纯 SSH 终端：进入后是普通远程 shell，不自动启动任何应用
                        // （需要时在终端里手动输入 dsh-tui 即可）
                    }
                },
                onData = { data ->
                    runOnUiThread {
                        val emu = emulator
                        if (emu != null) emu.append(data, data.size)
                        else pendingBytes.write(data)  // 引擎未就绪时缓存
                    }
                },
                onExit = { code ->
                    runOnUiThread {
                        statusView?.text = "连接断开（exit=$code）"
                        statusView?.visibility = View.VISIBLE
                    }
                }
            )
            if (handle == null) {
                runOnUiThread { statusView?.text = "SSH 连接失败（主机/端口/认证）" }
            }
        }.start()
    }

    /**
     * 挂接渲染：拿 session 的 emulator（Termux 原生初始化），SSH 输出直接 append。
     * session 的 emulator 由 onSizeChanged→updateSize→initializeEmulator 创建，
     * 与 SSH 握手存在竞态：若尚未就绪，用 post{} 延迟重试（最多 20 轮 × 500ms）。
     */
    private fun retrySetupEmulator(handle: SshTunnel.ShellHandle, attempt: Int) {
        val v = terminalView ?: return
        val emu = v.mTermSession?.getEmulator()
        if (emu != null) {
            emulator = emu
            // 重放引擎就绪前缓存的 SSH 输出（提示符等首屏数据）
            val buffered = pendingBytes.toByteArray()
            if (buffered.isNotEmpty()) {
                emu.append(buffered, buffered.size)
                pendingBytes.reset()
            }
            v.invalidate()
            return
        }
        if (attempt >= 20) {
            statusView?.text = "终端引擎未就绪（布局未完成）"
            statusView?.visibility = View.VISIBLE
            return
        }
        v.postDelayed({ retrySetupEmulator(handle, attempt + 1) }, 500)
    }

    /** 底部常驻键排（Termux extra-keys 思路；覆盖 dsh-tui 高频键）。 */
    private fun buildKeyRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 0)
            addView(key("ESC") { shell?.write("\u001b") })
            addView(key("TAB") { shell?.write("\t") })
            addView(key("CTRL", toggle = true) { ctrlDown = it })
            addView(key("←") { shell?.write("\u001b[D") })
            addView(key("↓") { shell?.write("\u001b[B") })
            addView(key("↑") { shell?.write("\u001b[A") })
            addView(key("→") { shell?.write("\u001b[C") })
            addView(key("HOME") { shell?.write("\u0001") })
            addView(key("END") { shell?.write("\u0005") })
            // dsh-tui 的 interrupt：kitty CSI-u 键盘协议下的 Ctrl+Enter
            // （Enter=13, Ctrl=5）。注意：dsh-tui 的 supportsExtendedKeys() 依据
            // TERM_PROGRAM allowlist 检测——Termux 终端默认不在列表，会降级为
            // 无修饰键上报（README 已给兜底：Shift+Enter→Ctrl+J 换行等）。
            // 若远端 TERM_PROGRAM 配置为 kitty/iTerm2 等，此键输出即被识别。
            addView(key("CTRL⏎") { shell?.write("\u001b[13;5u") })
            addView(key("⏎", weight = 2f) { shell?.write("\r") })
        }
        return row
    }

    /**
     * 键排按钮。
     * @param toggle true=sticky 开关（如 CTRL：按下后保持，下一次普通键带 Ctrl）
     * @param onTap 普通键点击回调；sticky 键回调参数为当前选中状态
     */
    private fun key(label: String, weight: Float = 1f, toggle: Boolean = false, onTap: ((Boolean) -> Unit)? = null): View {
        val btn = ToggleButton(this).apply {
            text = label
            textSize = 11f
            isAllCaps = false
            setTextColor(COL_TEXT)
            setBackgroundColor(0x22000000)
            if (toggle) {
                // sticky：只走 checked 回调（点击即切换，不重复触发）
                setOnClickListener {
                    isChecked = !isChecked
                    onTap?.invoke(isChecked)
                }
            } else {
                // 普通键：按下即发一次（不改变 checked 视觉状态）
                setOnClickListener { onTap?.invoke(true) }
            }
        }
        val lp = LinearLayout.LayoutParams(0, 44, weight).apply { marginEnd = 3 }
        btn.layoutParams = lp
        return btn
    }

    override fun onDestroy() {
        shell?.close()
        shell = null
        try { tunnel?.close() } catch (_: Exception) {}
        tunnel = null
        super.onDestroy()
    }
}
