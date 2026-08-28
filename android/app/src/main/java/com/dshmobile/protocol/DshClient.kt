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
 *  - 就绪握手：并行开两流 + host.describe，全成才 connected；失败按指数退避重连。
 *
 * @param baseUrl 例如 http://127.0.0.1:3080 或 http://192.168.1.100:3080
 * @param timeoutSeconds unary 默认超时（协议 30s）
 */
class DshClient(baseUrl: String, timeoutSeconds: Long = 30) {

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    private val base = baseUrl.toHttpUrl()
    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // 事件流长连接
        .writeTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)    // keepalive，双 WebSocket 保活
        .build()

    private val running = AtomicBoolean(false)
    private val attempt = AtomicInteger(0)
    private var muxWebSocket: WebSocket? = null
    private var hostWebSocket: WebSocket? = null

    /** 状态机：connected / reconnecting。 */
    @Volatile var onStateChange: ((String) -> Unit)? = null

    /** 就绪握手成功回传 host.describe value。 */
    @Volatile var onConnected: ((JSONObject?) -> Unit)? = null

    // MuxFrame / HostFrame 分发监听（协议文档 §4）
    private val muxListeners = CopyOnWriteArrayList<(JSONObject) -> Unit>()
    private val hostListeners = CopyOnWriteArrayList<(JSONObject) -> Unit>()

    /** 订阅 session 事件帧（MuxFrame）。 */
    fun onMuxFrame(fn: (JSONObject) -> Unit) { muxListeners.add(fn) }

    /** 订阅全局状态帧（HostFrame）。 */
    fun onHostFrame(fn: (JSONObject) -> Unit) { hostListeners.add(fn) }

    // ── 就绪握手 + 重连循环（protocol §2.4）─────────────────────
    /** 开启连接：建立两个 downlink WebSocket 并进入握手/重连循环。幂等。 */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        openEventSockets()
        genLoop()
    }

    /** 停止连接并释放。 */
    fun stop() {
        running.set(false)
        muxWebSocket?.close(1000, null)
        hostWebSocket?.close(1000, null)
    }

    fun close() = stop()

    private fun openEventSockets() {
        muxWebSocket?.close(1000, null)
        hostWebSocket?.close(1000, null)
        muxWebSocket = client.newWebSocket(
            Request.Builder().url(eventUrl("/api/events.mux")).build(),
            DownlinkListener(muxListeners)
        )
        hostWebSocket = client.newWebSocket(
            Request.Builder().url(eventUrl("/api/events.host")).build(),
            DownlinkListener(hostListeners)
        )
    }

    private fun genLoop() {
        if (!running.get()) return
        onStateChange?.invoke("connecting")
        val desc = hostDescribe()
        if (!running.get()) return
        if (desc.isOk) {
            attempt.set(0)
            onStateChange?.invoke("connected")
            onConnected?.invoke((desc as Rpc.Result.Ok).value)
        } else {
            if (!running.get()) return
            onStateChange?.invoke("reconnecting")
            val n = attempt.incrementAndGet()
            var cap = 500L * Math.pow(2.0, Math.max(0, n - 1).toDouble()).toLong()
            cap = Math.min(10_000L, cap)
            val delay = cap / 2 + (Math.random() * cap / 2).toLong()
            Thread.sleep(delay)
            genLoop()
        }
    }

    /** 生成 ws:// 或 wss:// 的 downlink URL。 */
    private fun eventUrl(path: String): String {
        val scheme = if (base.scheme == "https") "wss" else "ws"
        val host = base.host
        val defaultPort = if (base.scheme == "https") 443 else 80
        val port = if (base.port == defaultPort) "" else ":${base.port}"
        return "$scheme://$host$port$path"
    }

    // ── 数据传输（protocol §2.1）──────────────────────────────
    /** unary RPC：POST /api/<method>，body=client-request，校验 rpcId 回显。 */
    fun callUnary(method: String, payload: JSONObject? = null): Rpc.Result {
        val rpcId = UUID.randomUUID().toString()
        val body = Rpc.clientRequest(rpcId, method, payload).toRequestBody(JSON_MEDIA)
        val url = base.newBuilder().encodedPath("/api/$method").build()
        val request = Request.Builder().url(url).method("POST", body).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                return Rpc.Result.Err(Rpc.Error("internal", "transport failure for $method: HTTP ${resp.code}"))
            }
            val full = Rpc.parseEnvelope(resp.body!!.string())
            if (full.rpcId != rpcId) {
                return Rpc.Result.Err(Rpc.Error("internal", "rpcId mismatch for $method: sent $rpcId, got ${full.rpcId}"))
            }
            return full.result ?: Rpc.Result.Err(Rpc.Error("internal", "missing result in server-response"))
        }
    }

    /** 回 server-request（client-response，POST /api/respond）。 */
    fun respond(rpcId: String, value: JSONObject? = null): Rpc.Result {
        val body = Rpc.clientResponse(rpcId, value).toRequestBody(JSON_MEDIA)
        val url = base.newBuilder().encodedPath("/api/respond").build()
        val request = Request.Builder().url(url).method("POST", body).build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                return Rpc.Result.Err(Rpc.Error("internal", "respond transport failure: HTTP ${resp.code}"))
            }
            // accepted: true | false
            return Rpc.Result.Ok(JSONObject(resp.body!!.string()))
        }
    }

    // ── 方法目录（protocol §3）────────────────────────────────
    /** 就绪描述。 */
    fun hostDescribe(): Rpc.Result = callUnary("host.describe", JSONObject())
    /** 会话列表。 */
    fun sessionList(): Rpc.Result = callUnary("session.list", JSONObject())
    /** 搜索会话。 */
    fun sessionSearch(query: String): Rpc.Result = callUnary("session.search", JSONObject().put("query", query))
    /** 创建会话。 */
    fun sessionCreate(workspaceId: String? = null, cwd: String? = null, agentPreset: String? = null): Rpc.Result {
        val p = JSONObject()
        workspaceId?.let { p.put("workspaceId", it) }
        cwd?.let { p.put("cwd", it) }
        agentPreset?.let { p.put("agentPreset", it) }
        return callUnary("session.create", p)
    }
    /** 会话历史。 */
    fun sessionHistory(sessionId: String): Rpc.Result = callUnary("session.history", JSONObject().put("sessionId", sessionId))
    /** 会话可用模型。 */
    fun sessionModels(sessionId: String): Rpc.Result = callUnary("session.models", JSONObject().put("sessionId", sessionId))
    /** 选择模型。 */
    fun sessionSelectModel(sessionId: String, provider: String, model: String, reasoningEffort: String? = null): Rpc.Result {
        val p = JSONObject().put("sessionId", sessionId).put("provider", provider).put("model", model)
        reasoningEffort?.let { p.put("reasoningEffort", it) }
        return callUnary("session.selectModel", p)
    }
    /** 会话重命名。 */
    fun sessionRename(sessionId: String, title: String): Rpc.Result =
        callUnary("session.rename", JSONObject().put("sessionId", sessionId).put("title", title))
    /** 会话分叉。 */
    fun sessionFork(sessionId: String, atSeq: Int? = null): Rpc.Result {
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
    /** 取消会话。 */
    fun sessionCancel(sessionId: String): Rpc.Result = callUnary("session.cancel", JSONObject().put("sessionId", sessionId))

    /** 工作区。 */
    fun workspaceList(): Rpc.Result = callUnary("workspace.list", JSONObject())
    fun workspaceCreate(path: String): Rpc.Result = callUnary("workspace.create", JSONObject().put("path", path))

    /** 子代理。 */
    fun subagentList(parentSessionId: String): Rpc.Result =
        callUnary("subagent.list", JSONObject().put("parentSessionId", parentSessionId))

    /** 模型能力（配置平面相关，远程可能 403）。 */
    fun llmModels(): Rpc.Result = callUnary("llm.models", JSONObject())
    fun llmProviders(): Rpc.Result = callUnary("llm.providers", JSONObject())

    /** downlink WebSocket 监听：只收不发，解析 server-request 帧并按 payload.type 分发。 */
    private inner class DownlinkListener(
        private val listeners: CopyOnWriteArrayList<(JSONObject) -> Unit>
    ) : WebSocketListener() {
        override fun onMessage(socket: WebSocket, text: String) {
            try {
                val enveloped = Rpc.parseEnvelope(text)
                val payload = enveloped.payload ?: return
                listeners.forEach { it(payload) }
            } catch (_: Exception) {
                // 单帧损坏不杀流（与 Web 端一致）
            }
        }

        override fun onOpen(socket: WebSocket, response: Response) {
            // 流 open；就绪握手以 host.describe 成功为准
        }

        override fun onFailure(socket: WebSocket, t: Throwable, response: Response?) {
            // 断流不广播错误，交给控制器重连逻辑
        }

        override fun onClosed(socket: WebSocket, code: Int, reason: String) {
            // 流关闭
        }
    }
}
