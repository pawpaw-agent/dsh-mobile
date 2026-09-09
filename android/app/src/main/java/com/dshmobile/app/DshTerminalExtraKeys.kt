package com.dshmobile.app

import android.view.View
import com.termux.shared.terminal.io.TerminalExtraKeys
import com.termux.view.TerminalView

/**
 * dsh-mobile 版 [TerminalExtraKeys]：复用 Termux 官方 extra-keys 派发，
 * 仅拦截非终端键：
 *  - KEYBOARD —— 收起/唤出软键盘（Termux 同款行为）
 *  - A+ / A-  —— 调整终端字号
 * 其余键（ESC/TAB/CTRL/ALT/方向键/HOME/END/PGUP/PGDN/ENTER/普通字符）交给
 * Termux 原实现（KeyEvent → TerminalView.onKeyDown / inputCodePoint，
 * CTRL/ALT 状态经 readControlKey/readAltKey 从 ExtraKeysView 读取）。
 */
class DshTerminalExtraKeys(
    terminalView: TerminalView,
    private val onToggleKeyboard: () -> Unit,
    private val onAdjustFont: (Int) -> Unit
) : TerminalExtraKeys(terminalView) {

    override fun onTerminalExtraKeyButtonClick(
        view: View, key: String,
        ctrlDown: Boolean, altDown: Boolean, shiftDown: Boolean, fnDown: Boolean
    ) {
        when (key) {
            "KEYBOARD" -> onToggleKeyboard()
            "A+" -> onAdjustFont(2)
            "A-" -> onAdjustFont(-2)
            else -> super.onTerminalExtraKeyButtonClick(view, key, ctrlDown, altDown, shiftDown, fnDown)
        }
    }
}
