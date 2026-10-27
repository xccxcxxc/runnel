package io.github.xccxcxxc.runnel.ui.reader

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import android.webkit.WebView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.layout.Row
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.xccxcxxc.runnel.AccountSession
import io.github.xccxcxxc.runnel.AppGraph
import io.github.xccxcxxc.runnel.LocalAccount
import io.github.xccxcxxc.runnel.Graph
import io.github.xccxcxxc.runnel.content.PageVideo
import io.github.xccxcxxc.runnel.data.ArticleSource
import io.github.xccxcxxc.runnel.data.Settings
import io.github.xccxcxxc.runnel.data.db.ReaderArticle
import io.github.xccxcxxc.runnel.ui.components.ActionSheet
import io.github.xccxcxxc.runnel.ui.components.SheetAction
import io.github.xccxcxxc.runnel.ui.components.BarButton
import io.github.xccxcxxc.runnel.ui.components.BarHeight
import io.github.xccxcxxc.runnel.ui.components.BottomToolbar
import io.github.xccxcxxc.runnel.ui.components.RunnelIcons
import io.github.xccxcxxc.runnel.ui.components.ToolbarHeight
import io.github.xccxcxxc.runnel.ui.nav.LocalNavEntry
import io.github.xccxcxxc.runnel.ui.nav.ReaderSession
import io.github.xccxcxxc.runnel.ui.ArticleActions
import io.github.xccxcxxc.runnel.ui.theme.Runnel
import io.github.xccxcxxc.runnel.ui.theme.Motion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/** 每篇文章退出时的正文滚动位置（px），再次打开同一篇时回到那里；只在本次运行内记，最多记 100 篇。键是 “账号 id:文章 id”。 */
private object ReaderScrollMemory {
    private val positions = object : LinkedHashMap<String, Int>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>) = size > 100
    }

    operator fun get(id: String): Int = positions[id] ?: 0

    operator fun set(id: String, y: Int) {
        if (y > 0) positions[id] = y else positions.remove(id)
    }
}

/** 一个 ArticlePage 的滚动恢复状态：[target] 是待恢复的位置，[settled] 为 true 才允许在释放时保存（还没恢复完就退出，别把旧位置覆盖成 0）。 */
private class ScrollRestore(var target: Int) {
    var settled = target <= 0

    /** 用的是留在内存里的 WebView，还没对过正文（对上了就不用重新载入）。 */
    var parked = false

    /** 正文是否可以露出来：要恢复位置时，先用背景色盖住 WebView，滚到位再露出，免得先看到顶部再跳过去。 */
    var revealed by mutableStateOf(target <= 0)
}

/** 阅读页状态：已加载文章的缓存、全文模式、提示消息、拉动切篇的位移。 */
class ReaderState(private val graph: AppGraph, private val account: AccountSession, private val scope: CoroutineScope) {
    val accountId: String get() = account.id

    private val cache = LinkedHashMap<Long, ReaderArticle>(16, 0.75f, true)
    val fullText = mutableStateMapOf<Long, String>()
    val showFull = mutableStateMapOf<Long, Boolean>()
    val loadingFull = mutableStateMapOf<Long, Boolean>()
    var message by mutableStateOf<String?>(null)
        private set

    /** 当前页被拉动的位移（px）：负数 = 到底继续上拉（下一篇），正数 = 到顶继续下拉（上一篇）。 */
    val pull: MutableFloatState = mutableFloatStateOf(0f)

    /** 拉了阈值的几成（按手指距离，符号同 [pull]），给提示渐显用。 */
    val pullProgress: MutableFloatState = mutableFloatStateOf(0f)

    /** 当前组合中的 WebView，手势判断“是否已滚到顶/底”用。 */
    val webViews = HashMap<Long, WebView>()

    /** 顶栏/底栏是否显示：正文往下滚隐藏，往上滚或回到顶部显示（自动隐藏）。 */
    var barsVisible by mutableStateOf(true)
    var currentId: Long = -1L

    fun onWebScroll(id: Long, y: Int, oldY: Int) {
        if (id != currentId) return
        val dy = y - oldY
        if (!graph.prefs.settings.value.autoHideBars) {
            barsVisible = true
            return
        }
        when {
            y < 40 -> barsVisible = true
            dy > 6 -> barsVisible = false
            dy < -10 -> barsVisible = true
        }
    }

