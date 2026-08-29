package com.dshmobile.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.dshmobile.protocol.DshClient
import com.dshmobile.protocol.Models
import com.dshmobile.protocol.Rpc
import com.dshmobile.protocol.SshTunnel
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
 * 权限：POST_NOTIFICATIONS（Android 13+ 运行时申请）、FOREGROUND_SERVICE、
 * FOREGROUND_SERVICE_SPECIAL_USE（API 34+，manifest 声明）。
 */
class AgentMonitorService : Service() {

    private var client: DshClient? = null
    private var sshTunnel: SshTunnel? = null
    private val lastRunning = HashMap<String, Boolean>()

    companion object {
        private const val CHANNEL_ID = "agent-done"
        private const val NOTIFICATION_TAG = "agent-done"
        private const val FGS_ID = 42
        private fun prefs(ctx: Context) = ctx.getSharedPreferences("dsh-mobile", Context.MODE_PRIVATE)

        /** App 退后台时调用；回前台/退出时调用 [stop]。 */
        fun start(ctx: Context) {
            val i = Intent(ctx, AgentMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, AgentMonitorService::class.java)) }
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
        startForeground(FGS_ID, monitorNotification("DSH Mobile"))
        connect()
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
            .setSmallIcon(
                if (done) android.R.drawable.stat_sys_download_done
                else android.R.drawable.stat_notify_sync
            )
            .setOngoing(!done)
            .setAutoCancel(done)
            .build()

    private fun connect() {
        val prefs = prefs(this)
        val base = prefs.getString("url", null) ?: run { stopSelf(); return }
        val rawSsh = prefs.getString("ssh_json", null)
        val needSsh = rawSsh != null &&
            (prefs.getBoolean("ssh_enabled", false) || base.startsWith("http://127.0.0.1:"))
        if (!needSsh) {
            startMonitor(DshClient(base))
            return
        }
        val cfg = rawSsh?.let { try { JSONObject(it) } catch (_: Exception) { null } }
        val host = cfg?.optString("sshHost") ?: ""
        val user = cfg?.optString("sshUser") ?: ""
        if (cfg == null || host.isBlank() || user.isBlank()) {
            startMonitor(DshClient(base))
            return
        }
        val tunnel = SshTunnel(
            sshHost = host,
            sshPort = cfg.optInt("sshPort", 22),
            sshUser = user,
            remoteHost = cfg.optString("remoteHost", "127.0.0.1"),
            remotePort = cfg.optInt("remotePort", 3080),
            auth = if (cfg.optString("authType", "password") == "key") {
                val keyPath = cfg.optString("keyPath", "")
                if (keyPath.isBlank()) {
                    startMonitor(DshClient(base))
                    return
                }
                SshTunnel.Auth.KeyPair(java.io.File(keyPath), cfg.optString("keyPass").ifEmpty { null })
            } else SshTunnel.Auth.Password(cfg.optString("password", ""))
        )
        Thread {
            tunnel.start()
            val local = tunnel.localBaseUrl
            if (local == null) {
                tunnel.close()
                stopSelf()
                return@Thread
            }
            // 后台通知使用和主界面一致的 SSH 回环通道
            prefs.edit().putString("url", local).apply()
            startMonitor(DshClient(local), tunnel)
        }.start()
    }

    private fun startMonitor(c: DshClient, tunnel: SshTunnel? = null) {
        client = c
        sshTunnel = tunnel
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        client?.stop()
        client = null
        try { sshTunnel?.close() } catch (_: Exception) {}
        sshTunnel = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

}
