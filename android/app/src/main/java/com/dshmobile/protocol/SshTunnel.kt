package com.dshmobile.protocol

import android.util.Log
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.net.InetSocketAddress
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 内置 SSH 本地端口转发（合规的远程完整访问路径，官方文档认可的方案）。
 *
 * 原理：在手机本机监听 127.0.0.1:<localPort>，经 SSH 隧道转发到
 * [sshHost] 机器视角的 127.0.0.1:<remotePort]（即 dsh web）。
 * 客户端因此以 http://127.0.0.1:<localPort> 访问 —— 服务端 /api 信任栅栏
 * 看到 Host: 127.0.0.1 判定为回环 → 配置平面（settings/credentials 等）放行。
 *
 * 实现基于 JSch（com.github.mwiede:jsch）：
 *  - 使用 JCE 默认 provider，Android 的 Conscrypt 原生支持 EC/X25519
 *  - 不依赖 sshj / Android 内置精简 BouncyCastle（它没有 X25519/EC）
 *  - 不注册完整 BC，避免低内存 Android 设备 OOM
 *
 * 用法：
 * ```
 * val tunnel = SshTunnel(
 *     sshHost = "my-pc.example.com", sshPort = 22, sshUser = "xsj",
 *     remoteHost = "127.0.0.1", remotePort = 3080,
 *     auth = SshTunnel.Password("...")   // 或 SshTunnel.KeyPair(file, passphrase)
 * )
 * tunnel.start()
 * val client = DshClient("http://127.0.0.1:${tunnel.localPort}")
 * ```
 * 断线自动重连；[close] 释放。
 */
