package com.dshmobile.app

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dshmobile.protocol.DshClient
import com.dshmobile.protocol.Models
import com.dshmobile.protocol.Rpc
import org.json.JSONArray
import org.json.JSONObject

/**
 * 原生会话视图：Session 列表 + 打开会话 + 发送 prompt + 实时流式渲染。
 *
 * 所有请求走 [DshClient]（/api RPC + /api/events.mux downlink）。
 * MuxFrame `session/event` 帧 → [appendTranscript] 渲染文本增量（text-delta / reasoning-delta）；
 * HostFrame 在这里暂不处理（保持最小可用）。
 */
class ConversationActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var client: DshClient
    private var sessionId: String? = null

    // transcript 容器（vertical LinearLayout in ScrollView）
    private lateinit var transcript: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var sessionListText: TextView
    private lateinit var input: EditText
    private lateinit var titleView: TextView

    // 当前正在累积的 assistant 文本
    private var streamingText = StringBuilder()
    private var streamingView: TextView? = null

    private companion object {
        const val COL_BG = 0xFF14141F.toInt()
        const val COL_PANEL = 0xFF1E1E2E.toInt()
        const val COL_ACCENT = 0xFFE94560.toInt()
        const val COL_USER = 0xFF0F3460.toInt()
        const val COL_ASSIST = 0xFF252537.toInt()
        const val COL_TEXT = 0xFFFFFFFF.toInt()
        const val COL_MUTED = 0xB3FFFFFF.toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client = (application as DshApp).client ?: run {
            finish(); return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COL_BG)
        }

        // 顶栏：标题 + 会话列表快捷区
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }
        titleView = TextView(this).apply {
            text = "会话"; textSize = 20f; setTextColor(COL_TEXT)
        }
        titleRow.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val modelBtn = Button(this).apply {
            text = "模型"; setTextColor(COL_TEXT); setBackgroundColor(0x33FFFFFF)
            setOnClickListener { chooseModel() }
        }
        titleRow.addView(modelBtn, LinearLayout.LayoutParams(dp(72), dp(42)))
        root.addView(titleRow)

        sessionListText = TextView(this).apply {
            textSize = 13f; setTextColor(COL_MUTED); setPadding(dp(16), 0, dp(16), dp(8))
            maxLines = 6
        }
        root.addView(sessionListText)

        // 会话列表便捷按钮（横向滚动）
        val sessionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        root.addView(sessionBar)

        // transcript
        transcript = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply {
            addView(transcript)
        }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // 输入栏
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(8), dp(16), dp(16))
            setBackgroundColor(COL_PANEL)
        }
        input = EditText(this).apply {
            hint = "输入…"; setTextColor(COL_TEXT); setHintTextColor(COL_MUTED)
            setBackgroundColor(0x33FFFFFF)
        }
        inputRow.addView(input, LinearLayout.LayoutParams(0, dp(46), 1f))
        val send = Button(this).apply {
            text = "发送"; setTextColor(COL_TEXT); setBackgroundColor(COL_ACCENT)
            setOnClickListener { sendPrompt() }
        }
        inputRow.addView(send, LinearLayout.LayoutParams(dp(72), dp(46)).apply { leftMargin = dp(8) })
        root.addView(inputRow)

        setContentView(root)

        // 打开会话列表
        loadSessions()
    }

    private fun loadSessions() {
        Thread {
            val r = client.sessionList()
            ui.post {
                sessionBarRefresh(r)
            }
        }.start()
        // 订阅事件流
        subscribeEvents()
    }

    private fun sessionBarRefresh(r: Rpc.Result) {
        if (r is Rpc.Result.Ok) {
            val list = Models.SessionList.fromJson(r.value)
            val names = list.items.take(5).joinToString("\n") {
                val t = it.title ?: it.sessionId.take(8)
                "${if (it.running) "●" else "○"} $t"
            }
            sessionListText.text = if (names.isEmpty()) "暂无会话" else "最近会话:\n$names"
            // 打开第一个会话
            list.items.firstOrNull()?.let { openSession(it.sessionId) }
        }
    }

    private fun openSession(sid: String) {
        if (sessionId == sid) return
        sessionId = sid
        titleView.text = "会话 ${sid.take(8)}"
        clearTranscript()
        // 拉取历史
        Thread {
            val r = client.sessionHistory(sid)
            if (r is Rpc.Result.Ok) {
                val events = r.value?.optJSONArray("events")
                ui.post {
                    if (events != null) {
                        for (i in 0 until events.length()) {
                            val ev = events.optJSONObject(i)?.optJSONObject("event")
                            if (ev != null) renderHistoryEvent(ev)
                        }
                    }
                }
            }
        }.start()
    }

    /** 模型选择：拉取 session.models，展示 provider group / model 列表并选中。 */
    private fun chooseModel() {
        val sid = sessionId ?: run { toast("先打开一个会话"); return }
        Thread {
            val r = client.sessionModels(sid)
            ui.post {
                if (r !is Rpc.Result.Ok) { toast("获取模型失败"); return@post }
                val m = Models.SessionModels.fromJson(r.value)
                // 扁平化为 {provider, modelId, name} 列表
                val flat = ArrayList<Pair<String, Models.CatalogModel>>()
                for (g in m.groups) for (mdl in g.models) flat.add(g.id to mdl)
                if (flat.isEmpty()) { toast("无可用模型"); return@post }

                val names = flat.map { it.second.name }
                val titles = names.toTypedArray()
                android.app.AlertDialog.Builder(this)
                    .setTitle("选择模型")
                    .setItems(titles) { _, which ->
                        val p = flat[which]
                        Thread { client.sessionSelectModel(sid, p.first, p.second.id) }.start()
                        toast("已选: ${p.second.name}")
                    }
                    .show()
            }
        }.start()
    }

    /** 渲染历史里的 user/assistant 消息。 */
    private fun renderHistoryEvent(ev: JSONObject) {
        val type = ev.optString("type")
        if (type == "user/message") {
            val text = extractText(ev.optJSONObject("data"))
            appendBubble(text ?: "", COL_USER, left = true)
        } else if (type == "assistant/message") {
            val text = extractText(ev.optJSONObject("data")?.optJSONObject("message"))
            appendBubble(text ?: "", COL_ASSIST, left = false)
        }
    }

    private fun subscribeEvents() {
        client.onMuxFrame { rpcId, payload ->
            val type = payload.optString("type")
            ui.post {
                when (type) {
                    "session/event" -> {
                        val sid = payload.optString("sessionId")
                        if (sid != sessionId) return@post
                        val event = payload.optJSONObject("event") ?: return@post
                        handleEvent(event)
                    }
                    // 工具权限审批：agent 请求执行命令时弹出(允一次/拒绝)
                    "approval/requested" -> {
                        val sid = payload.optString("sessionId")
                        if (sid != sessionId) return@post
                        showApproval(rpcId, payload)
                    }
                    "question/requested" -> {
                        // 用户提问：展示问题（可选：respondQuestion）
                    }
                    "session/jobs" -> {
                        // 任务/进度视图
                        val jobs = payload.optJSONArray("jobs")
                        if (jobs != null) renderJobs(jobs)
                    }
                    "stream/error" -> { /* 服务端流错误，可显示 */ }
                }
            }
        }
    }

    /** 渲染工具审批弹窗：允许一次 / 拒绝，调用 client.respondApproval 应答。 */
    private fun showApproval(rpcId: String, payload: JSONObject) {
        val sid = payload.optString("sessionId")
        val approvalId = payload.optString("approvalId")
        val toolName = payload.optString("toolName")
        val reason = payload.optString("reason")

        appendSystemBubble("🔐 请求执行工具「$toolName」${if (reason.isNotEmpty()) " — $reason" else ""}")

        android.app.AlertDialog.Builder(this)
            .setTitle("是否需要批准？")
            .setMessage("工具「$toolName」请求执行${if (reason.isNotEmpty()) "：$reason" else ""}")
            .setPositiveButton("允许一次") { _, _ ->
                Thread { client.respondApproval(rpcId, sid, approvalId, true) }.start()
                appendSystemBubble("→ 已允许一次")
            }
            .setNegativeButton("拒绝") { _, _ ->
                Thread { client.respondApproval(rpcId, sid, approvalId, false) }.start()
                appendSystemBubble("→ 已拒绝")
            }
            .setOnCancelListener {
                Thread { client.respondApproval(rpcId, sid, approvalId, false) }.start()
            }
            .show()
    }

    /** 渲染任务/进度视图（session/jobs → TaskView[]）。 */
    private fun renderJobs(jobs: JSONArray) {
        if (jobs.length() == 0) return
        val sb = StringBuilder("🏃 任务:\n")
        for (i in 0 until jobs.length()) {
            val j = jobs.optJSONObject(i) ?: continue
            val label = j.optString("label")
            val status = j.optString("status")
            sb.append("  • $label [$status]\n")
        }
        appendSystemBubble(sb.toString().trimEnd())
    }

    private fun appendSystemBubble(text: String) {
        val tv = TextView(this).apply {
            this.text = text
            setTextColor(0xCCCCCC.toInt())
            setPadding(dp(16), dp(6), dp(16), dp(6))
            textSize = 13f
        }
        transcript.addView(tv)
        ensureScrollBottom()
    }

    /** 处理实时 session/event：文本/推理增量 → 滚动追加。
     *  真实线上格式（地面真值）：assistant/chunk 的 data.chunk = {type, index, text}。
     */
    private fun handleEvent(event: JSONObject) {
        val type = event.optString("type")
        val data = event.optJSONObject("data")
        when (type) {
            "user/message" -> {
                val t = extractText(data)
                if (t != null) appendBubble(t, COL_USER, left = true)
            }
            "assistant/chunk" -> {
                val chunk = data?.optJSONObject("chunk")
                val dt = chunk?.optString("type")
                when (dt) {
                    "text-delta", "reasoning-delta" -> {
                        streamingText.append(chunk.optString("text"))
                        scrollStreamingText()
                    }
                    "tool-call-delta" -> { /* 可显示工具名 */ }
                }
            }
            "assistant/message" -> {
                // 最终完整消息（带 usage 等），结束流
                streamingView?.let { ensureScrollBottom() }
                streamingView = null
                streamingText = StringBuilder()
            }
        }
    }

    private fun scrollStreamingText() {
        if (streamingView == null) {
            streamingView = makeBubble(streamingText.toString(), COL_ASSIST, left = false)
            transcript.addView(streamingView)
        } else {
            (streamingView as TextView).text = streamingText.toString()
        }
        ensureScrollBottom()
    }

    private fun sendPrompt() {
        val sid = sessionId ?: return
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        appendBubble(text, COL_USER, left = true)
        input.setText("")
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", text))
        Thread {
            client.sessionPrompt(sid, "queue", content)
        }.start()
    }

    // ── transcript 渲染辅助 ────────────────────────────────────
    private fun clearTranscript() {
        streamingText = StringBuilder()
        streamingView = null
        transcript.removeAllViews()
    }

    private fun appendBubble(text: String, color: Int, left: Boolean) {
        transcript.addView(makeBubble(text, color, left))
        ensureScrollBottom()
    }

    private fun makeBubble(text: String, color: Int, left: Boolean): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(COL_TEXT)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(color)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(if (left) dp(16) else dp(48), dp(4), if (left) dp(48) else dp(16), dp(4))
            layoutParams = lp
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.NORMAL)
        }

    private fun ensureScrollBottom() {
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** 从 content 块里抽取文本（text 块拼接）。 */
    private fun extractText(data: JSONObject?): String? {
        val content = data?.optJSONArray("content") ?: return null
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.optJSONObject(i) ?: continue
            if (block.optString("type") == "text") sb.append(block.optString("text"))
        }
        return sb.toString().ifEmpty { null }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density + 0.5f).toInt()

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}
