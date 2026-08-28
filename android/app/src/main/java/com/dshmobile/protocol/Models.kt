package com.dshmobile.protocol

import org.json.JSONArray
import org.json.JSONObject

/**
 * DSH 协议的核心领域模型（Kotlin 数据类），对照 docs/dsh-protocol.md §3。
 * 每个数据类带一个 [fromJson] 工厂（宽容解析：字段缺失给默认值），供 [Rpc.Result.Ok.value] 解析。
 * 注：value 形如 `items`/`event`/`payload` 的复杂字典，这里提供最常用的会话 / 工作区 / 模型 / 事件帧模型。
 */
object Models {

    /** host.describe value。 */
    data class HostDescribe(
        val version: String,
        val cwd: String,
        val provider: String?,
        val model: String?,
        val attachedSessions: Int,
        val home: String,
        val canOpenPath: Boolean
    ) {
        companion object {
            fun fromJson(o: JSONObject?) = HostDescribe(
                version = o?.optString("version", "") ?: "",
                cwd = o?.optString("cwd", "") ?: "",
                provider = o?.optString("provider").takeUnless { it.isNullOrEmpty() },
                model = o?.optString("model").takeUnless { it.isNullOrEmpty() },
                attachedSessions = o?.optInt("attachedSessions", 0) ?: 0,
                home = o?.optString("home", "") ?: "",
                canOpenPath = o?.optBoolean("canOpenPath", false) ?: false
            )
        }
    }

    /** Session event（宽 envelope，data 为未知）。 */
    data class SessionEvent(
        val type: String,
        val seq: Long,
        val time: Long,
        val data: JSONObject?
    ) {
        companion object {
            fun fromJson(o: JSONObject?) = SessionEvent(
                type = o?.optString("type", "") ?: "",
                seq = o?.optLong("seq", 0L) ?: 0L,
                time = o?.optLong("time", 0L) ?: 0L,
                data = o?.optJSONObject("data")
            )
        }
    }

    /** SessionSummary 行（session.list）。 */
    data class SessionSummary(
        val sessionId: String,
        val updatedAt: Long,
        val running: Boolean,
        val blank: Boolean,
        val parentSessionId: String?,
        val origin: String?,
        val cwd: String?,
        val agentPreset: String?,
        val title: String?,
        val turnCount: Int?,
        val stepCount: Int?
    ) {
        companion object {
            fun fromJson(o: JSONObject?): SessionSummary {
                val p = o?.optJSONObject("projections")?.optJSONObject("values")
                val stats = p?.optJSONObject("sessionStats")
                return SessionSummary(
                    sessionId = o?.optString("sessionId", "") ?: "",
                    updatedAt = o?.optLong("updatedAt", 0L) ?: 0L,
                    running = o?.optBoolean("running", false) ?: false,
                    blank = o?.optBoolean("blank", false) ?: false,
                    parentSessionId = o?.optString("parentSessionId").takeUnless { it.isNullOrEmpty() },
                    origin = o?.optString("origin").takeUnless { it.isNullOrEmpty() },
                    cwd = o?.optString("cwd").takeUnless { it.isNullOrEmpty() },
                    agentPreset = o?.optString("agentPreset").takeUnless { it.isNullOrEmpty() },
                    title = p?.optString("title"),
                    turnCount = stats?.optInt("turns"),
                    stepCount = stats?.optInt("steps")
                )
            }
        }
    }

    /** session.list value。 */
    data class SessionList(val items: List<SessionSummary>) {
        companion object {
            fun fromJson(o: JSONObject?): SessionList {
                val arr = o?.optJSONArray("items") ?: JSONArray()
                val list = ArrayList<SessionSummary>(arr.length())
                for (i in 0 until arr.length()) list.add(SessionSummary.fromJson(arr.optJSONObject(i)))
                return SessionList(list)
            }
        }
    }

    /** WorkspaceView。 */
    data class Workspace(
        val workspaceId: String,
        val path: String,
        val title: String,
        val sessionIds: List<String>,
        val createdAt: String,
        val updatedAt: String
    ) {
        companion object {
            fun fromJson(o: JSONObject?): Workspace {
                val ids = o?.optJSONArray("sessionIds") ?: JSONArray()
                val list = ArrayList<String>(ids.length())
                for (i in 0 until ids.length()) list.add(ids.optString(i))
                return Workspace(
                    workspaceId = o?.optString("workspaceId", "") ?: "",
                    path = o?.optString("path", "") ?: "",
                    title = o?.optString("title", "") ?: "",
                    sessionIds = list,
                    createdAt = o?.optString("createdAt", "") ?: "",
                    updatedAt = o?.optString("updatedAt", "") ?: ""
                )
            }
        }
    }

