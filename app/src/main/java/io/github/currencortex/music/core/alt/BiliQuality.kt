// SPDX-License-Identifier: GPL-3.0-only
// B站 取流画质偏好：视频模式里切换画质后，下一次解析音/视频源都用这个 qn。
package io.github.currencortex.music.core.alt

object BiliQuality {
    /** 16=360P 32=480P 64=720P 80=1080P（与 playurl 的 qn 一致） */
    @Volatile var preferredQn: Int = 80

    fun label(qn: Int): String = when (qn) {
        16 -> "360P"; 32 -> "480P"; 64 -> "720P"; 80 -> "1080P"; else -> "自动"
    }
}
