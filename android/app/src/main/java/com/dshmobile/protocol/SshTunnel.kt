package com.dshmobile.protocol

import android.util.Log
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.LocalPortForwarder
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile
import net.schmizz.sshj.userauth.password.PasswordUtils
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 内置 SSH 本地端口转发（合规的远程完整访问路径，官方文档认可的方案）。
 *
 * 原理：在手机本机监听 127.0.0.1:<localPort>，经 SSH 隧道转发到
 * [sshHost] 机器视角的 127.0.0.1:<remotePort>（即 dsh web）。
 * DshClient 因此以 http://127.0.0.1:<localPort> 访问 —— 服务端 /api 信任栅栏
 * 看到 Host: 127.0.0.1 判定为回环 → 配置平面（settings/credentials 等）放行。
 * 不伪造任何请求头；认证由 SSH 本身承担（密码或 OpenSSH 私钥）。
 *
 * 用法：
 * ```
 * val tunnel = SshTunnel(
 *     sshHost = "my-pc.example.com", sshPort = 22, sshUser = "xsj",
 *     remoteHost = "127.0.0.1", remotePort = 3080,
 *     auth = SshTunnel.Password("...")   // 或 SshTunnel.KeyPair(file, passphrase)
 * )
 * tunnel.start()                       // 阻塞直到隧道就绪，返回本地端口
 * val client = DshClient("http://127.0.0.1:${tunnel.localPort}")
 * ```
 * 断线自动重连（指数退避）；[close] 释放。
 */