    /** workspace.list value。 */
    data class WorkspaceList(val items: List<Workspace>, val archivedSessionIds: List<String>) {
        companion object {
            fun fromJson(o: JSONObject?): WorkspaceList {
                val arr = o?.optJSONArray("items") ?: JSONArray()
                val list = ArrayList<Workspace>(arr.length())
                for (i in 0 until arr.length()) list.add(Workspace.fromJson(arr.optJSONObject(i)))
                val archived = o?.optJSONArray("archivedSessionIds") ?: JSONArray()
                val archivedList = ArrayList<String>(archived.length())
                for (i in 0 until archived.length()) archivedList.add(archived.optString(i))
                return WorkspaceList(list, archivedList)
            }
        }
    }

    /** 模型条目（一个 provider group 中的一个模型）。 */
    data class CatalogModel(
        val id: String,
        val name: String,
        val description: String?
    ) {
        companion object {
            fun fromJson(o: JSONObject?) = CatalogModel(
                id = o?.optString("id", "") ?: "",
                name = o?.optString("name", "") ?: "",
                description = o?.optString("description").takeUnless { it.isNullOrEmpty() }
            )
        }
    }

    /** 模型提供方分组。 */
    data class ModelGroup(val id: String, val name: String, val models: List<CatalogModel>) {
        companion object {
            fun fromJson(o: JSONObject?): ModelGroup {
                val arr = o?.optJSONArray("models") ?: JSONArray()
                val list = ArrayList<CatalogModel>(arr.length())
                for (i in 0 until arr.length()) list.add(CatalogModel.fromJson(arr.optJSONObject(i)))
                return ModelGroup(
                    id = o?.optString("id", "") ?: "",
                    name = o?.optString("name", "") ?: "",
                    models = list
                )
            }
        }
    }

    /** session.models value。 */
    data class SessionModels(
        val current: ModelSelection?,
        val routable: Boolean,
        val groups: List<ModelGroup>,
        val failures: List<JSONObject>
    ) {
        companion object {
            fun fromJson(o: JSONObject?): SessionModels {
                val cur = o?.optJSONObject("current")
                val groupsArr = o?.optJSONArray("groups") ?: JSONArray()
                val groups = ArrayList<ModelGroup>(groupsArr.length())
                for (i in 0 until groupsArr.length()) groups.add(ModelGroup.fromJson(groupsArr.optJSONObject(i)))
                val failsArr = o?.optJSONArray("failures") ?: JSONArray()
                val fails = ArrayList<JSONObject>(failsArr.length())
                for (i in 0 until failsArr.length()) fails.add(failsArr.optJSONObject(i) ?: JSONObject())
                return SessionModels(
                    current = cur?.let { ModelSelection.fromJson(it) },
                    routable = o?.optBoolean("routable", false) ?: false,
                    groups = groups,
                    failures = fails
                )
            }
        }
    }

    /** 当前 model selection。 */
    data class ModelSelection(
        val provider: String,
        val model: String,
        val reasoningEffort: String?
    ) {
        companion object {
            fun fromJson(o: JSONObject?) = ModelSelection(
                provider = o?.optString("provider", "") ?: "",
                model = o?.optString("model", "") ?: "",
                reasoningEffort = o?.optString("reasoningEffort").takeUnless { it.isNullOrEmpty() }
            )
        }
    }

    /** 事件帧的通用判别：根据 payload.type 分发。 */
    object Frame {
        // MuxFrame types (protocol §4.1)
        const val SESSION_EVENT = "session/event"
        const val SESSION_SUBSCRIBED = "session/subscribed"
        const val APPROVAL_REQUESTED = "approval/requested"
        const val APPROVAL_RESOLVED = "approval/resolved"
        const val QUESTION_REQUESTED = "question/requested"
        const val QUESTION_RESOLVED = "question/resolved"
        const val SESSION_QUEUE = "session/queue"
        const val SESSION_JOBS = "session/jobs"
        const val SESSION_PROJECTION = "session/projection"
        const val STREAM_ERROR = "stream/error"
        // HostFrame types (protocol §4.2)
        const val HOST_SESSION_ADDED = "host/session-added"
        const val HOST_SESSION_REMOVED = "host/session-removed"
        const val HOST_SESSION_STATUS = "host/session-status"
        const val HOST_AGENT_ERROR = "host/agent-error"
        const val HOST_WORKSPACE_CHANGED = "host/workspace-changed"
        const val HOST_WORKSPACE_REMOVED = "host/workspace-removed"
        const val HOST_WORKSPACE_ORDER_CHANGED = "host/workspace-order-changed"
        const val HOST_ARCHIVED_CHANGED = "host/archived-sessions-changed"
        const val HOST_REMOTE_EVENT = "host/remote-event"
    }
}
