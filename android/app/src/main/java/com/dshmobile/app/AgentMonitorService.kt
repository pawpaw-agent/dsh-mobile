package com.dshmobile.app

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.dshmobile.protocol.DshClient
import com.dshmobile.protocol.Models
import com.dshmobile.protocol.Rpc
import org.json.JSONObject

/**
 * Agent 完成通知服务（前台服务，App 退后台时启动）。
 *
 * 监听 DSH 的 host 级广播帧 `/api/events.host` → `host/session-status`：
 * running=true→false 边沿 = 某个会话的任务跑完 → 拉一次 session.list 取标题 → 推通知。
 *
 * 纯 WebView 版也使用：服务持有自己的轻量 [DshClient] 实例，
 * 只消费 host downlink。App 回前台即停服务，避免与页面重复耗电。
 *
 * SSH 隧道不由本服务持有：通过 [DshApp.ensureTunnel] 取用（WebView 那条在就复用，
 * 同一进程只允许一条 dbclient / 一个本地端口）。服务停止时**不关隧道**。
 *
 * 停止竞态（实测 1.5.2 踩中 ForegroundServiceDidNotStartInTimeException）：
 * 禁止外部 stopService —— startForegroundService 的 5s 窗口内（服务尚未执行
 * startForeground）收到 stop 会被 AMS 直接炸进程。改为服务内部自停：
 * [onCreate] 注册 ActivityLifecycleCallbacks，MainActivity 回前台时执行
 * stopSelf()；它与 onStartCommand 同在主线程队列，必然排在
 * startForeground 之后 —— 自停永不落入 pending-start 窗口。
 *
 * 权限：POST_NOTIFICATIONS（Android 13+ 运行时申请）、FOREGROUND_SERVICE、
 * FOREGROUND_SERVICE_SPECIAL_USE（API 34+，manifest 声明）。
 */
class AgentMonitorService : Service() {

    private var client: DshClient? = null
    private val lastRunning = HashMap<String, Boolean>()
    private var foregroundStarted = false
    private val connectStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    companion object {
        private const val TAG = "AgentMonitorService"
        private const val CHANNEL_ID = "agent-done"
        private const val NOTIFICATION_TAG = "agent-done"
        private const val FGS_ID = 42
        private fun prefs(ctx: Context) = ctx.getSharedPreferences("dsh-mobile", Context.MODE_PRIVATE)

        /** App 退后台时调用；服务在 App 回前台时自停（见类注释）。 */
        fun start(ctx: Context) {
            val i = Intent(ctx, AgentMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    /**
     * 自停回调：MainActivity 回前台 → stopSelf()。
     * 与 onStartCommand 同线程队列 → 顺序保证 startForeground 先执行。
     */
    private val selfStopCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            if (activity is MainActivity) {
                Log.i(TAG, "MainActivity resumed -> self-stop")
                stopSelf()
            }
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        // 尽早占用前台状态：5s 窗口从 startForegroundService 请求即开始，
        // 主线程繁忙/实例复用等场景不能只依赖 onStartCommand 的时序
        ensureForeground()
        (application as DshApp).registerActivityLifecycleCallbacks(selfStopCallbacks)
        Log.i(TAG, "onCreate (foreground=$foregroundStarted)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        connect()
        return START_STICKY
    }

    /** startForeground 幂等保护：onCreate / onStartCommand 只允许成功一次。 */
    private fun ensureForeground() {
        if (foregroundStarted) return
        try {
            startForeground(FGS_ID, monitorNotification("DSH Mobile"))
            foregroundStarted = true
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
        }
    }

    private fun openAppIntent(): PendingIntent {
        val i = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            this, 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(CHANNEL_ID, "Agent 完成通知", NotificationManager.IMPORTANCE_LOW)
            channel.description = "DeepSeek Harness 子代理/任务完成通知"
            nm.createNotificationChannel(channel)
        }
    }

    private fun monitorNotification(text: String, done: Boolean = false): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(if (done) "✅ Agent 任务完成" else "DSH Mobile")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(!done)
            .setAutoCancel(done)
            .setContentIntent(openAppIntent())
            .build()

    private fun connect() {
        if (!connectStarted.compareAndSet(false, true)) return
        val prefs = prefs(this)
        val base = prefs.getString("url", null) ?: run { stopSelf(); return }
        val launchToken = prefs.getString("server_token", "")?.trim().orEmpty()
        val rawSsh = prefs.getString("ssh_json", null)
        val needSsh = rawSsh != null &&
            (prefs.getBoolean("ssh_enabled", false) || base.startsWith("http://127.0.0.1:"))
        if (!needSsh) {
            startMonitor(DshClient(base, launchToken))
            return
        }
        val cfg = rawSsh?.let { try { JSONObject(it) } catch (_: Exception) { null } }
        if (cfg == null || cfg.optString("sshHost").isBlank() || cfg.optString("sshUser").isBlank()) {
            startMonitor(DshClient(base, launchToken))
            return
        }
        Thread {
            // 向 DshApp 要隧道：WebView 那条还在就复用（同一实例，不再多起一条
            // dbclient、不再占第二个端口）；终端模式下没有隧道时才新建。
            // 非强制 → 配置未变且健康就直接复用。
            val tunnel = (application as DshApp).ensureTunnel(cfg, force = false)
            val local = tunnel?.localBaseUrl
            if (local == null) {
                Log.w(TAG, "无可用隧道，停止监听")
                stopSelf()
                return@Thread
            }
            startMonitor(DshClient(local, launchToken))
        }.start()
    }

    private fun startMonitor(c: DshClient) {
        client = c
        c.setHostListener { _, payload ->
            if (payload.optString("type") != "host/session-status") return@setHostListener
            val sid = payload.optString("sessionId")
            val running = payload.optBoolean("running", false)
            val was = synchronized(lastRunning) { lastRunning.put(sid, running) }
            // true→false 边沿；首次见到的会话不通知（避免启动时的状态风暴）
            if (was == true && !running) notifyDone(c, sid)
        }
        Thread {
            // 基线：先记录各会话当前 running，再开事件流
            val r = c.sessionList()
            if (r is Rpc.Result.Ok) {
                Models.SessionList.fromJson(r.value).items.forEach {
                    synchronized(lastRunning) { lastRunning[it.sessionId] = it.running }
                }
            }
            c.start()
        }.start()
    }

    private fun notifyDone(c: DshClient, sessionId: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val id = sessionId.hashCode()
        nm.notify(NOTIFICATION_TAG, id, monitorNotification(sessionId.take(12), done = true))
        // 异步补标题（session.list 的 projections.values.title）
        Thread {
            val r = c.sessionList()
            if (r is Rpc.Result.Ok) {
                val title = Models.SessionList.fromJson(r.value).items
                    .firstOrNull { it.sessionId == sessionId }?.title
                if (!title.isNullOrEmpty()) {
                    nm.notify(NOTIFICATION_TAG, id, monitorNotification(title.take(120), done = true))
                }
            }
        }.start()
    }

    override fun onDestroy() {
        (application as? DshApp)?.unregisterActivityLifecycleCallbacks(selfStopCallbacks)
        client?.stop()
        client = null
        // ⚠️ 不关 SSH 隧道：它归 DshApp 所有，WebView 那条还要继续用
        Log.i(TAG, "onDestroy")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

}
