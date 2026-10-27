package io.github.xccxcxxc.runnel

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import io.github.xccxcxxc.runnel.data.Settings
import io.github.xccxcxxc.runnel.ui.nav.NavHost
import io.github.xccxcxxc.runnel.ui.nav.Navigator
import io.github.xccxcxxc.runnel.ui.nav.Route
import io.github.xccxcxxc.runnel.ui.reader.ReaderStylePanel
import io.github.xccxcxxc.runnel.ui.theme.RunnelTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

/**
 * 阅读设置面板开在可以全屏右滑返回的页面上（和阅读页一样）：
 * 往右拖滑块不能变成右滑返回（2026-10-20 真机反馈“滑动滑块容易变为滑动屏幕”）。
 */
@RunWith(RobolectricTestRunner::class)
// 开发机是 arm64 Linux，Robolectric 的原生运行时不支持，用纯 Java 的旧实现
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@Config(sdk = [35], qualifiers = "w400dp-h800dp")
class ReaderStylePanelTest {
    @get:Rule
    val rule = createComposeRule()

    private lateinit var navigator: Navigator

    private fun setPage(panel: Boolean) {
        rule.setContent {
            RunnelTheme(Settings()) {
                val scope = rememberCoroutineScope()
                navigator = remember { Navigator(listOf(Route.Accounts), scope).also { it.push(Route.Search("a")) } }
                NavHost(navigator) { entry ->
                    if (entry.route is Route.Search) ReaderStylePanel(visible = panel, onDismiss = {})
                }
            }
        }
        rule.waitForIdle()
    }

    private fun lineSpacing() = Graph.get().prefs.settings.value.readerLineSpacing

    @Test
    fun dragSliderRightDoesNotSwipeBack() {
        setPage(panel = true)
        val before = lineSpacing()
        // 第二个滑块是行距（字号的滑块在“使用默认字号”打开时不可用）
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))[1].performTouchInput {
            val thumbX = 19.dp.toPx() + (before - 1.2f) / 1.2f * (width - 38.dp.toPx())
            down(Offset(thumbX, centerY))
            repeat(8) { moveBy(Offset(10.dp.toPx(), 0f), delayMillis = 16) }
            up()
        }
        rule.waitForIdle()
        assertEquals(2, navigator.stack.size)
        assertTrue("lineSpacing=${lineSpacing()} before=$before", lineSpacing() > before)
    }

    @Test
    fun swipeBackStillWorksWithoutPanel() {
        setPage(panel = false)
        rule.onRoot().performTouchInput {
            down(Offset(width * 0.2f, centerY))
            repeat(10) { moveBy(Offset(width * 0.07f, 0f), delayMillis = 16) }
            up()
        }
        rule.waitForIdle()
        assertEquals(1, navigator.stack.size)
    }
}
