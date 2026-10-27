package io.github.xccxcxxc.runnel.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** 线条风格的图标（自绘路径），24×24 视窗。 */
object RunnelIcons {
    val Back = stroke("back", "M15 4.5 L7.5 12 L15 19.5", width = 2.4f)
    val ChevronRight = stroke("chevron-right", "M9.5 6 L15.5 12 L9.5 18", width = 2.2f)
    val ChevronDown = stroke("chevron-down", "M6 9.5 L12 15.5 L18 9.5", width = 2.2f)
    val ChevronUp = stroke("chevron-up", "M6 14.5 L12 8.5 L18 14.5", width = 2.2f)
    /** 列表从底部上拉的大提示（扁宽的“∨”）。 */
    val PullChevron = stroke("pull-chevron", "M3.5 9 L12 15 L20.5 9", width = 1.5f)
    val PullChevronUp = stroke("pull-chevron-up", "M3.5 15 L12 9 L20.5 15", width = 1.5f)
    val StarOutline = stroke("star-o", STAR)
    val Star = fill("star", STAR)
    val CircleOutline = stroke("circle-o", "M12 6.5 A5.5 5.5 0 1 1 11.99 6.5 Z")
    val Circle = fill("circle", "M12 6.5 A5.5 5.5 0 1 1 11.99 6.5 Z")
    val CheckAll = stroke("check-all", "M2.5 12.5 L7 17 L15.5 7.5", "M11.5 16 L12.5 17 L21 7.5")
    val Check = stroke("check", "M5 12.5 L10 17.5 L19 7")
    val Refresh = stroke("refresh", "M19.5 12 A7.5 7.5 0 1 1 17.3 6.7", "M19.5 3.5 V7.5 H15.5")
    val Settings = stroke(
        "settings",
        "M4 7 H20", "M4 17 H20",
        "M9 7 m-2.2 0 a2.2 2.2 0 1 0 4.4 0 a2.2 2.2 0 1 0 -4.4 0",
        "M15 17 m-2.2 0 a2.2 2.2 0 1 0 4.4 0 a2.2 2.2 0 1 0 -4.4 0",
    )
    val Share = stroke(
        "share", "M12 3.5 V14.5", "M8 7.5 L12 3.5 L16 7.5",
        "M8 10.5 H6.5 A1.5 1.5 0 0 0 5 12 V19 A1.5 1.5 0 0 0 6.5 20.5 H17.5 A1.5 1.5 0 0 0 19 19 V12 A1.5 1.5 0 0 0 17.5 10.5 H16",
    )
    val Compass = stroke("compass", "M12 3 A9 9 0 1 1 11.99 3 Z", "M15.5 8.5 L13.4 13.4 L8.5 15.5 L10.6 10.6 Z")
    val FullText = stroke("full-text", "M5 5.5 H19", "M5 10 H19", "M5 14.5 H19", "M5 19 H13")
    val Folder = stroke("folder", "M3.5 7 A1.5 1.5 0 0 1 5 5.5 H9.5 L11.5 7.5 H19 A1.5 1.5 0 0 1 20.5 9 V17.5 A1.5 1.5 0 0 1 19 19 H5 A1.5 1.5 0 0 1 3.5 17.5 Z")
    val Tray = stroke(
        "tray", "M4 13 L6.5 5.5 H17.5 L20 13 V18.5 A1.5 1.5 0 0 1 18.5 20 H5.5 A1.5 1.5 0 0 1 4 18.5 Z",
        "M4 13 H8.5 L9.5 15.5 H14.5 L15.5 13 H20",
    )
    val AlignLeft = stroke("align-left", "M5 6.5 H19", "M5 10.5 H14", "M5 14.5 H19", "M5 18.5 H14", width = 2f)
    val AlignCenter = stroke("align-center", "M5 6.5 H19", "M8.5 10.5 H15.5", "M5 14.5 H19", "M8.5 18.5 H15.5", width = 2f)
    /** 账号类型：服务器 / 本机 / 演示数据。 */
    val Server = stroke("server", "M5 4.5 H19 A1 1 0 0 1 20 5.5 V10 A1 1 0 0 1 19 11 H5 A1 1 0 0 1 4 10 V5.5 A1 1 0 0 1 5 4.5 Z", "M5 13 H19 A1 1 0 0 1 20 14 V18.5 A1 1 0 0 1 19 19.5 H5 A1 1 0 0 1 4 18.5 V14 A1 1 0 0 1 5 13 Z", "M7.5 7.75 H8", "M7.5 16.25 H8")
    val Device = stroke("device", "M8 3 H16 A1.5 1.5 0 0 1 17.5 4.5 V19.5 A1.5 1.5 0 0 1 16 21 H8 A1.5 1.5 0 0 1 6.5 19.5 V4.5 A1.5 1.5 0 0 1 8 3 Z", "M11 18 H13")
    val Sparkle = stroke("sparkle", "M12 3.5 L13.8 10.2 L20.5 12 L13.8 13.8 L12 20.5 L10.2 13.8 L3.5 12 L10.2 10.2 Z")
    val Plus = stroke("plus", "M12 5 V19", "M5 12 H19", width = 2.1f)
    val Close = stroke("close", "M6.5 6.5 L17.5 17.5", "M17.5 6.5 L6.5 17.5", width = 2.1f)
    val More = fill(
        "more",
        "M5 12 m-1.6 0 a1.6 1.6 0 1 0 3.2 0 a1.6 1.6 0 1 0 -3.2 0",
        "M12 12 m-1.6 0 a1.6 1.6 0 1 0 3.2 0 a1.6 1.6 0 1 0 -3.2 0",
        "M19 12 m-1.6 0 a1.6 1.6 0 1 0 3.2 0 a1.6 1.6 0 1 0 -3.2 0",
    )
    val Search = stroke("search", "M10.5 4 A6.5 6.5 0 1 1 10.49 4 Z", "M15.3 15.3 L20 20")
    val ArrowUp = stroke("arrow-up", "M12 19.5 V5", "M6 11 L12 5 L18 11")
    val ArrowDown = stroke("arrow-down", "M12 4.5 V19", "M6 13 L12 19 L18 13")
    val List = stroke("list", "M9 6.5 H20", "M9 12 H20", "M9 17.5 H20", "M4.5 6.5 H5", "M4.5 12 H5", "M4.5 17.5 H5")

    private const val STAR =
        "M12 3.6 L14.55 8.85 L20.3 9.65 L16.1 13.6 L17.15 19.35 L12 16.6 L6.85 19.35 L7.9 13.6 L3.7 9.65 L9.45 8.85 Z"

    private fun stroke(name: String, vararg paths: String, width: Float = 1.9f): ImageVector =
        build(name, paths, filled = false, width = width)

    private fun fill(name: String, vararg paths: String): ImageVector = build(name, paths, filled = true, width = 0f)

    private fun build(name: String, paths: Array<out String>, filled: Boolean, width: Float): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            for (d in paths) {
                addPath(
                    pathData = addPathNodes(d),
                    fill = if (filled) SolidColor(Color.Black) else null,
                    stroke = if (filled) null else SolidColor(Color.Black),
                    strokeLineWidth = width,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()
}
