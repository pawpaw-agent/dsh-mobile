package com.dshmobile.app

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ToggleButton
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import org.json.JSONObject
import java.io.File

/**
 * dsh-mobile SSH 终端模式 —— Dropbear dbclient + Termux 原生渲染。
 *
 * 架构（B 方案，桥接清零）：
 *  - TerminalSession 直接启动 `libdbclient.so`（我们 CI 从官方 mkj/dropbear
 *    源码编译的 dropbear 客户端）：`dbclient -i <key> -y -tt user@host`
 *  - JNI createSubprocess 为它分配本地 pty；dbclient 经 sshd 拿到远端 pty；
 *    键盘/渲染/滚动/光标全部走 Termux 标准路径 —— 无任何手动桥。
 *  - 注意：argv 数组第一个元素必须是程序名（execvp(cmd, argv) 的约定）。
 *
 * 认证：dropbear 客户端密码认证依赖 getpass()（Android 无），官方 CI 采用
 * pubkey-only（DROPBEAR_CLI_PASSWORD_AUTH 0）。因此本模式使用私钥：
 *  - 连接屏已导入的私钥（ssh_json authType=key）直接使用；
 *  - 否则本 Activity 用 dropbearkey 生成一对（app 私有目录），启动一次后
 *    把公钥加到服务端 ~/.ssh/authorized_keys（日志/提示给出命令）。
 *
 * 许可：Termux terminal-view/emulator 与 Dropbear 均为 permissive/GPL-3.0，
 * 本项目对应以 GPL-3.0 分发。
 */
class TuiActivity : Activity() {

    private var terminalView: TerminalView? = null
    private var session: TerminalSession? = null
    private var statusView: TextView? = null

    @Volatile private var ctrlDown = false

    private companion object {
        const val TAG = "TuiActivity"
        const val COL_BG = 0xFF0A0A0E.toInt()
        const val COL_TEXT = 0xFFF5F5F7.toInt()
        const val COL_MUTED = 0x99FFFFFF.toInt()
        const val KEY_NAME = "id_dropbear"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(COL_BG) }
        setContentView(root)