    suspend fun load(id: Long): ReaderArticle? {
        cache[id]?.let { return it }
        val a = account.repo.reader(id) ?: return null
        cache[id] = a
        if (cache.size > 24) cache.remove(cache.keys.first())
        a.fullHtml?.let { fullText[id] = it }
        return a
    }

    /**
     * 订阅源设为默认用 Reader View 打开时，停在这篇文章就自动抓取并显示全文（每篇只自动一次，手动切回后不再强开）。
     * 虎嗅的视频文章正文只有一句摘要，视频在原网页里，也自动抓（[PageVideo]）。
     */
    private val autoTried = HashSet<Long>()

    suspend fun autoFullText(id: Long) {
        if (id in autoTried) return
        val article = load(id) ?: return
        if (!account.prefs.settings.value.autoReaderView(article.feedId) && !PageVideo.shouldFetch(article.url, article.html)) return
        autoTried += id
        if (showFull[id] != true && article.url != null) toggleFullText(article)
    }

    fun toggleFullText(article: ReaderArticle) {
        val id = article.id
        if (showFull[id] == true) {
            showFull[id] = false
            return
        }
        if (fullText[id] != null) {
            showFull[id] = true
            return
        }
        if (article.url == null || loadingFull[id] == true) return
        loadingFull[id] = true
        scope.launch {
            runCatching { account.repo.fetchFullText(article) }
                .onSuccess {
                    fullText[id] = it
                    showFull[id] = true
                }
                .onFailure { toast("抓取全文失败：${it.message ?: "未知错误"}") }
            loadingFull[id] = false
        }
    }

    fun toast(text: String) {
        message = text
        scope.launch {
            delay(2200)
            if (message == text) message = null
        }
    }
}

