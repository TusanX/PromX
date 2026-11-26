// SPDX-License-Identifier: GPL-3.0-only
// 哔哩哔哩 动作图标：移植自 Bilight（MIT License, Copyright (c) 2026 Bilight contributors）
// https://github.com/Ownhelm/Bilight — android/app/src/main/kotlin/app/bilight/android/ui/BiliActionIcon.kt
// 只保留了 PromX 用得到的「点赞 / 收藏 / 评论」，颜色改为跟随页面传入（哔哩哔哩 模式用粉色）。
package io.github.currencortex.music.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

enum class BiliActionKind { LIKE, FAVORITE, COMMENT }

/**
 * 原始手绘剪影（与 Bilight 的 iOS 实现同源），可见墨迹落在 22dp 内。
 * @param tint 选中态用品牌粉，未选中用白色半透
 */
@Composable fun BiliActionIcon(
    kind: BiliActionKind,
    label: String,
    tint: Color,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
) {
    Canvas(modifier.size(22.dp).semantics { contentDescription = label }) {
        val color = tint
        val path = Path()
        when (kind) {
            BiliActionKind.LIKE -> {
                path.addRoundRect(RoundRect(Rect(2f, 15f, 8f, 33f), CornerRadius(1.8f)))
                path.moveTo(11f, 15f); path.cubicTo(16f, 9f, 16f, 8f, 18f, 4f)
                path.cubicTo(19f, 0f, 24f, 2f, 24f, 7f); path.lineTo(23f, 14f); path.lineTo(31f, 14f)
                path.quadraticTo(35f, 14f, 34f, 19f); path.lineTo(31f, 30f)
                path.quadraticTo(30f, 33f, 27f, 33f); path.lineTo(11f, 33f); path.close()
            }
            BiliActionKind.FAVORITE -> {
                // 十角星：外/内半径交替，边缘用 quadratic 做出“软”轮廓
                val points = (0..9).map { i ->
                    val angle = i * Math.PI / 5 - Math.PI / 2
                    val r = if (i % 2 == 0) .49f else .255f
                    Offset(18 + cos(angle).toFloat() * r * 36, 18 + sin(angle).toFloat() * r * 36)
                }
                fun mix(a: Offset, b: Offset) = a * .86f + b * .14f
                val start = mix(points[0], points[9]); path.moveTo(start.x, start.y)
                for (i in 0..9) {
                    val next = mix(points[i], points[(i + 1) % 10])
                    path.quadraticTo(points[i].x, points[i].y, next.x, next.y)
                    val end = mix(points[(i + 1) % 10], points[i])
                    path.lineTo(end.x, end.y)
                }
                path.close()
            }
            BiliActionKind.COMMENT -> {
                // 圆角气泡 + 小尾巴
                path.addRoundRect(RoundRect(Rect(3f, 6f, 33f, 27f), CornerRadius(6f)))
                path.moveTo(11f, 26f); path.lineTo(9f, 33f); path.lineTo(18f, 27f); path.close()
            }
        }
        val bounds = path.getBounds()
        if (bounds.width <= 0f || bounds.height <= 0f) return@Canvas
        withTransform({
            scale(size.width / bounds.width, size.height / bounds.height, Offset.Zero)
            translate(-bounds.left, -bounds.top)
        }) {
            if (selected && kind == BiliActionKind.FAVORITE) drawPath(path, color)
            else drawPath(path, color)
        }
    }
}
