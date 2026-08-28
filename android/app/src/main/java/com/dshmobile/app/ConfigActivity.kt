package com.dshmobile.app

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.dshmobile.protocol.DshClient
import com.dshmobile.protocol.Rpc
import org.json.JSONArray
import org.json.JSONObject

/**
 * 原生配置管理页：模型提供方 / 模型列表 / 设置命名空间 / 凭据 / Agent Preset。
 *
 * 注意：settings.*、credentials.*、llm.discoverModels 属于 DSH 配置平面，
 * 仅本机回环可访问（PRIVILEGED_METHODS）。通过 App 内置 SSH 隧道访问
 * http://127.0.0.1:<localPort> 时这些接口可用；直连局域网时可能返回 403。
 */
class ConfigActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var client: DshClient
    private lateinit var content: LinearLayout
    private lateinit var statusView: TextView

    private companion object {
        const val COL_BG = 0xFF14141F.toInt()
        const val COL_PANEL = 0xFF1E1E2E.toInt()
        const val COL_ACCENT = 0xFFE94560.toInt()
        const val COL_TEXT = 0xFFFFFFFF.toInt()
        const val COL_MUTED = 0xB3FFFFFF.toInt()
        const val COL_INPUT_BG = 0x33FFFFFF.toInt()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client = (application as DshApp).client ?: run { finish(); return }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COL_BG)
        }

        val titleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        titleRow.addView(TextView(this).apply {
            text = "配置"; textSize = 18f; setTextColor(COL_TEXT)
        }, LinearLayout.LayoutParams(dp(64), ViewGroup.LayoutParams.WRAP_CONTENT))
        fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label; setTextColor(COL_TEXT); setBackgroundColor(0x33FFFFFF)
            setOnClickListener { onClick() }
        }
        titleRow.addView(btn("模型", { showModels() }), LinearLayout.LayoutParams(dp(68), dp(40)))
        titleRow.addView(btn("提供方", { showProviders() }), LinearLayout.LayoutParams(dp(68), dp(40)))
        titleRow.addView(btn("设置", { showSettings() }), LinearLayout.LayoutParams(dp(68), dp(40)))
        titleRow.addView(btn("凭据", { showCredentials() }), LinearLayout.LayoutParams(dp(68), dp(40)))
        titleRow.addView(btn("Preset", { showPresets() }), LinearLayout.LayoutParams(dp(72), dp(40)))
        titleRow.addView(btn("返回", { finish() }), LinearLayout.LayoutParams(dp(68), dp(40)))
        root.addView(android.widget.HorizontalScrollView(this).apply {
            addView(titleRow)
            isHorizontalScrollBarEnabled = false
        })

        statusView = TextView(this).apply {
            text = "配置平面需通过 SSH 回环访问；直连时设置/凭据类接口可能 403。"
            textSize = 12f; setTextColor(COL_MUTED); gravity = Gravity.CENTER
        }
        root.addView(statusView)

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)
        showOverview()
    }

    private fun showOverview() {
        content.removeAllViews()
        content.addView(sectionTitle("快捷操作"))
        content.addView(actionRow("模型列表", "查看当前可用模型分组", ::showModels))
        content.addView(actionRow("模型提供方", "查看已配置的 LLM Provider", ::showProviders))
        content.addView(actionRow("设置命名空间", "查看/编辑 dsh 设置", ::showSettings))
        content.addView(actionRow("凭据", "查看/设置/删除凭据", ::showCredentials))
        content.addView(actionRow("Agent Preset", "查看和管理预设", ::showPresets))
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 15f; setTextColor(COL_ACCENT)
        setPadding(dp(16), dp(12), dp(16), dp(4))
    }

    private fun actionRow(title: String, desc: String, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(COL_PANEL)
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply { text = title; textSize = 15f; setTextColor(COL_TEXT) })
        col.addView(TextView(this).apply { text = desc; textSize = 12f; setTextColor(COL_MUTED) })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(Button(this).apply {
            text = "查看"; setTextColor(COL_TEXT); setBackgroundColor(COL_ACCENT)
            setOnClickListener { onClick() }
        }, LinearLayout.LayoutParams(dp(72), dp(40)))
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(10), dp(4), dp(10), dp(4)) }
        row.layoutParams = lp
        return row
    }

    // ── LLM 模型 / 提供方 ─────────────────────────────────────
    private fun showModels() {
        Thread {
            val r = client.llmModels()
            ui.post {
                if (r !is Rpc.Result.Ok) { toast("获取模型失败: ${errText(r)}"); return@post }
                val v = r.value ?: JSONObject()
                val groups = v.optJSONArray("groups") ?: JSONArray()
                if (groups.length() == 0) { toast("无模型分组"); return@post }
                val sb = StringBuilder()
                for (i in 0 until groups.length()) {
                    val g = groups.optJSONObject(i) ?: continue
                    sb.append("■ ").append(g.optString("name", g.optString("id"))).append("\n")
                    val models = g.optJSONArray("models") ?: JSONArray()
                    for (j in 0 until models.length()) {
                        val m = models.optJSONObject(j) ?: continue
                        sb.append("   • ").append(m.optString("name", m.optString("id"))).append("\n")
                    }
                }
                showTextDialog("模型", sb.toString().trim())
            }
        }.start()
    }

    private fun showProviders() {
        Thread {
            val r = client.llmProviders()
            ui.post {
                if (r !is Rpc.Result.Ok) { toast("获取提供方失败: ${errText(r)}"); return@post }
                val arr = r.value?.optJSONArray("providers") ?: JSONArray()
                if (arr.length() == 0) { toast("无提供方"); return@post }
                val sb = StringBuilder()
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONObject(i) ?: continue
                    sb.append("● ").append(p.optString("displayName", p.optString("provider")))
                        .append(" [").append(p.optString("provider")).append("]")
                    if (p.optBoolean("active", false)) sb.append(" active")
                    sb.append("\n")
                }
                showTextDialog("模型提供方", sb.toString().trim())
            }
        }.start()
    }

    // ── 设置命名空间 ──────────────────────────────────────────
    private fun showSettings() {
        Thread {
            val r = client.settingsDescribe()
            ui.post {
                if (r !is Rpc.Result.Ok) { toast("获取设置失败: ${errText(r)}"); return@post }
                val v = r.value ?: JSONObject()
                val namespaces = v.optJSONArray("namespaces") ?: JSONArray()
                if (namespaces.length() == 0) { toast("无设置命名空间"); return@post }
                val labels = ArrayList<String>()
                val nsNames = ArrayList<String>()
                for (i in 0 until namespaces.length()) {
                    val n = namespaces.optJSONObject(i) ?: continue
                    nsNames.add(n.optString("ns"))
                    labels.add("${n.optString("ns")}  (${n.optString("applies", "?")})")
                }
                AlertDialog.Builder(this)
                    .setTitle("设置命名空间")
                    .setItems(labels.toTypedArray()) { _, which -> showNamespace(nsNames[which]) }
                    .show()
            }
        }.start()
    }

    private fun showNamespace(ns: String) {
        Thread {
            val r = client.settingsDescribe()
            ui.post {
                if (r !is Rpc.Result.Ok) { toast("获取设置失败: ${errText(r)}"); return@post }
                val namespaces = r.value?.optJSONArray("namespaces") ?: JSONArray()
                var found: JSONObject? = null
                for (i in 0 until namespaces.length()) {
                    val n = namespaces.optJSONObject(i) ?: continue
                    if (n.optString("ns") == ns) { found = n; break }
                }
                if (found == null) { toast("命名空间不存在"); return@post }
                val value = found.optJSONObject("value") ?: JSONObject()
                val et = EditText(this).apply {
                    setText(value.toString(2))
                    hint = "JSON patch / value"
                    setTextColor(COL_TEXT); setHintTextColor(COL_MUTED)
                    setBackgroundColor(COL_INPUT_BG)
                    gravity = Gravity.TOP
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                }
                AlertDialog.Builder(this)
                    .setTitle("设置 $ns")
                    .setView(et)
                    .setPositiveButton("保存(patch)") { _, _ ->
                        val patchText = et.text.toString().trim()
                        if (patchText.isEmpty()) { toast("内容为空"); return@setPositiveButton }
                        val patch = try { JSONObject(patchText) } catch (e: Exception) {
                            toast("JSON 无效"); return@setPositiveButton
                        }
                        Thread {
                            val rr = client.settingsUpdate(ns, patch)
                            ui.post {
                                if (rr is Rpc.Result.Ok) toast("已保存") else toast("保存失败: ${errText(rr)}")
                            }
                        }.start()
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }.start()
    }

    // ── 凭据 ──────────────────────────────────────────────────
    private fun showCredentials() {
        val refEt = EditText(this).apply {
            hint = "凭据名（如 OPENAI_API_KEY）"
            setTextColor(COL_TEXT); setHintTextColor(COL_MUTED); setBackgroundColor(COL_INPUT_BG)
        }
        AlertDialog.Builder(this)
            .setTitle("凭据")
            .setView(refEt)
            .setPositiveButton("描述") { _, _ ->
                val ref = refEt.text.toString().trim()
                if (ref.isEmpty()) { toast("请输入凭据名"); return@setPositiveButton }
                describeCredential(ref)
            }
            .setNeutralButton("设置") { _, _ ->
                val ref = refEt.text.toString().trim()
                if (ref.isEmpty()) { toast("请输入凭据名"); return@setNeutralButton }
                setCredential(ref)
            }
            .setNegativeButton("删除") { _, _ ->
                val ref = refEt.text.toString().trim()
                if (ref.isEmpty()) { toast("请输入凭据名"); return@setNegativeButton }
                Thread {
                    val rr = client.credentialsUnset(ref)
                    ui.post { toast(if (rr is Rpc.Result.Ok) "已删除" else "删除失败: ${errText(rr)}") }
                }.start()
            }
            .show()
    }

    private fun describeCredential(ref: String) {
        val refs = JSONArray().put(ref)
        Thread {
            val r = client.credentialsDescribe(refs)
            ui.post {
                if (r !is Rpc.Result.Ok) { toast("获取凭据失败: ${errText(r)}"); return@post }
                val creds = r.value?.optJSONObject("credentials") ?: JSONObject()
                val c = creds.optJSONObject(ref)
                if (c == null) { toast("无该凭据信息"); return@post }
                val sb = "配置: ${c.optBoolean("configured", false)}\n" +
                    "可写: ${c.optBoolean("writable", false)}\n" +
                    "来源: ${c.optString("source", "-")}"
                showTextDialog("凭据 $ref", sb)
            }
        }.start()
    }

    private fun setCredential(ref: String) {
        val et = EditText(this).apply {
            hint = "凭据值"
            setTextColor(COL_TEXT); setHintTextColor(COL_MUTED); setBackgroundColor(COL_INPUT_BG)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("设置凭据 $ref")
            .setView(et)
            .setPositiveButton("保存") { _, _ ->
                val value = et.text.toString()
                if (value.isEmpty()) { toast("值不能为空"); return@setPositiveButton }
                Thread {
                    val rr = client.credentialsSet(ref, value)
                    ui.post { toast(if (rr is Rpc.Result.Ok) "已保存" else "保存失败: ${errText(rr)}") }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ── Agent Preset ──────────────────────────────────────────
    private fun showPresets() {
        Thread {
            val r = client.agentPresetList()
            ui.post {
                if (r !is Rpc.Result.Ok) { toast("获取 Preset 失败: ${errText(r)}"); return@post }
                val arr = r.value?.optJSONArray("presets") ?: JSONArray()
                if (arr.length() == 0) { toast("无 Preset"); return@post }
                val sb = StringBuilder()
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONObject(i) ?: continue
                    val broken = if (p.optBoolean("broken", false)) " [broken]" else ""
                    sb.append("● ").append(p.optString("name", p.optString("id")))
                        .append(" (").append(p.optString("trust", "?")).append(")")
                        .append(broken).append("\n")
                }
                showTextDialog("Agent Preset", sb.toString().trim())
            }
        }.start()
    }

    // ── 工具 ──────────────────────────────────────────────────
    private fun showTextDialog(title: String, text: String) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 13f; setTextColor(COL_TEXT)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(tv)
            .setPositiveButton("关闭", null)
            .show()
    }

    private fun errText(r: Rpc.Result) = (r as? Rpc.Result.Err)?.error?.display ?: "未知错误"
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(n: Int) = (n * resources.displayMetrics.density + 0.5f).toInt()
}