class SshTunnel(
    private val sshHost: String,
    private val sshPort: Int,
    private val sshUser: String,
    private val remoteHost: String,
    private val remotePort: Int,
    private val auth: Auth,
) : Closeable {

    sealed class Auth {
        data class Password(val password: String) : Auth()
        data class KeyPair(val privateKeyFile: File, val passphrase: String? = null) : Auth()
    }

    private val started = AtomicBoolean(false)
    private val connecting = AtomicBoolean(false)
    @Volatile private var everConnected = false
    @Volatile private var session: Session? = null
    @Volatile private var localPort: Int = 0
    private var reconnectThread: Thread? = null

    /** 隧道就绪后手机侧访问的本地基址（http://127.0.0.1:<port>）。 */
    @Volatile var localBaseUrl: String? = null
        private set

    @Volatile var onStateChange: ((String) -> Unit)? = null  // connecting / connected / reconnecting

    /** 本地端口/基址变化（SSH 断线重连后会更换端口）；WebView 需据此重新加载。 */
    @Volatile var onLocalBaseChanged: ((String) -> Unit)? = null

    /** 建立隧道并阻塞等待本地监听就绪（最多 ~10s）。成功返回本地端口。 */
    @Synchronized
    fun start(): Int {
        if (started.get()) return localPort
        started.set(true)
        connectOnce()
        // 守护重连
        reconnectThread = Thread {
            while (started.get()) {
                if (!isAlive()) {
                    onStateChange?.invoke("reconnecting")
                    if (connecting.compareAndSet(false, true)) {
                        try { connectOnce() } finally { connecting.set(false) }
                    }
                }
                try { Thread.sleep(if (isAlive()) 5_000L else 2_000L) } catch (_: InterruptedException) { return@Thread }
            }
        }.apply { isDaemon = true; name = "ssh-tunnel-watchdog"; start() }
        return localPort
    }

    private fun isAlive(): Boolean =
        session?.isConnected == true && localPort > 0 && localListening()

    private fun localListening(): Boolean = try {
        java.net.Socket().use { it.connect(InetSocketAddress("127.0.0.1", localPort), 500) }
        true
    } catch (_: Exception) { false }

    private fun connectOnce() {
        onStateChange?.invoke("connecting")
        try {
            val jsch = JSch()
            val s = buildSession(jsch)
            s.connect(10_000)
            // 0 = 让 JSch 自己分配空闲端口，避免手动占/释放端口导致的 Already bound
            val port = s.setPortForwardingL("127.0.0.1", 0, remoteHost, remotePort)
            session = s
            localPort = port
            val base = "http://127.0.0.1:$port"
            val changed = localBaseUrl != base
            val first = !everConnected
            localBaseUrl = base
            everConnected = true
            onStateChange?.invoke("connected")
            // 首次连接由调用方负责加载；仅断线重连换端口时通知 WebView 跟随新基址
            if (changed && !first) onLocalBaseChanged?.invoke(base)
        } catch (e: Exception) {
            Log.w(TAG, "ssh tunnel connect failed: ${e.message}")
            try { session?.disconnect() } catch (_: Exception) {}
            session = null
            localBaseUrl = null
            if (!everConnected) onStateChange?.invoke("failed")
            else onStateChange?.invoke("reconnecting")
        }
    }

    private fun defaultConfig(): Properties = Properties().apply {
        // 与桌面 ssh 的 first-use 行为对齐：首连接受未知主机公钥（TOFU）。
        put("StrictHostKeyChecking", "no")
        put("PreferredAuthentications", "password,publickey,keyboard-interactive")
    }

    /**
     * 打开一个 PTY shell 通道（TUI 模式用）：独立于端口转发隧道的 SSH 会话，
     * 建立后把远端 shell 的原始字节流通过 [onData] 桥接到终端渲染（如
     * Termux TerminalView 的 TerminalEmulator）。
     *
     * 注意：本方法持有的 SSH 会话与 [start] 的隧道会话相互独立——
     * [close] 只关闭转发隧道；shell 会话由调用方通过返回的 [ShellHandle] 管理。
     *
     * @param termType 远端 $TERM（默认 xterm-256color）
     * @param rows/cols 初始 PTY 尺寸（Termux TerminalView 会在 resize 时更新）
     * @param onReady 通道打开并完成 PTY 协商后回调（可在此处写启动命令）
     * @param onData 远端输出（含回显、ANSI 序列）——须在 UI 线程消费
     * @param onExit 通道关闭/异常（退出码或 -1）
     */
    fun openShell(
        termType: String = "xterm-256color",
        rows: Int = 24,
        cols: Int = 80,
        onReady: (ShellHandle) -> Unit,
        onData: (ByteArray) -> Unit,
        onExit: (Int) -> Unit,
    ): ShellHandle? {
        return try {
            val jsch = JSch()
            val s = buildSession(jsch)
            s.connect(10_000)
            val channel = s.openChannel("shell") as com.jcraft.jsch.ChannelShell
            channel.setPtyType(termType)
            channel.setPtySize(cols, rows, 0, 0)
            // 先连接再启动输出泵，避免读到未连接通道的流而误报退出
            channel.connect(10_000)
            // 输出流逐块转发（Termux terminal-emulator 的 write() 消费）
            val pump = Thread {
                try {
                    val input = channel.inputStream
                    val buf = ByteArray(16384)
                    while (!channel.isClosed && !channel.isEOF) {
                        val n = try { input.read(buf) } catch (_: Exception) { -1 }
                        if (n <= 0) break
                        onData(buf.copyOf(n))
                    }
                } finally {
                    onExit(channel.exitStatus)
                }
            }.apply { isDaemon = true; name = "dsh-shell-pump"; start() }
            val handle = ShellHandle(channel, s, ::sendShellBytes)
            onReady(handle)
            handle
        } catch (e: Exception) {
            Log.w(TAG, "open shell failed: ${e.message}")
            onExit(-1)
            null
        }
    }

    /** 向已打开的 shell 通道写原始字节（等价终端输入流）。 */
    private fun sendShellBytes(channel: com.jcraft.jsch.ChannelShell, data: ByteArray) {
        try { channel.outputStream.write(data); channel.outputStream.flush() } catch (_: Exception) {}
    }

    /** 根据认证方式构造（但不连接）JSch Session（与 connectOnce 一致的认证逻辑）。 */
    private fun buildSession(jsch: JSch): com.jcraft.jsch.Session {
        val s = when (auth) {
            is Auth.Password -> {
                val sess = jsch.getSession(sshUser, sshHost, sshPort)
                sess.setPassword(auth.password)
                sess.setConfig(defaultConfig())
                sess
            }
            is Auth.KeyPair -> {
                val keyPath = auth.privateKeyFile.absolutePath
                if (auth.passphrase.isNullOrEmpty()) jsch.addIdentity(keyPath)
                else jsch.addIdentity(keyPath, auth.passphrase.toByteArray())
                val sess = jsch.getSession(sshUser, sshHost, sshPort)
                sess.setConfig(defaultConfig())
                sess
            }
        }
        return s
    }

    /**
     * 在远端执行一条命令并收集 stdout（一次性，独立会话，跑完即断）。
     *
     * 用于自动获取 dsh web 的浏览器认证 token（SSH 场景下服务端有
     * journald 日志，App 无需用户手输）。超时安全：命令超过 [timeoutMs]
     * 未结束（或读到 EOF）即返回已收集内容；失败返回 null。
     *
     * @return 命令 stdout（已 trim）；失败/空输出返回 null
     */
    fun execOnce(cmd: String, timeoutMs: Long = 8_000): String? {
        return try {
            val jsch = JSch()
            val s = buildSession(jsch)
            s.connect(10_000)
            try {
                val channel = s.openChannel("exec") as com.jcraft.jsch.ChannelExec
                channel.setCommand(cmd)
                channel.connect(10_000)
                val input = channel.inputStream
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                val deadline = System.currentTimeMillis() + timeoutMs
                var closed = false
                while (!closed && System.currentTimeMillis() < deadline) {
                    val n = try { input.read(buf) } catch (_: Exception) { -1 }
                    if (n < 0) { closed = true; break }
                    if (n > 0) out.write(buf, 0, n)
                    // EOF 提前退出；JSch 无阻塞 API，短 sleep 让出 CPU
                    if (channel.isClosed) { closed = true; break }
                    try { Thread.sleep(50) } catch (_: InterruptedException) { break }
                }
                try { channel.disconnect() } catch (_: Exception) {}
                out.toString(Charsets.UTF_8.name()).trim().takeIf { it.isNotEmpty() }
            } finally {
                try { s.disconnect() } catch (_: Exception) {}
            }
        } catch (_: Exception) { null }
    }

    /**
     * shell 通道句柄：暴露写输入、改 PTY 尺寸、关闭通道等 TUI 需要的能力。
     * 关闭时同时释放其专享的 SSH 会话（不碰转发隧道）。
     */
    class ShellHandle(
        private val channel: com.jcraft.jsch.ChannelShell,
        private val session: com.jcraft.jsch.Session,
        private val writer: (com.jcraft.jsch.ChannelShell, ByteArray) -> Unit,
    ) : Closeable {
        /** 远端 shell 是否有输出可写（写前检查）。 */
        val isOpen: Boolean get() = channel.isConnected && session.isConnected

        /** 写入输入字节（软键盘/键排合成的事件序列）。 */
        fun write(data: ByteArray) = writer(channel, data)

        fun write(text: String) = writer(channel, text.toByteArray(Charsets.UTF_8))

        /** 通知远端终端尺寸变化（TUI 界面随软键盘弹起/收起自适应）。 */
        fun setSize(rows: Int, cols: Int) {
            try { channel.setPtySize(cols, rows, 0, 0) } catch (_: Exception) {}
        }

        override fun close() {
            try { channel.disconnect() } catch (_: Exception) {}
            try { session.disconnect() } catch (_: Exception) {}
        }
    }


    override fun close() {
        started.set(false)
        reconnectThread?.interrupt()
        try { session?.disconnect() } catch (_: Exception) {}
        session = null
        localBaseUrl = null
    }

    companion object {
        private const val TAG = "SshTunnel"

        /** 从连接屏 JSON 配置构造（host/port/user/auth 持久化在 SharedPreferences）。 */
        fun fromJson(o: JSONObject): SshTunnel {
            val authType = o.optString("authType", "password")
            val auth = if (authType == "key") {
                Auth.KeyPair(File(o.getString("keyPath")), o.optString("keyPass").ifEmpty { null })
            } else Auth.Password(o.getString("password"))
            return SshTunnel(
                sshHost = o.getString("sshHost"),
                sshPort = o.optInt("sshPort", 22),
                sshUser = o.getString("sshUser"),
                remoteHost = o.optString("remoteHost", "127.0.0.1"),
                remotePort = o.optInt("remotePort", 3080),
                auth = auth
            )
        }
    }
}
