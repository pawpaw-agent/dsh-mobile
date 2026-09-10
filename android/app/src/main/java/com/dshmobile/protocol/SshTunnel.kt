package com.dshmobile.protocol

import android.util.Log
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SSH 本地端口转发 —— dbclient 进程方案（方案 B）。
 *
 * 与终端模式（TuiActivity）共用同一我们 CI 编译的 dropbear dbclient：
 * 密码/证书/TOFU（-y）/known_hosts（HOME）走完全一致的环境变量与参数，
 * 不再引入第二套 SSH 实现（JSch 依赖移除后，认证行为 100% 统一）。
 *
 * 转发实现：`dbclient -p <sshPort> -y -q -K 30 -N
 *   -L 127.0.0.1:<localPort>:<remoteHost>:<remotePort> user@host`
 *  - `-N`：不执行远程命令（纯隧道）
 *  - `-L`：本地转发。注意 dropbear 的 -L 端口 0 **不自分配**（JSch 有此能力，
 *    dbclient 没有），因此本地端口由本类预选（bind(0) 拿系统分配的空闲端口）。
 *  - 断线：dbclient 进程退出 → 看门狗检测 → 重建（新端口 → onLocalBaseChanged）。
 *
 * 令牌获取（[execOnce]）：同样用 dbclient 一次性进程执行
 * `dbclient -y -q -K 3 user@host "<cmd>"` 收集 stdout（命令模式无 pty）。
 *
 * 对外契约与旧 JSch 版一致（start/localBaseUrl/onStateChange/
 * onLocalBaseChanged/close/execOnce），调用方无需改动。
 *
 * dbclient 路径：DshApp.onCreate 通过 [binPath] 注入（Application 有
 * applicationInfo.nativeLibraryDir；SshTunnel 自身无 Context）。
 */
