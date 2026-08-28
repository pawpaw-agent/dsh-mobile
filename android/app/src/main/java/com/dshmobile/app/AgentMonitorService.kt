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

/**
 * Agent 完成通知服务（前台服务，App 退后台时启动）。
 *
 * 监听 DSH 的 host 级广播帧 `/api/events.host` → `host/session-status`：
 * running=true→false 边沿 = 某个会话的任务跑完 → 拉一次 session.list 取标题 → 推通知。
 *
 * 与 UI 模式无关（WebView / 原生都能用）：服务持有自己的轻量 [DshClient] 实例，
 * 只消费 host downlink。App 回前台即停服务，避免与页面重复耗电。
 *
 * 权限：POST_NOTIFICATIONS（Android 13+ 运行时申请）、FOREGROUND_SERVICE、
 * FOREGROUND_SERVICE_SPECIAL_USE（API 34+，manifest 声明）。
 */
class AgentMonitorService : Service() {

    private var client: DshClient? = null
    private val lastRunning = HashMap<String, Boolean>()

    private companion object {
        const val CHANNEL_ID = "agent-done"
        const val NOTIFICATION_TAG = "agent-done"
        const val FGS_ID = 42
        fun prefs(ctx: Context) = ctx.getSharedPreferences("dsh-mobile", Context.MODE_PRIVATE)
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(FGS_ID, buildMonitorNotification())
        connect()
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
        val base = prefs(this).getString("url", null) ?: run { stopSelf(); return }
        val c = DshClient(base)
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        client?.stop()
        client = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        /** App 退后台时调用；回前台/退出时调用 [stop]。 */
        fun start(ctx: Context) {
            val i = Intent(ctx, AgentMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, AgentMonitorService::class.java)) }
    }
}