/**
 * 阅读页的手势：
 * - 读到底继续上拉 → 下一篇；在顶部继续下拉 → 上一篇（过阈值震动，松手切换）
 * - 任意位置向右滑 → 跟手返回列表
 * 文章之间是竖向翻页；WebView 自己负责正文滚动，只有滚到边缘再拉时才由页面接管。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReaderScreen(session: ReaderSession, onBack: () -> Unit) {
    val graph = Graph.get()
    val account = LocalAccount.current
    val entry = LocalNavEntry.current
    val state = entry.retain("reader") { ReaderState(graph, account, entry.scope) }
    val settings by graph.prefs.settings.collectAsStateWithLifecycle()
    val colors = Runnel.colors
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val pagerState = rememberPagerState(initialPage = session.currentIndex) { session.ids.size }
    // 按手指距离算（要拉得更远、更粘，和列表上拉一样，2026-10-20；以前是位移 72dp ≈ 手指 140dp）
    val pullThreshold = with(LocalDensity.current) { 220.dp.toPx() }

    // 停在哪篇：记录位置给列表，按设置标为已读
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            session.currentIndex = page
            state.currentId = session.ids.getOrNull(page) ?: -1L
            state.barsVisible = true
            if (account.prefs.settings.value.markReadOnOpen) session.ids.getOrNull(page)?.let { account.repo.markReadIfUnread(it) }
            session.ids.getOrNull(page)?.let { state.autoFullText(it) }
        }
    }

    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val topPad = (statusTop + BarHeight).value.roundToInt() + 4
    val bottomPad = (navBottom + ToolbarHeight).value.roundToInt() + 56

    fun goTo(page: Int) {
        if (page !in 0 until session.ids.size) return
        scope.launch {
            if (settings.animArticleSwitch) pagerState.animateScrollToPage(page, animationSpec = Motion.ios(0.45f)) else pagerState.scrollToPage(page)
        }
    }

    // 读到最后一篇时，如果开了“读完后进入下一个订阅源”，找出下一个来源
    val nextSource by produceState<ArticleSource?>(null, pagerState.currentPage, session.source, settings.autoNextFeed) {
        value = if (settings.autoNextFeed && pagerState.currentPage == session.ids.size - 1) {
            account.repo.nextSource(session.source, session.filter)
        } else {
            null
        }
    }

    /** 把当前来源的文章全部标为已读，换成下一个来源的文章从头读。 */
    fun advance() {
        val next = nextSource ?: return
        val previous = session.source
        scope.launch {
            account.repo.markAllRead(previous, Long.MAX_VALUE)
            val list = account.repo.loadOrdered(next, session.filter)
            if (list.isEmpty()) return@launch
            session.source = next
            session.ids = list.map { it.id }
            session.currentIndex = 0
            pagerState.scrollToPage(0)
            state.toast("已读完「${previous.title}」，进入「${next.title}」")
        }
    }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        // 翻页只由我们自己来（拉动切篇、底栏、下一个订阅源），不响应 bringIntoView：三星真机上 WebView 获得焦点后，
        // 会有人把它带着滚动距离的整屏矩形（顶部 = scrollY）交给 AndroidView，Compose 不换算就转成 bringIntoView，
        // 正文滚到深处时翻页器以为要往下滚好几屏，一闪跳到后面的文章（2026-10-20 真机日志：scrollY=35912 时从第 1 篇跳到第 4 篇）
        CompositionLocalProvider(LocalBringIntoViewSpec provides NoBringIntoView) {
            VerticalPager(
                state = pagerState,
                userScrollEnabled = false,
                beyondViewportPageCount = 1,
                key = { session.ids[it] },
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (settings.pullToSwitch) Modifier.pullToSwitch(pagerState, state, session, view, pullThreshold, scope, ::goTo, { nextSource != null }, ::advance) else Modifier)
                    .testTag("reader_pager"),
            ) { page ->
                Box(
                    Modifier.fillMaxSize().graphicsLayer {
                        translationY = if (page == pagerState.currentPage) state.pull.floatValue else 0f
                    },
                ) {
                    ArticlePage(session.ids[page], page == pagerState.currentPage, state, settings, topPad, bottomPad)
                }
            }
        }

        val current = pagerState.currentPage.coerceIn(0, session.ids.lastIndex)
        val currentId = session.ids[current]
        val article by produceState<ReaderArticle?>(null, currentId) { value = state.load(currentId) }
        val prevTitle by produceState<String?>(null, current) { value = session.ids.getOrNull(current - 1)?.let { state.load(it)?.title } }
        val nextTitle by produceState<String?>(null, current) { value = session.ids.getOrNull(current + 1)?.let { state.load(it)?.title } }

        PullHint(
            icon = RunnelIcons.PullChevron,
            label = if (current > 0) "上一篇" else "已是第一篇",
            title = prevTitle,
            progress = { state.pullProgress.floatValue.coerceIn(0f, 1f) },
            modifier = Modifier.align(Alignment.TopCenter).padding(top = statusTop + BarHeight + 12.dp),
        )
        PullHint(
            icon = RunnelIcons.PullChevronUp,
            label = when {
                current < session.ids.size - 1 -> "下一篇"
                nextSource != null -> "读完了，下一个订阅源"
                else -> "已是最后一篇"
            },
            title = if (current < session.ids.size - 1) nextTitle else nextSource?.title,
            progress = { (-state.pullProgress.floatValue).coerceIn(0f, 1f) },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = navBottom + ToolbarHeight + 12.dp),
        )

        var stylePanel by remember { mutableStateOf(false) }
        // 0 = 显示，1 = 收起；只在 graphicsLayer 里读，收起/展开不触发重组
        val barsHidden = animateFloatAsState(if (state.barsVisible) 0f else 1f, Motion.ios(0.3f), label = "bars")
        ReaderTopBar(
            onClose = onBack,
            onMore = { stylePanel = true },
            modifier = Modifier.align(Alignment.TopCenter).graphicsLayer { translationY = -barsHidden.value * size.height },
        )
        ReaderToolbar(
            article = article,
            state = state,
            canNext = current < session.ids.size - 1 || nextSource != null,
            onNext = { if (current < session.ids.size - 1) goTo(current + 1) else advance() },
            modifier = Modifier.align(Alignment.BottomCenter).graphicsLayer { translationY = barsHidden.value * size.height },
        )
        AnimatedVisibility(
            visible = state.message != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = navBottom + ToolbarHeight + 16.dp),
        ) {
            Text(
                state.message.orEmpty(),
                style = Runnel.type.footnote,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xE6303030))
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
        ReaderStylePanel(visible = stylePanel, onDismiss = { stylePanel = false })
    }
}