        statusView = TextView(this).apply {
            text = "准备 SSH…"
            textSize = 14f
            setTextColor(COL_MUTED)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        terminalView = TerminalView(this, null).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            defaultFocusHighlightEnabled = false
            // 字号：Termux setTextSize 直接用 px（其 KDoc 误导）；
            // 按密度换算，默认 15dp，A+/A- 可调（prefs tui_font_size_px）。
            val density = resources.displayMetrics.density
            val defaultPx = (15 * density).toInt()
            val savedPx = getSharedPreferences("dsh-mobile", MODE_PRIVATE)
                .getInt("tui_font_size_px", defaultPx)
            val finalPx = savedPx.coerceIn((12 * density).toInt(), (36 * density).toInt())
            Log.i(TAG, "init font: density=$density default=${defaultPx}px saved=${savedPx}px final=${finalPx}px")
            setTextSize(finalPx)
            setTypeface(Typeface.MONOSPACE)
        }
        terminalView?.setTerminalViewClient(object : TerminalViewClient {
            override fun onScale(scale: Float): Float = 1f
            override fun onSingleTapUp(e: android.view.MotionEvent) {
                terminalView?.requestFocus()
            }
            override fun shouldBackButtonBeMappedToEscape(): Boolean = false
            // 字符级输入（Termux 默认 true；三星/Gboard 兼容）
            override fun shouldEnforceCharBasedInput(): Boolean = true
            override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
            override fun isTerminalViewSelected(): Boolean = true
            override fun copyModeChanged(copyMode: Boolean) {}
            override fun onLongPress(event: android.view.MotionEvent): Boolean = false

            override fun onKeyDown(keyCode: Int, e: android.view.KeyEvent, session: TerminalSession): Boolean = false
            override fun onKeyUp(keyCode: Int, e: android.view.KeyEvent): Boolean = false
            override fun readControlKey(): Boolean = ctrlDown
            override fun readAltKey(): Boolean = false
            override fun readShiftKey(): Boolean = false
            override fun readFnKey(): Boolean = false

            override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
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

        // 状态提示放在最上层（覆盖终端区域），失败信息（如 dbclient 缺失）可见
        root.addView(statusView)

        startDbclient()
    }

    /**
     * 构造 dbclient 参数并让 TerminalSession 启动它。
     *  - dbclient 二进制：nativeLibraryDir（APK 内 libdbclient.so，extractNativeLibs
     *    时系统会解压到该目录且可执行）。
     *  - argv[0] = 程序名（JNI execvp 约定）。
     */
    private fun startDbclient() {
        val libDir = applicationInfo.nativeLibraryDir
        val dbclient = File(libDir, "libdbclient.so")
        if (!dbclient.exists()) {
            statusView?.text = "dbclient 缺失（APK 未包含？）\n$libDir"
            return
        }

        val prefs = getSharedPreferences("dsh-mobile", MODE_PRIVATE)
        val rawSsh = prefs.getString("ssh_json", null)
        val cfg = rawSsh?.let { runCatching { JSONObject(it) }.getOrNull() }
        val host = cfg?.optString("sshHost") ?: ""
        val user = cfg?.optString("sshUser") ?: ""
        val port = cfg?.optInt("sshPort", 22) ?: 22
        val password = cfg?.optString("password", "")
        val authType = cfg?.optString("authType", "password") ?: "password"
        if (cfg == null || host.isBlank() || user.isBlank()) {
            statusView?.text = "未配置 SSH，请先回连接屏填写"
            return
        }

        // 认证方式：
        //  - password（默认）：DROPBEAR_PASSWORD 环境变量（补丁后 getpass 不再需要，
        //    与 Termux 原版 ssh 体验一致）。
        //  - key：-i 导入的私钥；缺私钥时尝试自动生成（dropbearkey）。
        val env: Array<String>?
        val keyArgs: Array<String>
        if (authType == "key") {
            val keyPath = resolveKeyPath() ?: run {
                statusView?.text = "SSH 密钥不可用，请回连接屏导入私钥"
                return
            }
            env = null
            keyArgs = arrayOf("-i", keyPath)
        } else {
            env = arrayOf("DROPBEAR_PASSWORD=$password")
            keyArgs = arrayOf()
        }

        val args = arrayOf(
            dbclient.absolutePath,
            "-p", port.toString(),
            "-y",                 // 首连接受未知主机公钥（TOFU，与现有行为一致）
            "-t",                 // 分配 pty（交互终端）
            *keyArgs,
            "$user@$host"
        )
        Log.i(TAG, "dbclient: args=${args.toList()} env=${env?.map { it.take(8) + "…" } ?: "key"}")

        statusView?.text = "连接 $user@$host:$port …"
        val client = object : TerminalSessionClient {
            override fun onTextChanged(changedSession: TerminalSession) {}
            override fun onTitleChanged(changedSession: TerminalSession) {}
            override fun onSessionFinished(finishedSession: TerminalSession) {
                runOnUiThread { statusView?.text = "SSH 会话结束"; statusView?.visibility = View.VISIBLE }
            }
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
        }
        val s = TerminalSession(
            dbclient.absolutePath, "/", args, env, 5000, client
        )
        session = s
        terminalView?.attachSession(s)
        statusView?.visibility = View.GONE
        Log.i(TAG, "dbclient started via TerminalSession")
    }

    /** 返回可用的私钥路径；若无导入密钥则用 dropbearkey 生成（并提示公钥）。 */
    private fun resolveKeyPath(): String? {
        val libDir = applicationInfo.nativeLibraryDir
        // 1) 连接屏导入的私钥
        val imported = getSharedPreferences("dsh-mobile", MODE_PRIVATE)
            .getString("ssh_json", null)?.let {
                runCatching { JSONObject(it) }.getOrNull()
            }?.optString("keyPath", "")
        if (!imported.isNullOrBlank() && File(imported).exists()) return imported

        // 2) 自动生成一对（app 私有目录），公钥一行提示加到服务端
        val dir = filesDir
        val key = File(dir, KEY_NAME)
        if (!key.exists()) {
            val dbkey = File(libDir, "libdropbearkey.so")
            if (!dbkey.exists()) return null
            val gen = ProcessBuilder(dbkey.absolutePath, "-t", "ed25519", "-f", key.absolutePath)
                .redirectErrorStream(true).start()
            gen.waitFor()
            if (!key.exists()) { Log.e(TAG, "dropbearkey failed"); return null }
            Log.i(TAG, "generated key at ${key.absolutePath}")
        }
        // 输出公钥到日志（方便用户加到服务端 authorized_keys）
        val dbkey = File(libDir, "libdropbearkey.so")
        if (dbkey.exists()) {
            try {
                val pub = ProcessBuilder(dbkey.absolutePath, "-y", "-f", key.absolutePath)
                    .redirectErrorStream(true).start()
                val text = pub.inputStream.bufferedReader().readText().trim()
                Log.i(TAG, "PUBKEY (add to server ~/.ssh/authorized_keys): $text")
            } catch (_: Exception) {}
        }
        return key.absolutePath
    }

    /** 底部常驻键排（Termux extra-keys 思路；覆盖 shell 高频键）。 */
    private fun buildKeyRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 0)
            addView(key("ESC") { send("\u001b") })
            addView(key("TAB") { send("\t") })
            addView(key("CTRL", toggle = true) { ctrlDown = it })
            addView(key("←") { send("\u001b[D") })
            addView(key("↓") { send("\u001b[B") })
            addView(key("↑") { send("\u001b[A") })
            addView(key("→") { send("\u001b[C") })
            addView(key("HOME") { send("\u0001") })
            addView(key("END") { send("\u0005") })
            addView(key("⏎", weight = 2f) { send("\r") })
            addView(key("A+") { adjustFont(2) })
            addView(key("A-") { adjustFont(-2) })
        }
        return row
    }

    /** 经 Termux 标准路径发送（session.write 写 pty → dbclient → sshd）。 */
    private fun send(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        session?.write(bytes, 0, bytes.size)
    }

    private fun key(label: String, weight: Float = 1f, toggle: Boolean = false, onTap: ((Boolean) -> Unit)? = null): View {
        val btn = ToggleButton(this).apply {
            text = label
            textSize = 11f
            isAllCaps = false
            setTextColor(COL_TEXT)
            setBackgroundColor(0x22000000)
            if (toggle) {
                setOnClickListener { isChecked = !isChecked; onTap?.invoke(isChecked) }
            } else {
                setOnClickListener { onTap?.invoke(true) }
            }
        }
        val lp = LinearLayout.LayoutParams(0, 44, weight).apply { marginEnd = 3 }
        btn.layoutParams = lp
        return btn
    }

    /** 调整终端字号（±2dp 换算 px）并持久化；范围 12–36dp。 */
    private fun adjustFont(deltaDp: Int) {
        val v = terminalView ?: return
        val density = resources.displayMetrics.density
        val defaultPx = (15 * density).toInt()
        val cur = getSharedPreferences("dsh-mobile", MODE_PRIVATE)
            .getInt("tui_font_size_px", defaultPx)
        val next = (cur + (deltaDp * density).toInt()).coerceIn(
            (12 * density).toInt(), (36 * density).toInt()
        )
        getSharedPreferences("dsh-mobile", MODE_PRIVATE)
            .edit().putInt("tui_font_size_px", next).apply()
        v.setTextSize(next)
    }

    override fun onDestroy() {
        session?.let { runCatching { it.finishIfRunning() } }
        session = null
        super.onDestroy()
    }
}
