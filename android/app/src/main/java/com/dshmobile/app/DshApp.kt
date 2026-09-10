package com.dshmobile.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.util.Log
import android.webkit.WebView
import com.dshmobile.protocol.SshTunnel
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Application 级单例：持有跨 Activity 存活的 WebView 与 **唯一的 SSH 隧道**。
 *
 * - WebView：跨重建保活，避免重新 loadUrl —— 前端 bundle 不必
 *   重新下载/解析/执行，滚动位置、JS 运行时、会话状态全部保留。
 * - SshTunnel：**所有权在这里**。MainActivity（WebView 用）和
 *   AgentMonitorService（后台通知用）都通过 [ensureTunnel] 取同一条隧道，
 *   不再各自建一条 —— 此前是两条 dbclient + 四个 WebSocket + 两个本地端口，
 *   固定端口后还会互抢。谁都不负责关闭它，只有 [closeTunnel]（用户手动断开）
 *   或进程结束才关。
 * - SshTunnel.binPath：dbclient 可执行文件（nativeLibraryDir/libdbclient.so），
 *   SshTunnel 自身无 Context，由这里注入（方案 B：进程式隧道与终端共用）。
 *
 * 后台监听触发（U3 修复）：不再由 MainActivity.onPause 触发 —— 打开
 * TuiActivity 也会让 MainActivity onPause，导致用户正在终端里干活时
 * 后台错误地多起一条 SSH 隧道 + 双 WebSocket + 前台通知。改为可见性计数：
 * 所有 dsh UI（Main/Tui）全部 onStop 后才启动 AgentMonitorService。
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

    /** 隧道状态/基址变化的订阅者（MainActivity 用；后台服务不关心）。 */
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

    /** 当前可见的 dsh UI（MainActivity / TuiActivity）。 */
    private val visibleActivities: MutableSet<String> = Collections.synchronizedSet(HashSet())

    private val visibilityCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            visibleActivities.add(activity.javaClass.name)
        }
        override fun onActivityStopped(activity: Activity) {
            visibleActivities.remove(activity.javaClass.name)
            // 最后一个 dsh UI 消失 = App 真正退后台 → 启动 agent 完成监听
            if (visibleActivities.isEmpty()) startMonitorIfNeeded()
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    override fun onCreate() {
        super.onCreate()
        SshTunnel.binPath = File(applicationInfo.nativeLibraryDir, "libdbclient.so")
            .takeIf { it.exists() }?.absolutePath
        registerActivityLifecycleCallbacks(visibilityCallbacks)
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

    /** 只有曾连接过（url 已保存）才启动监听；权限询问由 MainActivity 单独负责。 */
    private fun startMonitorIfNeeded() {
        val prefs = getSharedPreferences("dsh-mobile", Context.MODE_PRIVATE)
        if (prefs.getString("url", null) == null) return
        try {
            AgentMonitorService.start(this)
        } catch (e: Exception) {
            Log.w(TAG, "monitor start failed", e)
        }
    }

    private companion object {
        const val TAG = "DshApp"
    }
}
