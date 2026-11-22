// SPDX-License-Identifier: GPL-3.0-only
// 小 AI 的轻量事件通道：其他组件（例如热评气泡）在这里打点，AI 收到后做一次 notifying 反应。
package io.github.currencortex.music.ui.component

import kotlinx.coroutines.flow.MutableStateFlow

object BotSignals {
    /** 每 +1 表示「有事情通知 AI」，AI 弹一下 */
    val notifyTick = MutableStateFlow(0)

    fun ping() { notifyTick.value = notifyTick.value + 1 }

    /** 热评内容：非空时小 AI 会长成气泡并显示这段文字 */
    val bubbleText = MutableStateFlow<String?>(null)
}
