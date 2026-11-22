// SPDX-License-Identifier: GPL-3.0-only
// Prom 图标集：取自 Hugeicons（PickUI「挑组件」第 3 期收录的图标库，个人使用免费），
// 用 SVG path 直接构建 Compose ImageVector，保证 24dp 网格与 1.6 描边一致。
package io.github.currencortex.music.ui.component

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

object PromIcons {
    private fun svg(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f).apply {
            paths.forEach { d ->
                addPath(
                    pathData = addPathNodes(d),
                    fill = null,
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.6f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    /** 首页：圆角房子（Hugeicons home-01） */
    val House: ImageVector = svg("PromHouse",
        "M3 11.99v2.51c0 3.3 0 4.95 1.025 5.975S6.7 21.5 10 21.5h4c3.3 0 4.95 0 5.975-1.025S21 17.8 21 14.5v-2.51c0-1.682 0-2.522-.356-3.25s-1.02-1.244-2.346-2.276l-2-1.555C14.233 3.303 13.2 2.5 12 2.5s-2.233.803-4.298 2.409l-2 1.555C4.376 7.496 3.712 8.062 3.356 8.79S3 10.308 3 11.99Z",
        "M9 17.5h6",
    )

    /** 发现：罗盘（Hugeicons compass） */
    val Compass: ImageVector = svg("PromCompass",
        "M12 3.5V2m-2 0h4",
        "M14.773 10.257c.733.727-.44 6.636-1.644 6.74c-1.01.088-1.325-1.904-1.538-2.536c-.21-.623-.443-.847-1.061-1.052c-1.57-.519-2.355-.779-2.51-1.19c-.412-1.089 5.816-2.89 6.753-1.962Z",
        "M12 21.5a10 10 0 1 0 0-20a10 10 0 0 0 0 20Z",
    )

    /** 我的：人像（Hugeicons user） */
    val Person: ImageVector = svg("PromPerson",
        "M20 21a7.53 7.53 0 0 0-7.005-6.934L12 14q-.531.015-1 .038c-3.7.181-6.716 3.268-7 6.962",
        "M12 11a3.5 3.5 0 1 0 0-7a3.5 3.5 0 0 0 0 7Z",
    )

    /** 设置：齿轮（Hugeicons settings-01，简化为外圈 + 中心） */
    val Gear: ImageVector = svg("PromGear",
        "M12 15.5a3.5 3.5 0 1 0 0-7a3.5 3.5 0 0 0 0 7Z",
        "M19.5 12c0-.5-.05-1-.15-1.47l1.6-1.13-1.9-3.3-1.83.86a7.4 7.4 0 0 0-2.55-1.47L14.4 3.5h-4.8l-.27 1.99a7.4 7.4 0 0 0-2.55 1.47L4.95 6.1l-1.9 3.3 1.6 1.13a7.6 7.6 0 0 0 0 2.94l-1.6 1.13 1.9 3.3 1.83-.86a7.4 7.4 0 0 0 2.55 1.47l.27 1.99h4.8l.27-1.99a7.4 7.4 0 0 0 2.55-1.47l1.83.86 1.9-3.3-1.6-1.13c.1-.47.15-.97.15-1.47Z",
    )

    /** 搜索：放大镜（Hugeicons search-01） */
    val Search: ImageVector = svg("PromSearch",
        "m17 17l4 4m-2-10a8 8 0 1 0-16 0a8 8 0 0 0 16 0",
    )

    /** 喜欢：心形（Hugeicons heart） */
    val Heart: ImageVector = svg("PromHeart",
        "M10.41 19.968C7.59 17.858 2 13.035 2 8.694C2 5.826 4.105 3.5 7 3.5c1.5 0 3 .5 5 2.5c2-2 3.5-2.5 5-2.5c2.895 0 5 2.326 5 5.194c0 4.34-5.59 9.164-8.41 11.274c-.95.71-2.23.71-3.18 0",
    )

    /** 音符（Hugeicons music-note-01） */
    val MusicNote: ImageVector = svg("PromMusicNote",
        "M7 9.5a2.5 2.5 0 1 1-5 0a2.5 2.5 0 0 1 5 0m0 0V2c.333.5.6 2.6 3 3",
        "M13 19.5V11c0-.91 0-1.365.247-1.648c.246-.282.747-.35 1.748-.487c3.014-.411 5.206-1.667 6.375-2.436c.28-.184.42-.276.525-.22s.105.223.105.554v11.163",
        "M18 19.5a2.5 2.5 0 1 1-5 0a2.5 2.5 0 0 1 5 0Z",
    )
}
