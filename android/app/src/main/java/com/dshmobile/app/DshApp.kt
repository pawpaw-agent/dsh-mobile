package com.dshmobile.app

import android.app.Application
import com.dshmobile.protocol.DshClient

/**
 * Application 级单例：持有跨 Activity 存活的 [DshClient]。
 *
 * 让原生 DshClient（HTTP + 两个 WebSocket downlink）不随 Activity/转屏重建——
 * 会话状态、连接、事件流订阅全部保留。仅进程存活期间有效；进程被系统杀死后需重连。
 */
class DshApp : Application() {

    /** 进程级单例客户端；由连接屏在连接成功后设置 base，或直接 set。 */
    @Volatile
    var client: DshClient? = null

    /** 连接后建立的客户端是否已启动（start() 幂等）。 */
    @Volatile
    var clientStarted: Boolean = false

    /** SSH 隧道（SSH 模式连接时持有；退出/重连时 close）。 */
    @Volatile
    var sshTunnel: com.dshmobile.protocol.SshTunnel? = null
}
