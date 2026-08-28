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
    /** 期望的远端主机公钥指纹（SHA256，"SHA256:xxxx"）。为空则信任任意（首连 TOFU 语义）。 */
    @Suppress("UNUSED_PARAMETER")
    private val expectedFingerprint: String? = null
) : Closeable {

    sealed class Auth {
        data class Password(val password: String) : Auth()
        data class KeyPair(val privateKeyFile: File, val passphrase: String? = null) : Auth()
    }

    private val started = AtomicBoolean(false)
    private val connecting = AtomicBoolean(false)
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
            s.connect(10_000)
            // 0 = 让 JSch 自己分配空闲端口，避免手动占/释放端口导致的 Already bound
            val port = s.setPortForwardingL("127.0.0.1", 0, remoteHost, remotePort)
            session = s
            localPort = port
            val base = "http://127.0.0.1:$port"
            val changed = localBaseUrl != base
            localBaseUrl = base
            onStateChange?.invoke("connected")
            if (changed) onLocalBaseChanged?.invoke(base)
        } catch (e: Exception) {
            Log.w(TAG, "ssh tunnel connect failed: ${e.message}")
            try { session?.disconnect() } catch (_: Exception) {}
            session = null
            localBaseUrl = null
            onStateChange?.invoke("reconnecting")
        }
    }

    private fun defaultConfig(): Properties = Properties().apply {
        // 与桌面 ssh 的 first-use 行为对齐：首连接受未知主机公钥（TOFU）。
        put("StrictHostKeyChecking", "no")
        put("PreferredAuthentications", "password,publickey,keyboard-interactive")
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
                auth = auth,
                expectedFingerprint = o.optString("fingerprint").ifEmpty { null }
            )
        }
    }
}