/** 拉动切篇的提示：箭头 + “下一篇” + 标题，透明度和箭头翻转都只在绘制阶段随位移变化。 */
@Composable
private fun PullHint(icon: ImageVector, label: String, title: String?, progress: () -> Float, modifier: Modifier = Modifier) {
    val colors = Runnel.colors
    // 只在过不过阈值变化时重组（换颜色），拉动本身只走 graphicsLayer
    val currentProgress by rememberUpdatedState(progress)
    val armed by remember { derivedStateOf { currentProgress() >= 1f } }
    Column(
        modifier
            .padding(horizontal = 40.dp)
            .graphicsLayer {
                val p = progress()
                alpha = (p * 1.4f - 0.2f).coerceIn(0f, 1f)
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // 大图标：随拉动放大、过阈值翻转并变深
        Icon(
            icon, null, tint = if (armed) colors.secondaryLabel else colors.tertiaryLabel,
            modifier = Modifier.size(48.dp).graphicsLayer {
                val p = progress()
                scaleX = 0.7f + 0.3f * p
                scaleY = scaleX
                rotationZ = if (p >= 1f) 180f else 0f
            },
        )
        Text(label, style = Runnel.type.footnote, color = colors.secondaryLabel)
        if (title != null) {
            Text(title, style = Runnel.type.subhead, color = colors.label, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ArticlePage(id: Long, isCurrent: Boolean, state: ReaderState, settings: Settings, topPad: Int, bottomPad: Int) {
    val colors = Runnel.colors
    // 当前页立即加载；相邻页延后一点，避免进入阅读页的瞬间同时加载三页卡住主线程
    var ready by remember { mutableStateOf(isCurrent) }
    LaunchedEffect(isCurrent) {
        if (!isCurrent && !ready) delay(400)
        ready = true
    }
    val article by produceState<ReaderArticle?>(null, id) { value = state.load(id) }
    val full = state.showFull[id] == true
    val body = (if (full) state.fullText[id] else null) ?: article?.html.orEmpty()
    // 上下留白不算进 key：全屏看视频时系统栏隐藏、屏幕转横，留白跟着变，重新载入会把播放器踢出全屏（转屏也会丢掉正文状态），
    // 只用脚本改 CSS 变量
    val html = remember(article, body, settings, colors, ready) {
        if (!ready) null else article?.let { ReaderHtml.build(it, body, settings, colors, topPad, bottomPad) }
    }
    LaunchedEffect(topPad, bottomPad) {
        state.webViews[id]?.evaluateJavascript(ReaderHtml.paddingScript(topPad, bottomPad), null)
    }
    val bg = colors.background.toArgb()
    val context = LocalContext.current
    // 上次退出时留在内存里的 WebView（WebViewPool.park）：正文已排好、停在原位置，直接复用，不用恢复
    val memKey = "${state.accountId}:$id"
    val parked = remember(id) { WebViewPool.takeParked(memKey, context) }
    val restore = remember(id) { ScrollRestore(if (parked != null) 0 else ReaderScrollMemory[memKey]).also { it.parked = parked != null } }
    val current by rememberUpdatedState(isCurrent)
    // 没有留着的 WebView 时，第一次载入正文后滚回上次退出时的位置：图片还没撑开高度时 scrollTo 会被截短，所以隔一会儿重试，
    // 到位或用户自己动了手就停。恢复期间正文被背景色盖住，到位后一次性露出（最多盖 0.8 秒，之后仍在后台继续重试）
    LaunchedEffect(html) {
        if (html == null || restore.target <= 0) return@LaunchedEffect
        val target = restore.target
        restore.target = 0
        try {
            var lastSet = -1
            for (attempt in 0 until 40) {
                delay(50)
                val web = state.webViews[id] ?: break
                val y = web.scrollY
                if (y >= target - 2) break
                if (lastSet >= 0 && y != lastSet && y != 0) break // 用户已经自己滚动了
                web.scrollTo(0, target)
                lastSet = web.scrollY
                if (lastSet >= target - 2) {
                    delay(32) // 等 WebView 把新位置画出来再露
                    break
                }
                if (attempt == 15) restore.revealed = true
            }
        } finally {
            restore.settled = true
            restore.revealed = true
        }
    }

    val scope = rememberCoroutineScope()
    var pressedImage by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                (parked ?: WebViewPool.obtain(ctx)).also {
                    it.setBackgroundColor(bg)
                    state.webViews[id] = it
                    it.setOnScrollChangeListener { _, _, y, _, oldY -> state.onWebScroll(id, y, oldY) }
                    // 长按图片：弹出保存/分享；长按文字等其它地方照旧（返回 false，系统选字）
                    it.setOnLongClickListener { v ->
                        val hit = (v as WebView).hitTestResult
                        val src = hit.extra
                        if ((hit.type == WebView.HitTestResult.IMAGE_TYPE || hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) && !src.isNullOrBlank()) {
                            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            pressedImage = src
                            true
                        } else {
                            false
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
            onReset = null,
            onRelease = {
                if (restore.settled && it.tag != null) ReaderScrollMemory[memKey] = it.scrollY
                if (state.webViews[id] === it) state.webViews.remove(id)
                // 正在看的这篇（退回列表等）原样留在内存里，再打开时直接复用；翻页时滑出去的相邻页照常回收
                if (current && restore.settled && it.tag != null) WebViewPool.park(memKey, it) else WebViewPool.recycle(it)
            },
            update = { view ->
                view.setBackgroundColor(bg)
                if (html != null && view.tag != html) {
                    // 留着的那份正文已经过时（期间改了字号、样式等）：只能重新载入，再按它原来的位置恢复
                    if (restore.parked && view.tag != null && view.scrollY > 0) {
                        restore.target = view.scrollY
                        restore.settled = false
                        restore.revealed = false
                    }
                    view.tag = html
                    view.loadDataWithBaseURL(article?.url ?: "https://runnel.invalid/", html, "text/html", "utf-8", null)
                }
                if (html != null) restore.parked = false
            },
        )
        if (!restore.revealed) Box(Modifier.fillMaxSize().background(colors.background))
    }

    pressedImage?.let { src ->
        val referer = article?.url
        fun act(done: String, failed: String, block: suspend (ImageActions.Picture) -> Boolean) {
            scope.launch {
                val picture = ImageActions.load(src, referer)
                if (picture == null) state.toast("图片下载失败")
                else if (block(picture)) done.takeIf { it.isNotEmpty() }?.let(state::toast)
                else state.toast(failed)
            }
        }
        ActionSheet(
            title = null,
            actions = listOf(
                SheetAction("保存到相册") { act("已保存到相册（Pictures/Runnel）", "保存失败") { ImageActions.save(context, it) } },
                SheetAction("分享图片") { act("", "分享失败") { ImageActions.share(context, it) } },
            ),
            onDismiss = { pressedImage = null },
        )
    }
}

@Composable
private fun ReaderTopBar(onClose: () -> Unit, onMore: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Runnel.colors
    Box(
        modifier
            .fillMaxWidth()
            .background(colors.background)
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(BarHeight),
    ) {
        BarButton(RunnelIcons.Close, "关闭", modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp), onClick = onClose)
        // “…”打开阅读设置面板（在浏览器打开、复制链接在底栏的分享里）
        BarButton(RunnelIcons.More, "阅读设置", modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp), onClick = onMore)
    }
}

/** 底栏：已读、星标、下一篇、全文、分享，均匀排开。 */
@Composable
private fun ReaderToolbar(
    article: ReaderArticle?,
    state: ReaderState,
    canNext: Boolean,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val repo = LocalAccount.current.repo
    val colors = Runnel.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val id = article?.id
    val dbFlags by remember(id) { if (id != null) repo.observeFlags(id) else flowOf(null) }
        .collectAsState(null)
    val read = id?.let { repo.flags.read[it] } ?: dbFlags?.isRead ?: true
    val starred = id?.let { repo.flags.starred[it] } ?: dbFlags?.isStarred ?: false

    BottomToolbar(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            BarButton(if (read) RunnelIcons.CircleOutline else RunnelIcons.Circle, if (read) "标为未读" else "标为已读", enabled = id != null) {
                if (id != null) scope.launch { repo.setRead(listOf(id), !read) }
            }
            BarButton(if (starred) RunnelIcons.Star else RunnelIcons.StarOutline, if (starred) "取消星标" else "加星标", enabled = id != null) {
                if (id != null) scope.launch { repo.setStarred(id, !starred) }
            }
            BarButton(RunnelIcons.ChevronDown, "下一篇", enabled = canNext, onClick = onNext)
            Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                if (id != null && state.loadingFull[id] == true) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = colors.icon)
                } else {
                    val on = id != null && state.showFull[id] == true
                    BarButton(RunnelIcons.FullText, "全文", tint = if (on) colors.accent else colors.icon, enabled = article?.url != null) {
                        article?.let(state::toggleFullText)
                    }
                }
            }
            BarButton(RunnelIcons.Share, "分享", enabled = article?.url != null) {
                val a = article ?: return@BarButton
                ArticleActions.share(context, a.title, a.url)
            }
        }
    }
}

