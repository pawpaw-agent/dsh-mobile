package com.dshmobile.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
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
 *  - host 帧族（session-status / agent-error 等）→ 全局状态（运行指示、错误提示）
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
    private var queueView: TextView? = null

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

        const val STATE_SESSION = "sessionId"
        const val STATE_RUNNING = "running"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client = (application as DshApp).client ?: run { finish(); return }
        // 恢复状态（进程回收 / 重建）：当前会话与运行标志
        savedInstanceState?.let {
            sessionId = it.getString(STATE_SESSION)
            running = it.getBoolean(STATE_RUNNING, false)
        }
        // 从工作区/其它入口显式指定要打开的会话（覆盖状态恢复，便于跳转）
        intent?.getStringExtra("sessionId")?.let { sessionId = it }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COL_BG)
        }

        // 顶栏：标题 + 会话 + 新建 + 模型 + 工作区 + 更多
        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        titleView = TextView(this).apply {
            text = "会话"; textSize = 18f; setTextColor(COL_TEXT)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        titleRow.addView(titleView, LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT))
        fun barBtn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label; setTextColor(COL_TEXT); setBackgroundColor(0x33FFFFFF)
            setPadding(dp(4), 0, dp(4), 0)
            setOnClickListener { onClick() }
        }
        titleRow.addView(barBtn("会话") { pickSession() }, LinearLayout.LayoutParams(dp(56), dp(40)))
        titleRow.addView(barBtn("新建") { newSession() }, LinearLayout.LayoutParams(dp(56), dp(40)))
        titleRow.addView(barBtn("模型") { chooseModel() }, LinearLayout.LayoutParams(dp(56), dp(40)))
        titleRow.addView(barBtn("工作区") {
            startActivity(Intent(this@ConversationActivity, WorkspaceActivity::class.java))
        }, LinearLayout.LayoutParams(dp(68), dp(40)))
        titleRow.addView(barBtn("更多") { showMoreMenu() }, LinearLayout.LayoutParams(dp(56), dp(40)))
        root.addView(android.widget.HorizontalScrollView(this).apply {
            addView(titleRow)
            isHorizontalScrollBarEnabled = false
        })

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
            // 单行 + 回车直接发送（软键盘 action）
            setSingleLine(true)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                    sendPrompt(); true
                } else false
            }
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

        if (sessionId != null) {
            // 重建恢复：直接回到原会话，并用 sessionCache 补 running 初始态
            updateRunningUi()
            openSession(sessionId!!)
        } else {
            // 首次进入：打开最近会话
            Thread { refreshSessionsAndOpen() }.start()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.getStringExtra("sessionId")?.let {
            sessionId = it
            openSession(it)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        sessionId?.let { outState.putString(STATE_SESSION, it) }
        outState.putBoolean(STATE_RUNNING, running)
    }

    override fun onDestroy() {
        super.onDestroy()
        // 进程级单例：不持有已销毁的 Activity
        if (isFinishing) {
            client.setMuxListener(null)
            client.setHostListener(null)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 回连接屏而非退出（与 Web 壳一致的导航语义）
        moveTaskToBack(true)
    }

    // ── 会话管理 ──────────────────────────────────────────────
    private var sessionCache: List<Models.SessionSummary> = emptyList()

    private fun refreshSessionCache() {
        Thread {
            val r = client.sessionList()
            if (r is Rpc.Result.Ok) {
                sessionCache = Models.SessionList.fromJson(r.value).items
                ui.post { updateRunningUi() }
            }
        }.start()
    }

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
            "${if (s.running) "●" else "○"} ${s.title ?: s.sessionId.take(6)}"
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
        resetStreaming()
        ui.post {
            titleView.text = "会话 ${sid.take(6)}"
            transcript.removeAllViews()
            jobsView = null
            queueView = null
        }
        // 初始 running 态：session.list 缓存里就有，不必等 host/session-status 帧
        running = sessionCache.firstOrNull { it.sessionId == sid }?.running ?: false
        ui.post { updateRunningUi() }
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
        // 单槽语义：替换旧监听，避免 Activity 重建后重复回调 / 泄漏
        client.setMuxListener { rpcId, payload ->
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
                    "session/queue" -> renderQueue(payload.optJSONArray("items"))
                    "stream/error" -> {
                        payload.optJSONObject("error")?.let {
                            appendSystem("⚠ 流错误: ${it.optString("message", "unknown")}")
                        }
                    }
                }
            }
        }
        client.setHostListener { _, payload ->
            val type = payload.optString("type")
            ui.post {
                when (type) {
                    "host/session-status" -> {
                        if (payload.optString("sessionId") == sessionId) {
                            running = payload.optBoolean("running", false)
                            updateRunningUi()
                        }
                        refreshSessionCache()
                    }
                    "host/session-added", "host/session-removed" -> refreshSessionCache()
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

    // ── 流式缓冲（assistant/chunk 按 content-block index 分块）──
    // 真实 wire：block-start(index,blockType) → text-delta/reasoning-delta(index 同)…
    // → block-end(index, block 完整文本) → 下一个 block(index+1，可为 tool-call)。
    // 同一 step 内 text 与 tool-call 是不同 block，必须按 index 分开渲染。
    private var streamBlocks = HashMap<Int, StringBuilder>()
    private var streamViews = HashMap<Int, TextView>()

    private fun resetStreaming() {
        streamBlocks = HashMap()
        streamViews = HashMap()
        streamingText = StringBuilder()
        streamingView = null
    }

    /** 实时事件处理（chunk 语义对照 docs/dsh-protocol.md 与线上实测）。 */
    private fun handleEvent(event: JSONObject) {
        when (event.optString("type")) {
            EV_USER -> {
                extractText(event.optJSONObject("data"))?.let { appendBubble(it, COL_USER, left = true) }
            }
            EV_CHUNK -> {
                val chunk = event.optJSONObject("data")?.optJSONObject("chunk") ?: return
                when (chunk.optString("type")) {
                    "block-start" -> { /* 新 block：缓冲惰性创建 */ }
                    "text-delta", "reasoning-delta" -> {
                        val idx = chunk.optInt("index", 0)
                        val sb = streamBlocks.getOrPut(idx) { StringBuilder() }
                        sb.append(chunk.optString("text"))
                        updateStreaming(idx)
                    }
                    "block-end" -> {
                        // 服务端给的完整 block 文本：以它为准（覆盖增量累积，防丢字）
                        val idx = chunk.optInt("index", 0)
                        val block = chunk.optJSONObject("block")
                        if (block?.optString("type") == "text") {
                            streamBlocks[idx] = StringBuilder(block.optString("text"))
                            updateStreaming(idx)
                        }
                    }
                    "tool-call-delta" -> {
                        // 工具调用名/参数增量：收到 name 即展示一行
                        val name = chunk.optString("name")
                        if (name.isNotEmpty()) appendToolLine("🔧 $name …")
                    }
                    "finish" -> finishStreaming()
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

    /** 渲染/刷新指定 block 的气泡（每个 text block 一个气泡）。 */
    private fun updateStreaming(idx: Int) {
        val text = streamBlocks[idx]?.toString() ?: return
        if (text.isEmpty()) return
        val v = streamViews[idx]
        if (v == null) {
            val tv = makeBubble(text, COL_ASSIST, left = false)
            streamViews[idx] = tv
            transcript.addView(tv)
        } else {
            v.text = text
        }
        ensureScrollBottom()
    }

    private fun finishStreaming() {
        // 落定所有未完成的 block 气泡（保留内容，断开引用）
        streamViews.clear()
        streamBlocks.clear()
        streamingView = null
        streamingText = StringBuilder()
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
        titleView.text = "会话 ${(sessionId ?: "").take(6)} ${if (running) "●" else "○"}"
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

    /** 队列状态行：原地更新，不刷屏。 */
    private fun renderQueue(items: JSONArray?) {
        items ?: return
        if (items.length() == 0) {
            queueView?.let { transcript.removeView(it); queueView = null }
            return
        }
        val sb = StringBuilder()
        for (i in 0 until items.length()) {
            val it = items.optJSONObject(i) ?: continue
            val msg = it.optJSONObject("message")
            val text = msg?.optJSONArray("content")?.let { c ->
                val b = StringBuilder()
                for (j in 0 until c.length()) {
                    val part = c.optJSONObject(j) ?: continue
                    if (part.optString("type") == "text") b.append(part.optString("text"))
                }
                b.toString()
            } ?: ""
            sb.append("${it.optString("placement", "queued")} · ${text.take(60)}\n")
        }
        if (queueView == null) {
            queueView = TextView(this).apply {
                setTextColor(0xFFD9A05B.toInt()); textSize = 12f
                setPadding(dp(16), dp(4), dp(16), dp(4))
                transcript.addView(this)
            }
        }
        queueView?.text = sb.toString().trimEnd()
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

    private fun choosePreset() {
        val sid = sessionId ?: run { toast("先选择会话"); return }
        Thread {
            val r = client.agentPresetList()
            ui.post {
                if (r !is Rpc.Result.Ok) { toast("获取 Preset 失败: ${errText(r)}"); return@post }
                val presets = r.value?.optJSONArray("presets") ?: JSONArray()
                if (presets.length() == 0) { toast("无 Agent Preset"); return@post }
                val labels = ArrayList<String>()
                val ids = ArrayList<String>()
                for (i in 0 until presets.length()) {
                    val p = presets.optJSONObject(i) ?: continue
                    labels.add("${p.optString("name", p.optString("id"))} (${p.optString("trust", "?")})")
                    ids.add(p.optString("id"))
                }
                AlertDialog.Builder(this)
                    .setTitle("选择 Agent Preset")
                    .setItems(labels.toTypedArray()) { _, which ->
                        Thread { client.agentPresetSelect(sid, ids[which]) }.start()
                        toast("已选择 Preset")
                    }
                    .show()
            }
        }.start()
    }

    // ── 更多菜单：搜索 / 重命名 / fork / 子代理 / 配置 ────────
    private fun showMoreMenu() {
        val options = arrayOf(
            "搜索会话", "重命名当前会话", "Fork 当前会话",
            "子代理", "Agent Preset", "模型/配置管理", "工作区管理"
        )
        AlertDialog.Builder(this)
            .setTitle("更多")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> searchSessions()
                    1 -> renameSession()
                    2 -> forkSession()
                    3 -> showSubagents()
                    4 -> choosePreset()
                    5 -> startActivity(Intent(this@ConversationActivity, ConfigActivity::class.java))
                    6 -> startActivity(Intent(this@ConversationActivity, WorkspaceActivity::class.java))
                }
            }
            .show()
    }

    private fun searchSessions() {
        val et = EditText(this).apply {
            hint = "搜索会话内容…"
            setTextColor(COL_TEXT); setHintTextColor(COL_MUTED); setBackgroundColor(0x33FFFFFF)
        }
        AlertDialog.Builder(this)
            .setTitle("搜索会话")
            .setView(et)
            .setPositiveButton("搜索") { _, _ ->
                val q = et.text.toString().trim()
                if (q.isEmpty()) { toast("输入搜索词"); return@setPositiveButton }
                Thread {
                    val r = client.sessionSearch(q)
                    ui.post {
                        if (r !is Rpc.Result.Ok) { toast("搜索失败: ${errText(r)}"); return@post }
                        val items = r.value?.optJSONArray("items") ?: JSONArray()
                        if (items.length() == 0) { toast("无结果"); return@post }
                        val labels = ArrayList<String>()
                        val sids = ArrayList<String>()
                        for (i in 0 until items.length()) {
                            val it = items.optJSONObject(i) ?: continue
                            labels.add(it.optString("snippet", "").take(120))
                            sids.add(it.optString("sessionId"))
                        }
                        AlertDialog.Builder(this)
                            .setTitle("搜索结果")
                            .setItems(labels.toTypedArray()) { _, which ->
                                openSession(sids[which])
                            }
                            .show()
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun renameSession() {
        val sid = sessionId ?: run { toast("先选择会话"); return }
        val current = sessionCache.firstOrNull { it.sessionId == sid }?.title
        val et = EditText(this).apply {
            setText(current ?: "")
            hint = "会话标题"
            setTextColor(COL_TEXT); setHintTextColor(COL_MUTED); setBackgroundColor(0x33FFFFFF)
        }
        AlertDialog.Builder(this)
            .setTitle("重命名会话")
            .setView(et)
            .setPositiveButton("保存") { _, _ ->
                val title = et.text.toString().trim()
                if (title.isEmpty()) { toast("标题不能为空"); return@setPositiveButton }
                Thread {
                    val r = client.sessionRename(sid, title)
                    ui.post {
                        if (r is Rpc.Result.Ok) {
                            sessionCache = sessionCache.map {
                                if (it.sessionId == sid) it.copy(title = title) else it
                            }
                            toast("已重命名")
                        } else toast("重命名失败: ${errText(r)}")
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun forkSession() {
        val sid = sessionId ?: run { toast("先选择会话"); return }
        Thread {
            val r = client.sessionFork(sid)
            ui.post {
                if (r is Rpc.Result.Ok) {
                    val nsid = r.value?.optString("sessionId") ?: ""
                    if (nsid.isNotEmpty()) {
                        toast("已 fork，打开新会话")
                        openSession(nsid)
                    } else toast("fork 返回为空")
                } else toast("fork 失败: ${errText(r)}")
            }
        }.start()
    }

    // ── 子代理 ────────────────────────────────────────────────
    private fun showSubagents() {
        val sid = sessionId ?: run { toast("先选择会话"); return }
        Thread {
            val r = client.subagentList(sid)
            ui.post {
                if (r !is Rpc.Result.Ok) { toast("获取子代理失败: ${errText(r)}"); return@post }
                val entries = r.value?.optJSONArray("entries") ?: JSONArray()
                if (entries.length() == 0) { toast("暂无子代理"); return@post }
                val labels = ArrayList<String>()
                val ids = ArrayList<String>()
                val modes = ArrayList<String>()
                for (i in 0 until entries.length()) {
                    val e = entries.optJSONObject(i) ?: continue
                    val kind = e.optString("kind")
                    val id = e.optString("id")
                    if (kind != "child") {
                        labels.add("⚠ ${id} (${e.optString("reason", "diagnostic")})")
                        ids.add(id); modes.add("")
                        continue
                    }
                    val label = e.optString("label", id)
                    val activity = e.optString("activity", "inactive")
                    val mode = e.optString("mode", "one-shot")
                    labels.add("${if (activity == "running") "●" else "○"} $label ($mode)")
                    ids.add(id); modes.add(mode)
                }
                AlertDialog.Builder(this)
                    .setTitle("子代理")
                    .setItems(labels.toTypedArray()) { _, which ->
                        if (modes[which].isNotEmpty()) subagentActions(sid, ids[which], modes[which])
                    }
                    .show()
            }
        }.start()
    }

    private fun subagentActions(parentSid: String, childSid: String, mode: String) {
        val options = arrayOf("查看历史", "继续/提示", "打断")
        AlertDialog.Builder(this)
            .setTitle("子代理 ${childSid.take(10)}")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showSubagentHistory(parentSid, childSid, mode)
                    1 -> promptSubagent(parentSid, childSid, mode)
                    2 -> Thread { client.subagentInterrupt(parentSid, childSid, mode) }.start()
                }
            }
            .show()
    }

    private fun showSubagentHistory(parentSid: String, childSid: String, mode: String) {
        Thread {
            val r = client.subagentHistory(parentSid, childSid, mode, maxMessages = 40)
            ui.post {
                if (r !is Rpc.Result.Ok) { toast("获取历史失败: ${errText(r)}"); return@post }
                val events = r.value?.optJSONArray("events") ?: JSONArray()
                val sb = StringBuilder()
                for (i in 0 until events.length()) {
                    val item = events.optJSONObject(i) ?: continue
                    val ev = item.optJSONObject("event") ?: continue
                    val type = ev.optString("type")
                    val data = ev.optJSONObject("data")
                    val text = when (type) {
                        "user/message", "assistant/message" -> {
                            val content = data?.optJSONArray("content")
                            val b = StringBuilder()
                            if (content != null) for (j in 0 until content.length()) {
                                val c = content.optJSONObject(j) ?: continue
                                if (c.optString("type") == "text") b.append(c.optString("text"))
                            }
                            b.toString()
                        }
                        "tool/call" -> "🔧 ${data?.optString("name") ?: ""} ${firstArgLine(data?.optString("arguments") ?: "")}"
                        "tool/result" -> "   ↳ ${extractResultText(data).take(120)}"
                        else -> null
                    }
                    if (!text.isNullOrBlank()) {
                        sb.append(if (type.startsWith("user")) "👤 " else "🤖 ").append(text).append("\n\n")
                    }
                }
                if (sb.isEmpty()) { toast("无历史消息"); return@post }
                val tv = TextView(this).apply {
                    text = sb.toString().trim()
                    textSize = 13f; setTextColor(COL_TEXT)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                }
                val scroll = ScrollView(this).apply { addView(tv) }
                AlertDialog.Builder(this)
                    .setTitle("子代理历史 ${childSid.take(10)}")
                    .setView(scroll)
                    .setPositiveButton("关闭", null)
                    .show()
            }
        }.start()
    }

    private fun promptSubagent(parentSid: String, childSid: String, mode: String) {
        val et = EditText(this).apply {
            hint = "向子代理发送消息…"
            setTextColor(COL_TEXT); setHintTextColor(COL_MUTED); setBackgroundColor(0x33FFFFFF)
        }
        AlertDialog.Builder(this)
            .setTitle("继续子代理")
            .setView(et)
            .setPositiveButton("发送") { _, _ ->
                val text = et.text.toString().trim()
                if (text.isEmpty()) { toast("消息不能为空"); return@setPositiveButton }
                val content = JSONArray().put(JSONObject().put("type", "text").put("text", text))
                Thread {
                    val r = client.subagentPrompt(parentSid, childSid, mode, content)
                    ui.post {
                        if (r is Rpc.Result.Ok) toast("已发送到子代理") else toast("发送失败: ${errText(r)}")
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun errText(r: Rpc.Result) = (r as? Rpc.Result.Err)?.error?.display ?: "未知错误"
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(n: Int) = (n * resources.displayMetrics.density + 0.5f).toInt()

    /** 沉浸式全屏（沿用 WebView 版语义）：内容画到状态栏/导航栏后面。 */
    private fun applyFullscreen() {
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen()
    }
}
