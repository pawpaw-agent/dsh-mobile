package com.dshhandheld.app

import android.app.Activity
import android.app.Application
import android.content.MutableContextWrapper
import android.util.Log
import android.webkit.WebView
import com.dshhandheld.protocol.SshTunnel
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
 * - SshTunnel.binPath / SshTunnel.homeDir：dbclient 可执行文件与它的 HOME
 *   （nativeLibraryDir/libdbclient.so、filesDir），SshTunnel 自身无 Context，
 *   由这里注入（方案 B：进程式隧道与终端共用）。
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
        private set

    /**
     * 保活 WebView 的 context 包装器。
     *
     * **为什么需要它**：WebView 必须用 Activity context 创建（WebView 的默认实现要用窗口，
     * 例如页面里的 `confirm()` 会走 WebChromeClient 的默认对话框），但它被本 Application
     * 长期持有。若不处理，**第一个 MainActivity 连同它整棵连接屏视图树**会随进程存活到结束
     * —— Activity 重建（"不保留活动"、多窗口、字号/语言变更）后，旧实例也回收不掉。
     *
     * [MutableContextWrapper] 是官方推荐的解法：WebView 实例与它的全部状态（JS 运行时、
     * localStorage、滚动位置）都保留，但它看到的 base context 可以跟着当前 Activity 走；
     * Activity 销毁时换回 application，陈旧 Activity 即可被回收。
     */
    @Volatile
    private var retainedWebViewContext: MutableContextWrapper? = null

    /**
     * 取得保活 WebView（首次调用时创建），并把它的 context 指向 [activity]。
     *
     * 每次 Activity 重建都要调一次：这正是「换掉 WebView 眼中的 Activity」的时机。
     */
    fun obtainWebView(activity: Activity): WebView {
        val existing = retainedWebView
        val wrapper = retainedWebViewContext
        if (existing != null && wrapper != null) {
            if (wrapper.baseContext !== activity) {
                Log.i(TAG, "obtainWebView: 复用保活实例，context → ${activity.javaClass.simpleName}")
                wrapper.baseContext = activity
            }
            return existing
        }
        Log.i(TAG, "obtainWebView: 首次创建")
        val w = MutableContextWrapper(activity)
        retainedWebViewContext = w
        return WebView(w).also { retainedWebView = it }
    }

    /**
     * Activity 销毁时调用：把 WebView 的 context 换回 application，释放对 Activity 的引用。
     *
     * 与 [obtainWebView] 配对。漏掉这一步，[MutableContextWrapper] 就等于没加。
     */
    fun releaseWebViewContext() {
        retainedWebViewContext?.let {
            if (it.baseContext !== this) {
                Log.i(TAG, "releaseWebViewContext: context → application")
                it.baseContext = this
            }
        }
    }

    /**
     * 当前 SSH 隧道（唯一实例）。只由 [ensureTunnel] / [closeTunnel] 改动；
     * 其他模块只读，**不要自己 close**（会打断 WebView 页面）。
     */
    @Volatile
    var sshTunnel: SshTunnel? = null
        private set

    /** 隧道基址变化的订阅者（目前只有 MainActivity 订阅）。 */
    interface TunnelObserver {
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
        // HOME 用 filesDir，与终端模式（TuiActivity）一致：两边写同一份 known_hosts，
        // TOFU 信任才不会分裂成两份。见 SshTunnel.homeDir。
        SshTunnel.homeDir = filesDir.absolutePath
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
    fun ensureTunnel(cfg: SshConfig, force: Boolean = false): SshTunnel? {
        val fp = cfg.fingerprint()
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
                // 只记日志：状态条由 ①②③ 引导流程表达，connecting/connected 属内部
                // 状态（曾显示为「隧道: connected」，是术语）。
                Log.i(TAG, "tunnel state: $s")
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

    /** 配置指纹由 [SshConfig.fingerprint] 提供（字段定义在那里，不再各写一份）。 */
    private fun build(cfg: SshConfig): SshTunnel? {
        if (!cfg.isComplete) return null
        // 私钥方式但没给路径 → 配置不可用（终端模式另有「现生成一对」的回退，不在此列）
        val auth = cfg.toAuth() ?: return null
        return SshTunnel(
            sshHost = cfg.host,
            sshPort = cfg.port,
            sshUser = cfg.user,
            remoteHost = cfg.remoteHost,
            remotePort = cfg.remotePort,
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