/**
 * 拉动切篇，在 Initial 阶段（先于 WebView）判断：纵向且正文已在该方向的边缘 → 接管（iOS 橡皮筋阻尼），
 * 其它情况放手交给 WebView 滚动/点链接；横向右滑由 NavHost 处理为全屏返回。
 * 接管后消费事件，WebView 会收到 CANCEL，不会和页面同时动。
 */
private fun Modifier.pullToSwitch(
    pagerState: PagerState,
    state: ReaderState,
    session: ReaderSession,
    view: View,
    threshold: Float,
    scope: CoroutineScope,
    goTo: (Int) -> Unit,
    canAdvance: () -> Boolean,
    advance: () -> Unit,
): Modifier = pointerInput(pagerState) {
    val slop = viewConfiguration.touchSlop
    var settle: Job? = null
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var dx = 0f
        var dy = 0f
        var pulling = false
        var rawPull = 0f
        var armed = false
        var finished = false
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                val delta = change.positionChange()
                if (!pulling) {
                    dx += delta.x
                    dy += delta.y
                    if (abs(dx) > slop && abs(dx) > abs(dy)) return@awaitEachGesture
                    if (abs(dy) <= slop) continue
                    val web = session.ids.getOrNull(pagerState.currentPage)?.let { state.webViews[it] }
                    val atEdge = if (dy < 0) web?.canScrollVertically(1) != true else web?.canScrollVertically(-1) != true
                    if (!atEdge || pagerState.isScrollInProgress) return@awaitEachGesture
                    settle?.cancel()
                    rawPull = state.pullProgress.floatValue * threshold
                    pulling = true
                }
                event.changes.forEach { it.consume() }
                rawPull += delta.y
                val d = size.height.toFloat()
                // iOS 橡皮筋：(1 - 1 / (x·c/d + 1))·d，c = 0.55；x 只取手指距离的 STICKY_FOLLOW，到边后更粘
                val x = abs(rawPull) * STICKY_FOLLOW
                state.pull.floatValue = sign(rawPull) * (1f - 1f / (x * 0.55f / d + 1f)) * d
                state.pullProgress.floatValue = rawPull / threshold
                val nowArmed = abs(rawPull) >= threshold
                if (nowArmed != armed) {
                    armed = nowArmed
                    view.performHapticFeedback(
                        if (Build.VERSION.SDK_INT >= 34) {
                            if (nowArmed) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE else HapticFeedbackConstants.GESTURE_THRESHOLD_DEACTIVATE
                        } else {
                            HapticFeedbackConstants.CLOCK_TICK
                        },
                    )
                }
            }
            if (!pulling) return@awaitEachGesture
            finished = true
            val shown = state.pull.floatValue
            val progress = state.pullProgress.floatValue
            val current = pagerState.currentPage
            when {
                progress <= -1f && current < session.ids.size - 1 -> goTo(current + 1)
                progress <= -1f && current == session.ids.size - 1 && canAdvance() -> advance()
                progress >= 1f && current > 0 -> goTo(current - 1)
            }
            settle = scope.launch {
                animate(1f, 0f, animationSpec = Motion.ios(0.35f, 1f, 0.5f)) { v, _ ->
                    state.pull.floatValue = shown * v
                    state.pullProgress.floatValue = progress * v
                }
            }
        } finally {
            // 手势被取消时把页面放回原位
            if (pulling && !finished) {
                state.pull.floatValue = 0f
                state.pullProgress.floatValue = 0f
            }
        }
    }
}

/** 拉动切篇时橡皮筋跟手的比例。 */
private const val STICKY_FOLLOW = 0.5f

/** 不为 bringIntoView 滚动（阅读页的翻页器用，见 ReaderScreen 里的说明）。 */
@OptIn(ExperimentalFoundationApi::class)
private object NoBringIntoView : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}
