package com.dshmobile.app

import android.app.Application
import android.webkit.WebView
import com.dshmobile.protocol.DshClient

/**
 * Application 级单例：持有跨 Activity 存活的 [DshClient] 与 WebView。
 *
 * - WebView（完整网页模式）：跨重建保活，避免重新 loadUrl —— 前端 bundle 不必
 *   重新下载/解析/执行，滚动位置、JS 运行时、会话状态全部保留。
 * - DshClient（原生简版模式）：HTTP + 双 WebSocket downlink 不随 Activity 重建。
 * - SshTunnel：SSH 模式连接时持有，退出/重连时 close。
 */
class DshApp : Application() {

    /** WebView 保留实例（完整网页模式）。 */
    @Volatile
    var retainedWebView: WebView? = null

    /** 进程级单例客户端（原生简版模式）。 */
    @Volatile
    var client: DshClient? = null

    /** 连接后建立的客户端是否已启动（start() 幂等）。 */
    @Volatile
    var clientStarted: Boolean = false

    /** SSH 隧道（SSH 模式连接时持有；退出/重连时 close）。 */
    @Volatile
    var sshTunnel: com.dshmobile.protocol.SshTunnel? = null
}
