package com.dshmobile.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
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
import com.dshmobile.protocol.Models
import com.dshmobile.protocol.Rpc

/**
 * 原生工作区管理页。
 *
 * 对应 docs/dsh-protocol.md §3.4：workspace.list/create/rename/delete/insertBefore、
 * host.listDirectory/createDirectory 等。通过 DshApp 进程级 DshClient 调用 unary RPC。
 */
class WorkspaceActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var client: DshClient
    private lateinit var list: LinearLayout
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
            text = "工作区"; textSize = 18f; setTextColor(COL_TEXT)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        titleRow.addView(Button(this).apply {
            text = "刷新"; setTextColor(COL_TEXT); setBackgroundColor(0x33FFFFFF)
            setOnClickListener { load() }
        }, LinearLayout.LayoutParams(dp(72), dp(40)))
        titleRow.addView(Button(this).apply {
            text = "新建"; setTextColor(COL_TEXT); setBackgroundColor(COL_ACCENT)
            setOnClickListener { createWorkspace() }
        }, LinearLayout.LayoutParams(dp(72), dp(40)))
        titleRow.addView(Button(this).apply {
            text = "返回"; setTextColor(COL_TEXT); setBackgroundColor(0x33FFFFFF)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(72), dp(40)))
        root.addView(titleRow)

        statusView = TextView(this).apply {
            textSize = 13f; setTextColor(COL_MUTED); gravity = Gravity.CENTER
        }
        root.addView(statusView)

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)
        load()
    }

    private fun load() {
        statusView.text = "加载中…"
        Thread {
            val r = client.workspaceList()
            ui.post {
                if (r !is Rpc.Result.Ok) {
                    statusView.text = "加载失败: ${errText(r)}"
                    toast((r as? Rpc.Result.Err)?.error?.display ?: "加载失败")
                    return@post
                }
                render(Models.WorkspaceList.fromJson(r.value))
            }
        }.start()
    }

    private fun render(data: Models.WorkspaceList) {
        list.removeAllViews()
        statusView.text = if (data.items.isEmpty()) "暂无工作区" else "${data.items.size} 个工作区"
        for (w in data.items) list.addView(workspaceRow(w))
    }

    private fun workspaceRow(w: Models.Workspace): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(COL_PANEL)
        }
        col.addView(TextView(this).apply {
            text = w.title.ifBlank { w.path.ifBlank { w.workspaceId } }
            textSize = 16f; setTextColor(COL_TEXT)
        })
        col.addView(TextView(this).apply {
            text = w.path
            textSize = 12f; setTextColor(COL_MUTED)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        col.addView(TextView(this).apply {
            text = "会话: ${w.sessionIds.size} 个"
            textSize = 12f; setTextColor(COL_MUTED)
        })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "打开会话"; setTextColor(COL_TEXT); setBackgroundColor(COL_ACCENT)
            setOnClickListener { openSession(w) }
        }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(6) })
        row.addView(Button(this).apply {
            text = "管理"; setTextColor(COL_TEXT); setBackgroundColor(0x33FFFFFF)
            setOnClickListener { manage(w) }
        }, LinearLayout.LayoutParams(0, dp(40), 1f).apply { leftMargin = dp(6) })
        col.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(10), dp(4), dp(10), dp(4)) }
        col.layoutParams = lp
        return col
    }

    private fun openSession(w: Models.Workspace) {
        if (w.sessionIds.isEmpty()) { toast("该工作区无会话"); return }
        val ids = w.sessionIds
        AlertDialog.Builder(this)
            .setTitle("选择会话")
            .setItems(ids.map { it.take(12) }.toTypedArray()) { _, which ->
                startActivity(
                    Intent(this@WorkspaceActivity, ConversationActivity::class.java)
                        .putExtra("sessionId", ids[which])
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
                finish()
            }
            .show()
    }

    private fun manage(w: Models.Workspace) {
        val options = arrayOf("重命名", "新建会话", "删除")
        AlertDialog.Builder(this)
            .setTitle(w.title.ifBlank { w.path })
            .setItems(options) { _, which ->
                when (which) {
                    0 -> rename(w)
                    1 -> createSessionIn(w)
                    2 -> delete(w)
                }
            }
            .show()
    }

    private fun rename(w: Models.Workspace) {
        val et = EditText(this).apply {
            setText(w.title.ifBlank { w.path })
            setTextColor(COL_TEXT); setHintTextColor(COL_MUTED); setBackgroundColor(COL_INPUT_BG)
        }
        AlertDialog.Builder(this)
            .setTitle("重命名工作区")
            .setView(et)
            .setPositiveButton("保存") { _, _ ->
                val title = et.text.toString().trim()
                if (title.isEmpty()) { toast("名称不能为空"); return@setPositiveButton }
                Thread {
                    val r = client.workspaceRename(w.workspaceId, title)
                    ui.post {
                        if (r is Rpc.Result.Ok) { toast("已重命名"); load() } else toast("重命名失败: ${errText(r)}")
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun delete(w: Models.Workspace) {
        AlertDialog.Builder(this)
            .setTitle("删除工作区")
            .setMessage("确定删除「${w.title.ifBlank { w.path }}」吗？")
            .setPositiveButton("删除") { _, _ ->
                Thread {
                    val r = client.workspaceDelete(w.workspaceId)
                    ui.post {
                        if (r is Rpc.Result.Ok) { toast("已删除"); load() } else toast("删除失败: ${errText(r)}")
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun createWorkspace() {
        val et = EditText(this).apply {
            hint = "工作区目录路径（如 /home/user/projects/demo）"
            setText(client.lastDescribe?.optString("cwd", "") ?: "")
            setTextColor(COL_TEXT); setHintTextColor(COL_MUTED); setBackgroundColor(COL_INPUT_BG)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(16))
            addView(et)
            addView(Button(this@WorkspaceActivity).apply {
                text = "服务端选择目录"
                setTextColor(COL_TEXT); setBackgroundColor(0x33FFFFFF)
                setOnClickListener {
                    Thread {
                        val r = client.hostPickDirectory()
                        ui.post {
                            if (r is Rpc.Result.Ok) {
                                r.value?.optString("path")?.takeIf { it.isNotEmpty() }?.let { et.setText(it) }
                            } else toast("选择失败: ${errText(r)}")
                        }
                    }.start()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(8) })
        }
        AlertDialog.Builder(this)
            .setTitle("创建工作区")
            .setView(layout)
            .setPositiveButton("创建") { _, _ ->
                val path = et.text.toString().trim()
                if (path.isEmpty()) { toast("路径不能为空"); return@setPositiveButton }
                Thread {
                    val r = client.workspaceCreate(path)
                    ui.post {
                        if (r is Rpc.Result.Ok) { toast("已创建"); load() } else toast("创建失败: ${errText(r)}")
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun createSessionIn(w: Models.Workspace) {
        Thread {
            val r = client.sessionCreate(workspaceId = w.workspaceId)
            ui.post {
                if (r is Rpc.Result.Ok) {
                    val sid = r.value?.optString("sessionId") ?: ""
                    toast("已在新会话中创建: ${sid.take(12)}（回会话页查看）")
                } else toast("创建失败: ${errText(r)}")
            }
        }.start()
    }

    private fun errText(r: Rpc.Result) = (r as? Rpc.Result.Err)?.error?.display ?: "未知错误"
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(n: Int) = (n * resources.displayMetrics.density + 0.5f).toInt()
}