class SshTunnel(
    private val sshHost: String,
    private val sshPort: Int,
    private val sshUser: String,
    private val remoteHost: String,
    private val remotePort: Int,
    private val auth: Auth,
    /**
     * 本地转发端口候选（按序取第一个空闲的）。**必须稳定**，理由见 [pickFreePort]。
     *
     * 默认是给 WebView 用的 [PORT_CANDIDATES]（3080 优先）；后台监控服务
     * （AgentMonitorService）不关心 origin，传 [MONITOR_PORT_CANDIDATES] 主动
     * 让出 3080，免得两边抢端口、有一方退到 13080 后 origin 分家。
     */
    private val preferredPorts: List<Int> = PORT_CANDIDATES,
) : Closeable {

    sealed class Auth {
        data class Password(val password: String) : Auth()
        data class KeyPair(val privateKeyFile: File, val passphrase: String? = null) : Auth()
    }

    private val started = AtomicBoolean(false)
    private val connecting = AtomicBoolean(false)
    @Volatile private var proc: Process? = null
    @Volatile private var localPort: Int = 0
    private var reconnectThread: Thread? = null

    /** 隧道就绪后手机侧访问的本地基址（http://127.0.0.1:<port>）。 */
    @Volatile var localBaseUrl: String? = null
        private set

    @Volatile var onStateChange: ((String) -> Unit)? = null  // connecting / connected / reconnecting

    /** 本地端口/基址变化（断线重连后会更换端口）；调用方需据此重新加载。 */
    @Volatile var onLocalBaseChanged: ((String) -> Unit)? = null

    /** 建立隧道并阻塞等待本地监听就绪（最多 ~10s）。成功返回本地端口。 */
    @Synchronized
    fun start(): Int {
        if (started.get()) return localPort
        started.set(true)
        connectOnce()
        // 守护重连：dbclient 进程退出（网络断/服务端关）后重建
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

    private fun isAlive(): Boolean {
        val p = proc ?: return false
        if (!p.isAlive) return false
        return localPort > 0 && localListening()
    }

    private fun localListening(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", localPort), 500) }
        true
    } catch (_: Exception) { false }

    /**
     * 选本地转发端口：按 [PORT_CANDIDATES] 顺序取第一个空闲的（**不用随机端口**）。
     *
     * 为什么端口必须稳定：WebView 的 origin 是 `http://127.0.0.1:<port>`，端口一变
     * origin 就变，localStorage / IndexedDB / cache 全部另起一份。实测随机端口时
     * 重连后上一 origin 的 localStorage 读不到（探针 key 返回 null），每条会话的
     * 草稿/视图状态丢失，旧 origin 的 ~4.5KB 还永久留在 leveldb 里。服务端 cookie
     * 名又含 authority（`dsh-auth-` + sha256(authority)），端口固定后浏览器直接
     * 覆盖而不是累积（HTTP 431 那次的根因）。
     *
     * 首选 3080 与 dsh web 的默认端口一致，撞车时退到 13080。
     */
    private fun pickFreePort(): Int {
        for (p in preferredPorts) {
            if (isPortFree(p)) {
                if (p != preferredPorts.first()) {
                    Log.w(TAG, "本地端口 ${preferredPorts.first()} 被占用，改用 $p")
                }
                return p
            }
        }
        // 明显异常状态（候选端口都被占）→ 显式报错，不偷偷换随机端口（换了 origin 就变）
        Log.e(TAG, "本地端口候选 ${preferredPorts.joinToString()} 全部被占用")
        return -1
    }

    /** 试绑即释放。dbclient -L 不接受端口 0，必须先定下具体端口（存在极小抢注竞态）。 */
    private fun isPortFree(port: Int): Boolean = try {
        ServerSocket(port).use { true }
    } catch (_: Exception) {
        false
    }

    private fun connectOnce() {
        onStateChange?.invoke("connecting")
        val bin = binPath
        if (bin == null) {
            Log.e(TAG, "dbclient path not set — DshApp.onCreate should inject it")
            onStateChange?.invoke("failed: 内部错误（dbclient 路径未设置）")
            return
        }
        for (attempt in 1..3) {
            if (!started.get()) return
            val port = pickFreePort()
            if (port < 0) {
                onStateChange?.invoke(
                    "failed: 本地端口 ${preferredPorts.joinToString(" / ")} 都被占用，请关掉占用它的应用后重试")
                return
            }
            val args = mutableListOf(
                bin,
                "-p", sshPort.toString(),
                "-y",                 // 首连接受未知主机公钥（TOFU，与终端模式一致）
                "-q",                 // 静默 remote banner/日志
                // ⚠️ 不用 -K <n>：dropbear keepalive 依赖服务端应答，而本部署的
                // OpenSSH 10.0p1 不回答其 keepalive（实测 90s=30×3 后
                // "Keepalive timeout"），隧道必死、页面永远加载不完。
                // 默认 0 = 永不超时（与电脑端普通 ssh -L 行为一致），死链由看门狗兜底。
                "-N",                 // 纯隧道，不执行远程命令
                "-L", "127.0.0.1:$port:$remoteHost:$remotePort"
            )
            if (auth is Auth.KeyPair) {
                args += listOf("-i", auth.privateKeyFile.absolutePath)
                auth.passphrase?.takeIf { it.isNotEmpty() }?.let { Log.w(TAG, "key passphrase unsupported by dbclient CLI; try without") }
            }
            args += "$sshUser@$sshHost"
            Log.i(TAG, "dbclient tune #$attempt: ${args.joinToString(" ").take(160)}")
            try {
                val pb = ProcessBuilder(args).redirectErrorStream(true)
                pb.environment().clear()
                baseEnv().forEach { (k, v) -> pb.environment().put(k, v) }
                val p = pb.start()
                // 立即等待端口就绪（进程可能秒退：认证失败等）
                waitForLocal(port, p, timeoutMs = 8_000)
                if (!p.isAlive) {
                    val err = p.inputStream.bufferedReader().readText().trim()
                    Log.w(TAG, "dbclient tune #$attempt exited rc=${p.exitValue()}: ${err.take(200)}")
                    if (attempt == 3) onStateChange?.invoke("failed: $err")
                    continue
                }
                proc = p
                localPort = port
                val base = "http://127.0.0.1:$port"
                val changed = localBaseUrl != base
                val first = localBaseUrl == null
                localBaseUrl = base
                pumpLogs(p)
                onStateChange?.invoke("connected")
                if (changed && !first) onLocalBaseChanged?.invoke(base)
                return
            } catch (e: Exception) {
                Log.w(TAG, "dbclient launch failed: ${e.message}")
                if (attempt == 3) onStateChange?.invoke("failed: ${e.message}")
            }
        }
    }

    /** 轮询本地端口可连 + 进程存活，等待隧道就绪。返回进程是否仍存活。 */
    private fun waitForLocal(port: Int, p: Process, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!p.isAlive) return false
            if (try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200) }
                true
            } catch (_: Exception) { false }) return true
            try { Thread.sleep(100) } catch (_: InterruptedException) { return false }
        }
        return false
    }

    /** 消费 dbclient 输出（-q 后信息很少，认证错误行仍会打印），避免管道阻塞。 */
    private fun pumpLogs(p: Process) {
        Thread {
            try { p.inputStream.bufferedReader().forEachLine { Log.i(TAG, "dbclient: $it") } }
            catch (_: Exception) {}
        }.apply { isDaemon = true; name = "dbclient-log"; start() }
    }

    /** 与终端模式一致的认证 env（HOME 写 known_hosts；密码经 DROPBEAR_PASSWORD）。 */
    private fun baseEnv(): Map<String, String> {
        val m = mutableMapOf(
            "HOME" to (System.getenv("HOME") ?: "/data/data/com.dshmobile.app"),
            "TERM" to "xterm-256color"
        )
        if (auth is Auth.Password) m["DROPBEAR_PASSWORD"] = auth.password
        return m
    }

    /**
     * 在远端执行一条命令并收集 stdout（一次性 dbclient 进程，跑完即断）。
     * 命令模式：无 pty（不分配 tty），远端命令结束后 dbclient 自动退出。
     * 超时后杀进程返回已收集内容；失败返回 null。
     */
    fun execOnce(cmd: String, timeoutMs: Long = 8_000): String? {
        val bin = binPath ?: return null
        val args = mutableListOf(
            bin,
            "-p", sshPort.toString(),
            // -K 必须 ≥1：dbclient 拒绝 -K 0（"Bad keepalive '0'"），
            // 实测认证后命令都不执行——token 自动获取因此全失败（401）。
            "-y", "-q", "-K", "3",
        )
        if (auth is Auth.KeyPair) args += listOf("-i", auth.privateKeyFile.absolutePath)
        args += listOf("$sshUser@$sshHost", cmd)
        return try {
            val pb = ProcessBuilder(args).redirectErrorStream(true)
            pb.environment().clear()
            baseEnv().forEach { (k, v) -> pb.environment().put(k, v) }
            val p = pb.start()
            val out = StringBuffer()
            val reader = Thread {
                try { p.inputStream.bufferedReader().forEachLine { out.append(it).append('\n') } }
                catch (_: Exception) {}
            }.apply { isDaemon = true; start() }
            val deadline = System.currentTimeMillis() + timeoutMs
            while (p.isAlive && System.currentTimeMillis() < deadline) Thread.sleep(100)
            if (p.isAlive) p.destroy()
            reader.join(500)
            out.toString().trim().takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "execOnce failed: ${e.message}")
            null
        }
    }

    override fun close() {
        started.set(false)
        reconnectThread?.interrupt()
        val dying = proc
        proc = null
        try { dying?.destroy() } catch (_: Exception) {}
        // 等旧 dbclient 真正退出：固定端口要立刻重绑，残留进程会占着监听 socket
        // （Process.destroy 是异步的）。正常几十毫秒就退，只有异常情况才等到超时。
        try {
            if (dying != null && !dying.waitFor(1500, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "dbclient 未在 1.5s 内退出，强制杀")
                dying.destroyForcibly()
            }
        } catch (_: Exception) {}
        localBaseUrl = null
    }

    companion object {
        private const val TAG = "SshTunnel"

        /** WebView 用的本地转发端口候选（按序取第一个空闲的）：3080 与 dsh web
         *  默认端口一致，被占时退到 13080。端口必须稳定，理由见 [pickFreePort]。 */
        val PORT_CANDIDATES = listOf(3080, 13080)

        /** 后台监控服务用的候选：主动让出 3080 给 WebView（它不关心 origin）。 */
        val MONITOR_PORT_CANDIDATES = listOf(13080, 3080)

        /** dbclient 可执行文件路径：由 DshApp.onCreate 注入（nativeLibraryDir/libdbclient.so）。 */
        @Volatile
        var binPath: String? = null

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
