// SPDX-License-Identifier: GPL-3.0-only
// PromX 小 AI 本体：常驻右上角的一只小生物。
// 形制参考 grok-icon-study（逆向的 Grok 图标动画）：圆润身体 + 一对会追随的眼睛 + 呼吸与眨眼。
// 物理用 Compose 的 spring，状态留了接口给下一步（播放律动 / 加载 / 出错）。
package io.github.currencortex.music.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

enum class CreatureMood { IDLE, LISTENING, THINKING, ERROR }

/**
 * @param mood 留作状态联动：IDLE 呼吸 + 慢漂移；LISTENING 眼睛更活；THINKING 眯眼；ERROR 抖动
 */
@Composable fun MiniAiCreature(
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    mood: CreatureMood = CreatureMood.IDLE,
    tint: Color = Color(0xFF5877FF),
    onTap: () -> Unit = {},
) {
    // 呼吸：4.2 秒一轮，幅度 1 → 1.035
    val breath by rememberInfiniteTransition(label = "breath").animateFloat(
        initialValue = 1f, targetValue = 1.035f,
        animationSpec = infiniteRepeatable(tween(2100, easing = LinearEasing), RepeatMode.Reverse),
        label = "breathScale",
    )
    // 眨眼：每 3–6 秒一次
    var blink by remember { mutableStateOf(1f) }
    LaunchedEffect(mood) {
        while (true) {
            delay(Random.nextLong(2600, 6200))
            blink = 0.08f
            delay(90)
            blink = 1f
        }
    }
    // 瞳孔漂移：随心情改变速度与半径
    val drift = rememberInfiniteTransition(label = "drift").animateFloat(
        initialValue = 0f, targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            tween(if (mood == CreatureMood.LISTENING) 3200 else 5200, easing = LinearEasing)),
        label = "driftAngle",
    )
    val wobble = rememberInfiniteTransition(label = "wobble").animateFloat(
        initialValue = 0f, targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(5600, easing = LinearEasing)), label = "wobbleAngle")

    Canvas(
        modifier.size(size).testTag("mini_ai_creature")
            .let { m -> m }, // tap 由外层处理，避免与拖拽冲突
    ) {
        val w = this.size.width
        val h = this.size.height
        val cx = w / 2f
        val cy = h / 2f
        val r = minOf(w, h) * 0.5f * breath

        // 身体：带轻微起伏的圆（用四段贝塞尔做出“软”感）
        val body = Path().apply {
            val squash = 1f + 0.02f * sin(wobble.value)
            moveTo(cx, cy - r * squash)
            cubicTo(cx + r * .55f, cy - r * squash, cx + r, cy - r * .55f, cx + r, cy)
            cubicTo(cx + r, cy + r * .55f, cx + r * .55f, cy + r * squash, cx, cy + r * squash)
            cubicTo(cx - r * .55f, cy + r * squash, cx - r, cy + r * .55f, cx - r, cy)
            cubicTo(cx - r, cy - r * .55f, cx - r * .55f, cy - r * squash, cx, cy - r * squash)
            close()
        }
        drawPath(body, tint)

        // 眼睛：被扰动的白眼 + 跟随漂移的瞳孔
        val eyeDx = r * .30f
        val eyeDy = -r * .08f
        val eyeW = r * .30f
        val eyeH = r * .34f * blink * (if (mood == CreatureMood.THINKING) .55f else 1f)
        val pupilR = eyeW * .46f
        val angle = drift.value
        val travel = r * (if (mood == CreatureMood.LISTENING) .075f else .05f)
        val px = cos(angle) * travel
        val py = sin(angle) * travel

        listOf(-eyeDx, eyeDx).forEach { ox ->
            drawOval(
                color = Color(0xFFF7F5EF),
                topLeft = Offset(cx + ox - eyeW / 2f, cy + eyeDy - eyeH / 2f),
                size = Size(eyeW, eyeH),
            )
            drawCircle(
                color = Color(0xFF11332E),
                radius = pupilR,
                center = Offset(cx + ox + px, cy + eyeDy + py),
            )
        }
    }
}
