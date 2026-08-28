package com.dshmobile.protocol

import org.json.JSONObject

/**
 * DSH 线上协议的四象限 RPC envelope + 统一错误体。
 *
 * 完全对应 docs/dsh-protocol.md §1。判别字段 `type`：
 *  - client-request   客户端→服务端（POST /api/<method>）
 *  - server-response  服务端→客户端（unary 回包）
 *  - server-request   服务端→客户端（WebSocket downlink 事件帧）
 *  - client-response  客户端→服务端（POST /api/respond，回 server-request）
 *
 * 用 org.json 手工解析，避免额外序列化依赖；与浏览器端 `JSON.parse` 行为一致。
 */
object Rpc {
    const val CLIENT_REQUEST = "client-request"
    const val SERVER_RESPONSE = "server-response"
    const val SERVER_REQUEST = "server-request"
    const val CLIENT_RESPONSE = "client-response"

    /**
     * 统一错误体。`details` 按 [code] 判别，见协议文档 §1.5。
     * 这里保留原始 details 字典（JSONObject），上层按需读取。
     */
    data class Error(val code: String, val message: String, val details: JSONObject? = null) {
        val display: String
            get() = if (message.isBlank()) "$code" else "$code: $message"
    }

    /** 业务结果：ok=true 携带 value；ok=false 携带 error。 */
    sealed class Result {
        data class Ok(val value: JSONObject?) : Result()
        data class Err(val error: Error) : Result()

        val isOk: Boolean get() = this is Ok
    }

    /** 四象限 envelope 的公共字段。 */
    data class Envelope(
        val type: String,
        val rpcId: String,
        val method: String? = null,
        val payload: JSONObject? = null,
        val result: Result? = null
    )

    fun parseResult(o: JSONObject): Result {
        return if (o.optBoolean("ok", false)) {
            Result.Ok(o.optJSONObject("value"))
        } else {
            val e = o.optJSONObject("error") ?: JSONObject()
            Result.Err(
                Error(
                    code = e.optString("code", "internal"),
                    message = e.optString("message", "unknown rpc error"),
                    details = e.optJSONObject("details")
                )
            )
        }
    }

    /**
     * 解析一个传入的服务端消息 JSON。
     * 既用于 unary 回包（server-response），也用于 WebSocket downlink 帧（server-request）。
     */
    fun parseEnvelope(json: String): Envelope {
        val o = JSONObject(json)
        val type = o.optString("type")
        val rpcId = o.optString("rpcId")
        return when (type) {
            SERVER_RESPONSE -> Envelope(
                type = type,
                rpcId = rpcId,
                result = parseResult(o.optJSONObject("result") ?: JSONObject())
            )
            SERVER_REQUEST -> Envelope(
                type = type,
                rpcId = rpcId,
                method = o.optString("method", ""),
                payload = o.optJSONObject("payload")
            )
            else -> Envelope(type = type, rpcId = rpcId)
        }
    }

    /** 构造 client-request JSON（POST /api/<method> 的 body 或 websocket）。 */
    fun clientRequest(rpcId: String, method: String, payload: JSONObject? = null): String {
        return JSONObject()
            .put("type", CLIENT_REQUEST)
            .put("rpcId", rpcId)
            .put("method", method)
            .put("payload", payload ?: JSONObject())
            .toString()
    }

    /** 构造 client-response JSON（POST /api/respond 的 body，回 server-request）。 */
    fun clientResponse(rpcId: String, value: JSONObject? = null): String {
        val result = JSONObject()
            .put("ok", true)
            .put("value", value ?: JSONObject())
        return JSONObject()
            .put("type", CLIENT_RESPONSE)
            .put("rpcId", rpcId)
            .put("result", result)
            .toString()
    }
}
