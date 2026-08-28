package com.dshmobile.protocol

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * DeepSeek Harness 原生线上协议客户端（Kotlin，Android / JVM）。
 *
 * 完全对应 docs/dsh-protocol.md §0/§2/§4：
 *  - unary / respond：HTTP POST `/api/<method>`（client-request → server-response）
 *  - 事件流：`/api/events.mux`、`/api/events.host` 两个 downlink-only WebSocket
 *    （每帧一个 server-request JSON，payload 为 MuxFrame / HostFrame）
 *  - 就绪握手：host.describe 成功 → connected；失败按指数退避重连。
 *
 * 线程约定：[start] 必须在后台线程调用（内部同步发起 host.describe）；
 * 事件回调在 OkHttp 的 WS 线程触发，UI 侧需自行 post 到主线程。
 *
 * @param baseUrl 例如 http://127.0.0.1:3080 或 http://192.168.1.100:3080
 */
class DshClient(baseUrl: String) {

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        const val BACKOFF_BASE_MS = 500L
        const val BACKOFF_MAX_MS = 10_000L
    }

    private val base = baseUrl.toHttpUrl()
    private val http: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)      // unary 超时；WS 由 pingInterval 保活
        .writeTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)     // 双 WebSocket keepalive
        .build()

    private val running = AtomicBoolean(false)
    private val backoff = AtomicInteger(0)
    private var muxWs: WebSocket? = null
    private var hostWs: WebSocket? = null

    /** 状态机：connecting / connected / reconnecting */
    @Volatile var onStateChange: ((String) -> Unit)? = null

    /** 就绪握手成功回传 host.describe value。 */
    @Volatile var onConnected: ((JSONObject?) -> Unit)? = null

    /** host.describe 的最近一次 value（供“新建会话”取 cwd 等）。 */
    @Volatile var lastDescribe: JSONObject? = null
        private set

    // MuxFrame / HostFrame 分发监听（协议文档 §4）。
    // 回调携带 (envelope rpcId, payload)：rpcId 用于回 server-request（approval/question → /api/respond）。
    private val muxListeners = CopyOnWriteArrayList<(String, JSONObject) -> Unit>()
    private val hostListeners = CopyOnWriteArrayList<(String, JSONObject) -> Unit>()

    /** 订阅 session 事件帧（MuxFrame）。回调参数：(envelopeRpcId, payload)。 */
    fun onMuxFrame(fn: (String, JSONObject) -> Unit) { muxListeners.add(fn) }

    /** 订阅全局状态帧（HostFrame）。回调参数：(envelopeRpcId, payload)。 */
    fun onHostFrame(fn: (String, JSONObject) -> Unit) { hostListeners.add(fn) }

    // ── 连接控制 ──────────────────────────────────────────────
    /** 开启连接：建立两个 downlink WebSocket 并做就绪握手（失败重试）。幂等；须在后台线程调用。 */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        backoff.set(0)
        openEventSockets()
        handshakeLoop()
    }

    /** 停止连接并释放。 */
    fun stop() {
        running.set(false)
        muxWs?.close(1000, null)
        hostWs?.close(1000, null)
    }

    fun close() = stop()

    private fun openEventSockets() {
        muxWs?.cancel(); hostWs?.cancel()
        muxWs = http.newWebSocket(Request.Builder().url(eventUrl("/api/events.mux")).build(), Downlink(muxListeners))
        hostWs = http.newWebSocket(Request.Builder().url(eventUrl("/api/events.host")).build(), Downlink(hostListeners))
    }

    /** 同步就绪握手：成功 → connected；失败 → 退避后重开套接字再试，直到 stop()。 */
    private fun handshakeLoop() {
        while (running.get()) {
            onStateChange?.invoke("connecting")
            val desc = hostDescribe()
            if (!running.get()) return
            if (desc is Rpc.Result.Ok) {
                backoff.set(0)
                lastDescribe = desc.value
                onStateChange?.invoke("connected")
                onConnected?.invoke(desc.value)
                return
            }
            onStateChange?.invoke("reconnecting")
            val n = backoff.incrementAndGet()
            var cap = BACKOFF_BASE_MS * Math.pow(2.0, (n - 1).coerceAtLeast(0).toDouble()).toLong()
            cap = cap.coerceAtMost(BACKOFF_MAX_MS)
            val delay = cap / 2 + (Math.random() * cap / 2).toLong()
            try { Thread.sleep(delay) } catch (_: InterruptedException) { return }
            if (!running.get()) return
            openEventSockets()
        }
    }

    /** 生成 ws:// 或 wss:// 的 downlink URL。 */
    private fun eventUrl(path: String): String {
        val scheme = if (base.scheme == "https") "wss" else "ws"
        val defaultPort = if (base.scheme == "https") 443 else 80
        val port = if (base.port == defaultPort) "" else ":${base.port}"
        return "$scheme://${base.host}$port$path"
    }

    // ── 数据传输（protocol §2.1）──────────────────────────────
    /** unary RPC：POST /api/<method>，body=client-request，校验 rpcId 回显。须在后台线程调用。 */
    fun callUnary(method: String, payload: JSONObject? = null): Rpc.Result {
        val rpcId = UUID.randomUUID().toString()
        val body = Rpc.clientRequest(rpcId, method, payload).toRequestBody(JSON_MEDIA)
        val url = base.newBuilder().encodedPath("/api/$method").build()
        val request = Request.Builder().url(url).method("POST", body).build()
        try {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return Rpc.Result.Err(Rpc.Error("internal", "transport failure for $method: HTTP ${resp.code}"))
                }
                val text = resp.body?.string() ?: return Rpc.Result.Err(Rpc.Error("internal", "empty response body"))
                val full = Rpc.parseEnvelope(text)
                if (full.rpcId != rpcId) {
                    return Rpc.Result.Err(Rpc.Error("internal", "rpcId mismatch for $method"))
                }
                return full.result ?: Rpc.Result.Err(Rpc.Error("internal", "missing result in server-response"))
            }
        } catch (e: Exception) {
            return Rpc.Result.Err(Rpc.Error("internal", "transport failure for $method: ${e.message ?: e.javaClass.simpleName}"))
        }
    }

    /** 回 server-request（client-response，POST /api/respond，result.ok=true）。须在后台线程调用。 */
    fun respond(rpcId: String, value: JSONObject? = null): Rpc.Result =
        postRespond(rpcId, ok = true, value = value ?: JSONObject())

    /**
     * 回工具审批（protocol §4.3）：对 mux `approval/requested` 帧，用其 envelope rpcId 应答。
     * value = {sessionId, approvalId, outcome: allowed-once|rejected}
     */
    fun respondApproval(rpcId: String, sessionId: String, approvalId: String, approve: Boolean): Rpc.Result {
        val value = JSONObject()
            .put("sessionId", sessionId)
            .put("approvalId", approvalId)
            .put("outcome", if (approve) "allowed-once" else "rejected")
        return respond(rpcId, value)
    }

    /**
     * 回用户提问（protocol §4.1 question/requested）。
     * 真实线上 value（对照 dsh-host-apiproxy questions.schema）：
     *   {sessionId, answer:{answers:[{id, selected:[label…], custom?}]}}
     * @param answers 每个问题的 {id, selected, custom?} 数组
     */
    fun respondQuestion(rpcId: String, sessionId: String, answers: JSONArray): Rpc.Result {
        val value = JSONObject()
            .put("sessionId", sessionId)
            .put("answer", JSONObject().put("answers", answers))
        return respond(rpcId, value)
    }

    /** 取消用户提问：以 result.ok=false 的 client-response 应答。 */
    fun cancelQuestion(rpcId: String): Rpc.Result = postRespond(rpcId, ok = false, value = JSONObject())

    private fun postRespond(rpcId: String, ok: Boolean, value: JSONObject): Rpc.Result {
        val message = JSONObject()
            .put("type", Rpc.CLIENT_RESPONSE)
            .put("rpcId", rpcId)
            .put("result", JSONObject().put("ok", ok).put("value", value))
        val body = message.toString().toRequestBody(JSON_MEDIA)
        val url = base.newBuilder().encodedPath("/api/respond").build()
        val request = Request.Builder().url(url).method("POST", body).build()
        try {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return Rpc.Result.Err(Rpc.Error("internal", "respond transport failure: HTTP ${resp.code}"))
                }
                val text = resp.body?.string() ?: return Rpc.Result.Err(Rpc.Error("internal", "empty respond body"))
                return Rpc.Result.Ok(JSONObject(text))
            }
        } catch (e: Exception) {
            return Rpc.Result.Err(Rpc.Error("internal", "respond transport failure: ${e.message ?: e.javaClass.simpleName}"))
        }
    }

    // ── 方法目录（protocol §3）────────────────────────────────
    fun hostDescribe(): Rpc.Result = callUnary("host.describe", JSONObject())
    fun sessionList(): Rpc.Result = callUnary("session.list", JSONObject())
    fun sessionSearch(query: String): Rpc.Result = callUnary("session.search", JSONObject().put("query", query))

    /** 创建会话（workspaceId 或 cwd 二选一）。 */
    fun sessionCreate(workspaceId: String? = null, cwd: String? = null, agentPreset: String? = null): Rpc.Result {
        val p = JSONObject()
        workspaceId?.let { p.put("workspaceId", it) }
        cwd?.let { p.put("cwd", it) }
        agentPreset?.let { p.put("agentPreset", it) }
        return callUnary("session.create", p)
    }

    /** 会话历史（分页）：maxMessages 按消息数截尾；beforeSeq 按 seq 向前续读。 */
    fun sessionHistory(sessionId: String, maxMessages: Int? = null, beforeSeq: Long? = null): Rpc.Result {
        val p = JSONObject().put("sessionId", sessionId)
        maxMessages?.let { p.put("maxMessages", it) }
        beforeSeq?.let { p.put("beforeSeq", it) }
        return callUnary("session.history", p)
    }

    fun sessionModels(sessionId: String): Rpc.Result = callUnary("session.models", JSONObject().put("sessionId", sessionId))

    fun sessionSelectModel(sessionId: String, provider: String, model: String, reasoningEffort: String? = null): Rpc.Result {
        val p = JSONObject().put("sessionId", sessionId).put("provider", provider).put("model", model)
        reasoningEffort?.let { p.put("reasoningEffort", it) }
        return callUnary("session.selectModel", p)
    }

    fun sessionRename(sessionId: String, title: String): Rpc.Result =
        callUnary("session.rename", JSONObject().put("sessionId", sessionId).put("title", title))

    fun sessionFork(sessionId: String, atSeq: Long? = null): Rpc.Result {
        val p = JSONObject().put("sessionId", sessionId)
        atSeq?.let { p.put("atSeq", it) }
        return callUnary("session.fork", p)
    }

    /** 发送提示词（mode: queue | steer）。 */
    fun sessionPrompt(sessionId: String, mode: String, content: JSONArray, clientTimeZone: String? = null): Rpc.Result {
        val p = JSONObject().put("sessionId", sessionId).put("mode", mode).put("content", content)
        clientTimeZone?.let { p.put("clientTimeZone", it) }
        return callUnary("session.prompt", p)
    }

    fun sessionCancel(sessionId: String): Rpc.Result = callUnary("session.cancel", JSONObject().put("sessionId", sessionId))

    fun workspaceList(): Rpc.Result = callUnary("workspace.list", JSONObject())
    fun workspaceCreate(path: String): Rpc.Result = callUnary("workspace.create", JSONObject().put("path", path))

    fun subagentList(parentSessionId: String): Rpc.Result =
        callUnary("subagent.list", JSONObject().put("parentSessionId", parentSessionId))

    fun llmModels(): Rpc.Result = callUnary("llm.models", JSONObject())
    fun llmProviders(): Rpc.Result = callUnary("llm.providers", JSONObject())

    /** downlink WebSocket 监听：只收不发；解析 server-request 帧并分发（携带 envelope rpcId）。 */
    private inner class Downlink(
        private val listeners: CopyOnWriteArrayList<(String, JSONObject) -> Unit>
    ) : WebSocketListener() {
        override fun onMessage(socket: WebSocket, text: String) {
            try {
                val enveloped = Rpc.parseEnvelope(text)
                val payload = enveloped.payload ?: return
                listeners.forEach { it(enveloped.rpcId, payload) }
            } catch (_: Exception) {
                // 单帧损坏不杀流（与 Web 端一致）
            }
        }

        override fun onFailure(socket: WebSocket, t: Throwable, response: Response?) {
            // 断流：退避后重开两个套接字（running=false 时静默退出）
            if (!running.get()) return
            if (backoff.compareAndSet(0, 1)) {
                Thread {
                    try { Thread.sleep(BACKOFF_BASE_MS) } catch (_: InterruptedException) { return@Thread }
                    if (running.get()) openEventSockets()
                }.start()
            }
        }
    }
}
