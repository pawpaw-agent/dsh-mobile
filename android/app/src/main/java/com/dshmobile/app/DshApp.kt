package com.dshmobile.app

import android.app.Application
import android.webkit.WebView
import com.dshmobile.protocol.SshTunnel

/**
 * Application 级单例：持有跨 Activity 存活的 WebView 与 SSH 隧道。
 *
 * - WebView：跨重建保活，避免重新 loadUrl —— 前端 bundle 不必
 *   重新下载/解析/执行，滚动位置、JS 运行时、会话状态全部保留。
 * - SshTunnel：SSH 模式连接时持有，退出/重连时 close。
 */
class DshApp : Application() {

    /** WebView 保留实例。 */
    @Volatile
    var retainedWebView: WebView? = null

    /** SSH 隧道（SSH 模式连接时持有；退出/重连时 close）。 */
    @Volatile
    var sshTunnel: SshTunnel? = null
}
