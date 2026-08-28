package com.dshmobile.app

import android.app.Application
import android.webkit.WebView

/**
 * Application 级单例 WebView 持有器。
 *
 * 让 WebView 跨 Activity 实例存活（转屏 / 从最近任务返回），避免重新 loadUrl：
 * dsh web 的前端 bundle 不必重新下载/解析/执行，滚动位置、JS 运行时、会话状态全部保留。
 * 仅进程存活期间有效；进程被系统杀死后仍需重载（无解，可在系统层面用 FGS 保活降低频率）。
 */
class DshApp : Application() {
    var retainedWebView: WebView? = null
}
