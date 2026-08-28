package com.dshmobile.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.dshmobile.protocol.DshClient
import com.dshmobile.protocol.Models
import com.dshmobile.protocol.Rpc
import org.json.JSONArray
import org.json.JSONObject

/**
 * 原生会话视图：会话列表 + 打开会话 + 发送 prompt + 实时流式渲染 + 工具审批 + 提问 + 任务进度。
 *
 * 事件全部来自 [DshClient] 的 /api/events.mux downlink（须先 client.start()）。
 * 渲染规则（地面真值，见 docs/dsh-protocol.md §4.1）：
 *  - session/event → user/message（用户气泡）、assistant/chunk（增量）、assistant/message（收尾）、
 *    tool/call、tool/result（工具行）
 *  - approval/requested → 允许一次/拒绝（respondApproval）
 *  - question/requested → 选项对话框（respondQuestion，value={sessionId,answer:{answers:[…]}}）
 *  - session/jobs → 状态行原地更新（不刷屏）
 *  - host/* → 全局状态（运行指示、agent-error）
 */
class ConversationActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var client: DshClient
    private var sessionId: String? = null

    private lateinit var transcript: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var titleView: TextView
    private lateinit var input: EditText
    private lateinit var sendBtn: Button
    private var cancelBtn: Button? = null
    private var jobsView: TextView? = null

    // 流式累积
    private var streamingText = StringBuilder()
    private var streamingView: TextView? = null

    // 会话运行状态（host/session-status），决定 prompt 用 queue 还是 steer
    @Volatile private var running = false

    private companion object {
        const val COL_BG = 0xFF14141F.toInt()
        const val COL_PANEL = 0xFF1E1E2E.toInt()
        const val COL_ACCENT = 0xFFE94560.toInt()
        const val COL_USER = 0xFF0F3460.toInt()
        const val COL_ASSIST = 0xFF252537.toInt()
        const val COL_TEXT = 0xFFFFFFFF.toInt()
        const val COL_MUTED = 0xB3FFFFFF.toInt()
        const val COL_TOOL = 0xFF2A2A3D.toInt()

        /** 历史拉取的消息条数（服务端按消息数截尾，返回这些消息的全部事件）。 */
        const val HISTORY_MESSAGES = 40

        const val EV_USER = "user/message"
        const val EV_ASSISTANT = "assistant/message"
        const val EV_CHUNK = "assistant/chunk"
        const val EV_TOOL_CALL = "tool/call"
        const val EV_TOOL_RESULT = "tool/result"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client = (application as DshApp).client ?: run { finish(); return }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COL_BG)
        }

        // 顶栏：标题 + 会话 + 新建 + 模型 + 工作区
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        titleView = TextView(this).apply {
            text = "会话"; textSize = 18f; setTextColor(COL_TEXT)
        }
        titleRow.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun barBtn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label; setTextColor(COL_TEXT); setBackgroundColor(0x33FFFFFF)
            setPadding(dp(6), 0, dp(6), 0)
            setOnClickListener { onClick() }
        }
        titleRow.addView(barBtn("会话") { pickSession() }, LinearLayout.LayoutParams(dp(64), dp(40)))
        titleRow.addView(barBtn("新建") { newSession() }, LinearLayout.LayoutParams(dp(64), dp(40)))
        titleRow.addView(barBtn("模型") { chooseModel() }, LinearLayout.LayoutParams(dp(64), dp(40)))
        titleRow.addView(barBtn("工作区") { browseWorkspaces() }, LinearLayout.LayoutParams(dp(76), dp(40)))
        root.addView(titleRow)

        // transcript
        transcript = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply { addView(transcript) }
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // 输入栏：取消（运行中显示）+ 输入 + 发送
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
            setBackgroundColor(COL_PANEL)
        }
        val cancelBtn = Button(this).apply {
            text = "■"; setTextColor(COL_TEXT); setBackgroundColor(0x66E94560)
            setOnClickListener { cancelRun() }
            visibility = ViewGroup.GONE
        }
        inputRow.addView(cancelBtn, LinearLayout.LayoutParams(dp(52), dp(46)))
        input = EditText(this).apply {
            hint = "输入…"; setTextColor(COL_TEXT); setHintTextColor(COL_MUTED)
            setBackgroundColor(0x33FFFFFF)
        }
        inputRow.addView(input, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(8) })
        sendBtn = Button(this).apply {
            text = "发送"; setTextColor(COL_TEXT); setBackgroundColor(COL_ACCENT)
            setOnClickListener { sendPrompt() }
        }
        inputRow.addView(sendBtn, LinearLayout.LayoutParams(dp(72), dp(46)).apply { leftMargin = dp(8) })
        root.addView(inputRow)

        setContentView(root)
        this.cancelBtn = cancelBtn

        // 事件流（幂等）：未启动则启动；之后订阅分发
        Thread { client.start() }.start()
        subscribeEvents()
        // 打开最近会话
        Thread { refreshSessionsAndOpen() }.start()
    }

    // ── 会话管理 ──────────────────────────────────────────────
    private var sessionCache: List<Models.SessionSummary> = emptyList()

    private fun refreshSessionsAndOpen() {
        val r = client.sessionList()
        if (r is Rpc.Result.Ok) {
            sessionCache = Models.SessionList.fromJson(r.value).items
            ui.post { titleView.text = "会话" }
            sessionCache.firstOrNull { !it.blank }?.let { ui.post { openSession(it.sessionId) } }
        }
    }

    private fun pickSession() {
        if (sessionCache.isEmpty()) { toast("暂无会话"); return }
        val titles = sessionCache.take(15).mapIndexed { i, s ->
            "${if (s.running) "●" else "○"} ${s.title ?: s.sessionId.take(8)}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择会话")
            .setItems(titles) { _, which -> openSession(sessionCache[which].sessionId) }
            .setNegativeButton("刷新") { _, _ -> Thread { refreshSessionsAndOpen() }.start() }
            .show()
    }

    private fun newSession() {
        val cwd = client.lastDescribe?.optString("cwd", null)?.takeIf { it.isNotEmpty() }
        Thread {
            val r = client.sessionCreate(cwd = cwd)
            ui.post {
                if (r is Rpc.Result.Ok) {
                    val sid = r.value?.optString("sessionId") ?: return@post
                    openSession(sid)
                    toast("已新建会话")
                } else toast("新建失败: ${errText(r)}")
            }
        }.start()
    }

    private fun openSession(sid: String) {
        sessionId = sid
        streamingText = StringBuilder()
        streamingView = null
        ui.post {
            titleView.text = "会话 ${sid.take(8)}"
            transcript.removeAllViews()
            jobsView = null
        }
        Thread {
            val r = client.sessionHistory(sid, maxMessages = HISTORY_MESSAGES)
            if (r is Rpc.Result.Ok) {
                val events = r.value?.optJSONArray("events") ?: return@Thread
                ui.post {
                    for (i in 0 until events.length()) {
                        val item = events.optJSONObject(i) ?: continue
                        renderHistory(item.optJSONObject("event"), item.optJSONObject("view"))
                    }
                    ensureScrollBottom()
                }
            }
        }.start()
    }

    // ── 事件订阅与分发 ────────────────────────────────────────
    private fun subscribeEvents() {
        client.onMuxFrame { rpcId, payload ->
            val type = payload.optString("type")
            ui.post {
                when (type) {
                    "session/event" -> {
                        if (payload.optString("sessionId") == sessionId) {
                            payload.optJSONObject("event")?.let { handleEvent(it) }
                        }
                    }
                    "approval/requested" -> {
                        if (payload.optString("sessionId") == sessionId) showApproval(rpcId, payload)
                    }
                    "approval/resolved" -> { /* 服务端确认，无需动作 */ }
                    "question/requested" -> {
                        if (payload.optString("sessionId") == sessionId) showQuestion(rpcId, payload)
                    }
                    "session/jobs" -> renderJobs(payload.optJSONArray("jobs"))
                    "stream/error" -> {
                        payload.optJSONObject("error")?.let {
                            appendSystem("⚠ 流错误: ${it.optString("message", "unknown")}")
                        }
                    }
                }
            }
        }
        client.onHostFrame { _, payload ->
            val type = payload.optString("type")
            ui.post {
                when (type) {
                    "host/session-status" -> {
                        if (payload.optString("sessionId") == sessionId) {
                            running = payload.optBoolean("running", false)
                            updateRunningUi()
                        }
                    }
                    "host/agent-error" -> {
                        val msg = payload.optString("message")
                        if (msg.isNotEmpty()) appendSystem("⚠ $msg")
                    }
                    else -> {}
                }
            }
        }
        client.onStateChange = { state ->
            ui.post { if (state == "reconnecting") toast("连接中断，重连中…") }
        }
    }

    /** 实时事件处理（地面真值：assistant/chunk 的 data.chunk={type,index,text}）。 */
    private fun handleEvent(event: JSONObject) {
        when (event.optString("type")) {
            EV_USER -> {
                extractText(event.optJSONObject("data"))?.let { appendBubble(it, COL_USER, left = true) }
            }
            EV_CHUNK -> {
                val chunk = event.optJSONObject("data")?.optJSONObject("chunk") ?: return
                when (chunk.optString("type")) {
                    "text-delta", "reasoning-delta" -> {
                        streamingText.append(chunk.optString("text"))
                        updateStreaming()
                    }
                }
            }
            EV_ASSISTANT -> finishStreaming()
            EV_TOOL_CALL -> appendToolLine(
                event.optJSONObject("data")?.let { d ->
                    "🔧 ${d.optString("name")} ${firstArgLine(d.optString("arguments"))}"
                } ?: "🔧"
            )
            EV_TOOL_RESULT -> appendToolLine(
                "   ↳ " + extractResultText(event.optJSONObject("data")).take(160)
            )
        }
    }

    /** 历史 entry（event + 可选 host 计算的 view.card）。 */
    private fun renderHistory(event: JSONObject?, view: JSONObject?) {
        event ?: return
        when (event.optString("type")) {
            EV_USER -> extractText(event.optJSONObject("data"))?.let { appendBubble(it, COL_USER, left = true) }
            EV_ASSISTANT -> extractText(event.optJSONObject("data")?.optJSONObject("message"))
                ?.let { appendBubble(it, COL_ASSIST, left = false) }
            EV_TOOL_CALL -> {
                val card = view?.optJSONObject("view")?.optString("card")
                val d = event.optJSONObject("data")
                appendToolLine(card ?: "🔧 ${d?.optString("name")} ${firstArgLine(d?.optString("arguments") ?: "")}")
            }
            EV_TOOL_RESULT -> {
                val card = view?.optJSONObject("view")?.optString("card")
                appendToolLine("   ↳ " + (card ?: extractResultText(event.optJSONObject("data")).take(160)))
            }
        }
    }

    // ── 发送 / 取消 ──────────────────────────────────────────
    private fun sendPrompt() {
        val sid = sessionId ?: run { toast("先选择会话"); return }
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        // 运行中用 steer（插话），空闲用 queue（排队）
        val mode = if (running) "steer" else "queue"
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", text))
        Thread {
            val r = client.sessionPrompt(sid, mode, content)
            if (r is Rpc.Result.Err) ui.post { appendSystem("⚠ 发送失败: ${errText(r)}") }
        }.start()
    }

    private fun cancelRun() {
        val sid = sessionId ?: return
        Thread { client.sessionCancel(sid) }.start()
    }

    private fun updateRunningUi() {
        sendBtn.text = if (running) "插话" else "发送"
        cancelBtn?.visibility = if (running) ViewGroup.VISIBLE else ViewGroup.GONE
        titleView.text = "会话 ${(sessionId ?: "").take(8)} ${if (running) "●" else "○"}"
    }

    // ── 审批 / 提问 ──────────────────────────────────────────
    private fun showApproval(rpcId: String, payload: JSONObject) {
        val sid = payload.optString("sessionId")
        val approvalId = payload.optString("approvalId")
        val toolName = payload.optString("toolName")
        val reason = payload.optString("reason")
        appendSystem("🔐 「$toolName」请求执行${if (reason.isNotEmpty()) "：$reason" else ""}")
        AlertDialog.Builder(this)
            .setTitle("需要批准")
            .setMessage("工具「$toolName」${if (reason.isNotEmpty()) "\n$reason" else ""}")
            .setPositiveButton("允许一次") { _, _ ->
                Thread { client.respondApproval(rpcId, sid, approvalId, true) }.start()
                appendSystem("→ 已允许一次")
            }
            .setNegativeButton("拒绝") { _, _ ->
                Thread { client.respondApproval(rpcId, sid, approvalId, false) }.start()
                appendSystem("→ 已拒绝")
            }
            .setCancelable(true)
            .setOnCancelListener {
                Thread { client.respondApproval(rpcId, sid, approvalId, false) }.start()
            }
            .show()
    }

    /** 提问对话框：question/requested → 每题选项 → respondQuestion（value={sessionId,answer:{answers:[…]}}）。 */
    private fun showQuestion(rpcId: String, payload: JSONObject) {
        val questions = payload.optJSONArray("questions") ?: return
        if (questions.length() == 0) return
        val answers = JSONArray()
        fun ask(index: Int) {
            if (index >= questions.length()) {
                Thread { client.respondQuestion(rpcId, sessionId ?: "", answers) }.start()
                appendSystem("→ 已回答 ${answers.length()} 题")
                return
            }
            val q = questions.optJSONObject(index) ?: return@ask ask(index + 1)
            val options = q.optJSONArray("options")
            val labels = ArrayList<String>()
            if (options != null) for (i in 0 until options.length()) {
                labels.add(options.optJSONObject(i)?.optString("label") ?: continue)
            }
            val header = q.optString("header", q.optString("question"))
            if (labels.isEmpty()) {
                // 无选项：文本输入（custom）
                val et = EditText(this)
                AlertDialog.Builder(this)
                    .setTitle(header)
                    .setView(et)
                    .setPositiveButton("确定") { _, _ ->
                        answers.put(JSONObject().put("id", q.optString("id"))
                            .put("selected", JSONArray())
                            .put("custom", et.text.toString().trim()))
                        ask(index + 1)
                    }
                    .setNegativeButton("取消") { _, _ -> Thread { client.cancelQuestion(rpcId) }.start() }
                    .show()
            } else {
                AlertDialog.Builder(this)
                    .setTitle(header)
                    .setItems(labels.toTypedArray()) { _, which ->
                        answers.put(JSONObject().put("id", q.optString("id"))
                            .put("selected", JSONArray().put(labels[which])))
                        ask(index + 1)
                    }
                    .setNegativeButton("取消") { _, _ -> Thread { client.cancelQuestion(rpcId) }.start() }
                    .show()
            }
        }
        ask(0)
    }

    // ── 渲染辅助 ─────────────────────────────────────────────
    private fun updateStreaming() {
        if (streamingView == null) {
            streamingView = makeBubble(streamingText.toString(), COL_ASSIST, left = false)
            transcript.addView(streamingView)
        } else {
            (streamingView as TextView).text = streamingText.toString()
        }
        ensureScrollBottom()
    }

    private fun finishStreaming() {
        val v = streamingView ?: return
        if (streamingText.isBlank()) {
            transcript.removeView(v) // 空 assistant/message 只承载 usage
        }
        streamingView = null
        streamingText = StringBuilder()
    }

    private fun appendBubble(text: String, color: Int, left: Boolean) {
        transcript.addView(makeBubble(text, color, left))
        ensureScrollBottom()
    }

    private fun appendToolLine(text: String) {
        transcript.addView(TextView(this).apply {
            this.text = text
            setTextColor(COL_MUTED); setBackgroundColor(COL_TOOL)
            textSize = 12f; typeface = Typeface.MONOSPACE
            setPadding(dp(10), dp(4), dp(10), dp(4))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(16), dp(1), dp(16), dp(1)) }
        })
        ensureScrollBottom()
    }

    private fun appendSystem(text: String) {
        transcript.addView(TextView(this).apply {
            this.text = text
            setTextColor(0xCCCCCC.toInt())
            textSize = 13f
            setPadding(dp(16), dp(6), dp(16), dp(6))
        })
        ensureScrollBottom()
    }

    /** jobs 状态行：原地更新，不刷屏。 */
    private fun renderJobs(jobs: JSONArray?) {
        jobs ?: return
        if (jobs.length() == 0) {
            jobsView?.let { transcript.removeView(it); jobsView = null }
            return
        }
        val sb = StringBuilder()
        for (i in 0 until jobs.length()) {
            val j = jobs.optJSONObject(i) ?: continue
            sb.append("${j.optString("status")} · ${j.optString("label")}\n")
        }
        if (jobsView == null) {
            jobsView = TextView(this).apply {
                setTextColor(0x9FD49F.toInt()); textSize = 12f
                setPadding(dp(16), dp(4), dp(16), dp(4))
                transcript.addView(this)
            }
        }
        jobsView?.text = sb.toString().trimEnd()
        ensureScrollBottom()
    }

    private fun makeBubble(text: String, color: Int, left: Boolean): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(COL_TEXT); setBackgroundColor(color)
            textSize = 15f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(if (left) dp(16) else dp(48), dp(4), if (left) dp(48) else dp(16), dp(4)) }
        }

    private fun ensureScrollBottom() { scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) } }

    /** user/message: data.content[]；assistant/message: data.message.content[]。 */
    private fun extractText(data: JSONObject?): String? {
        val content = data?.optJSONArray("content") ?: return null
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val b = content.optJSONObject(i) ?: continue
            if (b.optString("type") == "text") sb.append(b.optString("text"))
        }
        return sb.toString().ifEmpty { null }
    }

    /** tool/result: data.message.content[0].content[]（tool-result 块的内部 blocks）。 */
    private fun extractResultText(data: JSONObject?): String {
        val inner = data?.optJSONObject("message")?.optJSONArray("content")
            ?.optJSONObject(0)?.optJSONArray("content") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until inner.length()) {
            val b = inner.optJSONObject(i) ?: continue
            if (b.optString("type") == "text") sb.append(b.optString("text"))
        }
        return sb.toString().ifEmpty { "" }
    }

    /** tool/call 的 arguments 是 JSON 字符串；取 description 或 command 首行。 */
    private fun firstArgLine(arguments: String): String {
        if (arguments.isBlank()) return ""
        return try {
            val o = JSONObject(arguments)
            val d = o.optString("description")
            if (d.isNotEmpty()) d else o.optString("command", o.optString("path", "")).lineSequence().firstOrNull() ?: ""
        } catch (_: Exception) { arguments.take(60) }
    }

    // ── 模型 / 工作区 ────────────────────────────────────────
    private fun chooseModel() {
        val sid = sessionId ?: run { toast("先选择会话"); return }
        Thread {
            val r = client.sessionModels(sid)
            ui.post {
                if (r !is Rpc.Result.Ok) { toast("获取模型失败: ${errText(r)}"); return@post }
                val m = Models.SessionModels.fromJson(r.value)
                val flat = ArrayList<Pair<String, Models.CatalogModel>>()
                for (g in m.groups) for (mdl in g.models) flat.add(g.id to mdl)
                if (flat.isEmpty()) { toast("无可用模型"); return@post }
                val current = m.current?.model
                AlertDialog.Builder(this)
                    .setTitle("选择模型（当前 ${current ?: "-"}）")
                    .setItems(flat.map { it.second.name }.toTypedArray()) { _, which ->
                        val p = flat[which]
                        Thread { client.sessionSelectModel(sid, p.first, p.second.id) }.start()
                        toast("已选: ${p.second.name}")
                    }
                    .show()
            }
        }.start()
    }

    private fun browseWorkspaces() {
        Thread {
            val r = client.workspaceList()
            ui.post {
                if (r !is Rpc.Result.Ok) { toast("获取工作区失败: ${errText(r)}"); return@post }
                val list = Models.WorkspaceList.fromJson(r.value)
                if (list.items.isEmpty()) { toast("暂无工作区"); return@post }
                AlertDialog.Builder(this)
                    .setTitle("选择工作区")
                    .setItems(list.items.map { "${it.title}  (${it.path})" }.toTypedArray()) { _, which ->
                        val sid = list.items[which].sessionIds.firstOrNull()
                        if (sid != null) openSession(sid) else toast("该工作区无会话")
                    }
                    .show()
            }
        }.start()
    }

    private fun errText(r: Rpc.Result) = (r as? Rpc.Result.Err)?.error?.display ?: "未知错误"
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(n: Int) = (n * resources.displayMetrics.density + 0.5f).toInt()
}