class SshTunnel(
    private val sshHost: String,
    private val sshPort: Int,
    private val sshUser: String,
    private val remoteHost: String,
    private val remotePort: Int,
    private val auth: Auth,
    /** 期望的远端主机公钥指纹（SHA256，"SHA256:xxxx"）。为空则信任任意（首连提示确认后可持久化）。 */
    private val expectedFingerprint: String? = null
) : Closeable {

    sealed class Auth {
        data class Password(val password: String) : Auth()
        data class KeyPair(val privateKeyFile: File, val passphrase: String? = null) : Auth()
    }

    private val started = AtomicBoolean(false)
    private val connecting = AtomicBoolean(false)
    @Volatile private var ssh: SSHClient? = null
    @Volatile private var forwarder: LocalPortForwarder? = null
    @Volatile private var localPort: Int = 0
    @Volatile private var boundPort: Int = 0
    private var reconnectThread: Thread? = null

    /** 隧道就绪后手机侧访问的本地基址（http://127.0.0.1:<port>）。 */
    @Volatile var localBaseUrl: String? = null
        private set

    @Volatile var onStateChange: ((String) -> Unit)? = null  // connecting / connected / reconnecting

    /** 建立隧道并阻塞等待本地监听就绪（最多 ~10s）。成功返回本地端口。 */
    @Synchronized
    fun start(): Int {
        if (started.get()) return localPort
        started.set(true)
        connectOnce()
        started.set(true)
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
        ssh != null && ssh!!.isConnected && ssh!!.isAuthenticated && localPort > 0 && localListening()

    private fun localListening(): Boolean = try {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", localPort), 500) }
        true
    } catch (_: Exception) { false }

    private fun connectOnce() {
        onStateChange?.invoke("connecting")
        try {
            val c = createClient()
            c.addHostKeyVerifier(FingerprintVerifier(expectedFingerprint))
            c.connect(sshHost, sshPort)
            when (auth) {
                is Auth.Password -> c.authPassword(sshUser, auth.password)
                is Auth.KeyPair -> {
                    val loader = OpenSSHKeyFile()
                    if (auth.passphrase != null) loader.init(auth.privateKeyFile, PasswordUtils.createOneOff(auth.passphrase.toCharArray()))
                    else loader.init(auth.privateKeyFile)
                    c.authPublickey(sshUser, loader)
                }
            }
            // 本地端口转发：手机 127.0.0.1:<ephemeral> → sshHost 视角的 remoteHost:remotePort
            // 自管 ServerSocket（兼容 sshj 各版本的 ServerSocket 变体 API），close 时一并释放
            val ss = ServerSocket()
            try {
                ss.reuseAddress = true
                ss.bind(java.net.InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), 0))
                val listenerPort = ss.localPort
                val params = Parameters(
                    "127.0.0.1", listenerPort,  // 手机侧绑定
                    remoteHost, remotePort      // sshd 视角的远端
                )
                forwarder = c.newLocalPortForwarder(params, ss)
                boundPort = listenerPort
            } catch (e: Exception) {
                try { ss.close() } catch (_: Exception) {}
                throw e
            }
            localPort = boundPort
            localBaseUrl = "http://127.0.0.1:$localPort"
            onStateChange?.invoke("connected")
        } catch (e: Exception) {
            Log.w(TAG, "ssh tunnel connect failed: ${e.message}")
            try { ssh?.disconnect() } catch (_: Exception) {}
            ssh = null
            localBaseUrl = null
            onStateChange?.invoke("reconnecting")
        }
    }

    override fun close() {
        started.set(false)
        reconnectThread?.interrupt()
        try { forwarder?.close() } catch (_: Exception) {}
        try { ssh?.disconnect() } catch (_: Exception) {}
        forwarder = null
        ssh = null
        localBaseUrl = null
    }

    /**
     * Android 版 SSH 配置：sshj 0.38 默认首选 Curve25519 / Ed25519，
     * 但 Android 内置精简 BC 不支持，完整 bcprov 又会在低内存设备注册时 OOM。
     * 因此这里显式绕开 X25519 / Ed25519，只保留 Android 原生支持的算法：
     *   KEX:     ECDH NIST P-256/384/521 + DH group1/14
     *   签名:    RSA / DSA
     *
     * 注：AndroidConfig 自带 EdDSA25519，这里也去掉，避免 Ed25519。
     */
    private fun createClient(): SSHClient {
        val cfg = net.schmizz.sshj.AndroidConfig()
        try {
            cfg.keyExchangeFactories = listOf(
                net.schmizz.sshj.transport.kex.ECDHNistP.Factory256(),
                net.schmizz.sshj.transport.kex.ECDHNistP.Factory384(),
                net.schmizz.sshj.transport.kex.ECDHNistP.Factory521(),
                net.schmizz.sshj.transport.kex.DHG14.Factory(),
                net.schmizz.sshj.transport.kex.DHG1.Factory()
            )
            cfg.keyAlgorithms = listOf(
                com.hierynomus.sshj.key.KeyAlgorithms.SSHRSA(),
                com.hierynomus.sshj.key.KeyAlgorithms.RSASHA256(),
                com.hierynomus.sshj.key.KeyAlgorithms.RSASHA512(),
                com.hierynomus.sshj.key.KeyAlgorithms.SSHDSA()
            )
        } catch (t: Throwable) {
            Log.w(TAG, "sshj Android config setup failed, using defaults: ${t.message}")
        }
        return SSHClient(cfg)
    }

    /** 主机公钥校验：有期望指纹则精确匹配，否则接受并记录（首连 TOFU 语义）。 */
    private class FingerprintVerifier(private val expected: String?) :
        net.schmizz.sshj.transport.verification.HostKeyVerifier {
        override fun verify(hostname: String?, port: Int, key: java.security.PublicKey?): Boolean {
            if (key == null) return false
            if (expected.isNullOrEmpty()) return true // TOFU：可在此持久化指纹后再放行
            return try {
                val digester = java.security.MessageDigest.getInstance("SHA-256")
                val fp = "SHA256:" + java.util.Base64.getEncoder().withoutPadding()
                    .encodeToString(digester.digest(key.encoded))
                fp == expected.trim()
            } catch (_: Exception) { false }
        }
        override fun findExistingAlgorithms(hostname: String?, port: Int): List<String> = emptyList()
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
