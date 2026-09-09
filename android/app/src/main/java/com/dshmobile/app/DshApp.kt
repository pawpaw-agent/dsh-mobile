package com.dshmobile.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.util.Log
import android.webkit.WebView
import com.dshmobile.protocol.SshTunnel
import java.io.File
import java.util.Collections

/**
 * Application 级单例：持有跨 Activity 存活的 WebView 与 SSH 隧道。
 *
 * - WebView：跨重建保活，避免重新 loadUrl —— 前端 bundle 不必
 *   重新下载/解析/执行，滚动位置、JS 运行时、会话状态全部保留。
 * - SshTunnel：SSH 模式连接时持有，退出/重连时 close。
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

    /** SSH 隧道（SSH 模式连接时持有；退出/重连时 close）。 */
    @Volatile
    var sshTunnel: SshTunnel? = null

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

    /** 只有曾连接过（url 已保存）才启动监听；权限询问由 MainActivity 单独负责。 */
    private fun startMonitorIfNeeded() {
        val prefs = getSharedPreferences("dsh-mobile", Context.MODE_PRIVATE)
        if (prefs.getString("url", null) == null) return
        try {
            AgentMonitorService.start(this)
        } catch (e: Exception) {
            Log.w("DshApp", "monitor start failed", e)
        }
    }
}
