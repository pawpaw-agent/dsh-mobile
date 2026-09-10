package com.dshhandheld.app

import android.app.Application
import android.util.Log
import android.webkit.WebView
import com.dshhandheld.protocol.SshTunnel
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Application 级单例：持有跨 Activity 存活的 WebView 与 **唯一的 SSH 隧道**。
 *
 * - WebView：跨重建保活，避免重新 loadUrl —— 前端 bundle 不必
 *   重新下载/解析/执行，滚动位置、JS 运行时、会话状态全部保留。
 * - SshTunnel：**所有权在这里**。[ensureTunnel] 是唯一入口，按配置指纹复用；
 *   调用方拿到的都是同一条隧道（此前 MainActivity 与后台通知服务各建一条 =
 *   两个 dbclient / 两个本地端口）。关闭只有 [closeTunnel]（用户手动断开）
 *   或进程结束。
 * - SshTunnel.binPath：dbclient 可执行文件（nativeLibraryDir/libdbclient.so），
 *   SshTunnel 自身无 Context，由这里注入（方案 B：进程式隧道与终端共用）。
 *
 * 后台行为：**没有前台服务**（原本的 agent 完成通知已在 1.11.0 移除，改由服务端
 * 经微信推送）。退到后台后进程降为 cached，可能被系统回收 → 回到 App 会冷启动
 * 重连一次；因为本地端口固定为 3080，WebView 的 origin 稳定，localStorage 里的
 * 会话/工作区状态不会因此丢失。
 */
class DshApp : Application() {

    /** WebView 保留实例。 */
    @Volatile
    var retainedWebView: WebView? = null

    /**
     * 当前 SSH 隧道（唯一实例）。只由 [ensureTunnel] / [closeTunnel] 改动；
     * 其他模块只读，**不要自己 close**（会打断 WebView 页面）。
     */
    @Volatile
    var sshTunnel: SshTunnel? = null
        private set

    /** 隧道状态/基址变化的订阅者（目前只有 MainActivity 订阅）。 */
    interface TunnelObserver {
        fun onTunnelState(state: String) {}
        fun onTunnelBaseChanged(base: String) {}
    }

    private val tunnelObservers = CopyOnWriteArrayList<TunnelObserver>()
    private val tunnelLock = Any()
    private var tunnelFingerprint: String? = null

    fun addTunnelObserver(o: TunnelObserver) {
        if (!tunnelObservers.contains(o)) tunnelObservers.add(o)
    }

    fun removeTunnelObserver(o: TunnelObserver) {
        tunnelObservers.remove(o)
    }

    override fun onCreate() {
        super.onCreate()
        SshTunnel.binPath = File(applicationInfo.nativeLibraryDir, "libdbclient.so")
            .takeIf { it.exists() }?.absolutePath
    }

    // ── 隧道所有权 ────────────────────────────────────────────────

    /**
     * 取得 SSH 配置 [cfg] 对应的隧道，必要时新建。**阻塞**（拨号 ~1-3s），
     * 必须在后台线程调用。失败返回 null。
     *
     * 复用规则：已有隧道且配置指纹一致且 [SshTunnel.isHealthy] → 直接返回。
     * [force] = true 时强制重建（用户在连接屏明确点了「连接」，可能是在修一条
     * 自己觉得有问题的隧道）。
     *
     * 同一时刻只允许一条拨号在跑（[tunnelLock]），避免 WebView 与后台服务
     * 同时建两条隧道。
     */
    fun ensureTunnel(cfg: JSONObject, force: Boolean = false): SshTunnel? {
        val fp = fingerprint(cfg)
        synchronized(tunnelLock) {
            val cur = sshTunnel
            if (!force && cur != null && tunnelFingerprint == fp && cur.isHealthy()) {
                Log.i(TAG, "ensureTunnel: 复用已有隧道 ${cur.localBaseUrl}")
                return cur
            }
            cur?.close()
            sshTunnel = null
            tunnelFingerprint = null

            val t = build(cfg) ?: return null
            t.onStateChange = { s ->
                Log.i(TAG, "tunnel state: $s")
                tunnelObservers.forEach { it.onTunnelState(s) }
            }
            t.onLocalBaseChanged = { b ->
                Log.i(TAG, "tunnel base changed: $b")
                tunnelObservers.forEach { it.onTunnelBaseChanged(b) }
            }
            t.start()
            if (t.localBaseUrl == null) {
                t.close()
                Log.w(TAG, "ensureTunnel: 拨号失败")
                return null
            }
            sshTunnel = t
            tunnelFingerprint = fp
            Log.i(TAG, "ensureTunnel: 已建立 ${t.localBaseUrl}")
            return t
        }
    }

    /** 关闭隧道（用户手动断开）。谁都不该在别处 close，否则会打断 WebView。 */
    fun closeTunnel() {
        synchronized(tunnelLock) {
            sshTunnel?.close()
            sshTunnel = null
            tunnelFingerprint = null
        }
    }

    /** 配置指纹：决定「同一条隧道能否复用」。含认证方式相关的全部字段。 */
    private fun fingerprint(cfg: JSONObject): String = listOf(
        cfg.optString("sshHost"), cfg.optInt("sshPort", 22).toString(),
        cfg.optString("sshUser"), cfg.optString("remoteHost", "127.0.0.1"),
        cfg.optInt("remotePort", 3080).toString(), cfg.optString("authType", "password"),
        cfg.optString("password"), cfg.optString("keyPath"), cfg.optString("keyPass")
    ).joinToString("\u0000")

    private fun build(cfg: JSONObject): SshTunnel? {
        val host = cfg.optString("sshHost")
        val user = cfg.optString("sshUser")
        if (host.isBlank() || user.isBlank()) return null
        val auth = if (cfg.optString("authType", "password") == "key") {
            val keyPath = cfg.optString("keyPath", "")
            if (keyPath.isBlank()) return null
            SshTunnel.Auth.KeyPair(File(keyPath), cfg.optString("keyPass").ifEmpty { null })
        } else {
            SshTunnel.Auth.Password(cfg.optString("password", ""))
        }
        return SshTunnel(
            sshHost = host,
            sshPort = cfg.optInt("sshPort", 22),
            sshUser = user,
            remoteHost = cfg.optString("remoteHost", "127.0.0.1"),
            remotePort = cfg.optInt("remotePort", 3080),
            auth = auth,
            // WebView 的 origin 是 http://127.0.0.1:<port>，所以端口必须稳定
            // （3080 优先）；见 SshTunnel.pickFreePort。
            preferredPorts = SshTunnel.PORT_CANDIDATES,
        )
    }

    private companion object {
        const val TAG = "DshApp"
    }
}
