package io.github.xccxcxxc.runnel.ui.list

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import io.github.xccxcxxc.runnel.data.SwipeKind
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import io.github.xccxcxxc.runnel.data.PullBottom
import io.github.xccxcxxc.runnel.ui.ArticleActions
import io.github.xccxcxxc.runnel.ui.components.ActionSheet
import io.github.xccxcxxc.runnel.ui.components.ContextMenuItem
import io.github.xccxcxxc.runnel.ui.components.ContextMenuOverlay
import io.github.xccxcxxc.runnel.ui.components.SheetAction
import io.github.xccxcxxc.runnel.ui.components.rememberPullFromBottom
import io.github.xccxcxxc.runnel.ui.reader.WebViewPool
import io.github.xccxcxxc.runnel.ui.components.ToolbarHeight
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.xccxcxxc.runnel.AccountSession
import io.github.xccxcxxc.runnel.AppGraph
import io.github.xccxcxxc.runnel.LocalAccount
import io.github.xccxcxxc.runnel.Graph
import io.github.xccxcxxc.runnel.data.ArticleSource
import io.github.xccxcxxc.runnel.data.FilterMode
import io.github.xccxcxxc.runnel.data.GroupBy
import io.github.xccxcxxc.runnel.data.db.ArticleListItem
import io.github.xccxcxxc.runnel.sync.SyncState
import io.github.xccxcxxc.runnel.ui.components.BarButton
import io.github.xccxcxxc.runnel.ui.components.BarHeight
import io.github.xccxcxxc.runnel.ui.components.RunnelIcons
import io.github.xccxcxxc.runnel.ui.components.FilterBottomBar
import io.github.xccxcxxc.runnel.ui.components.FilterField
import io.github.xccxcxxc.runnel.ui.components.LargeTitleList
import io.github.xccxcxxc.runnel.ui.components.SwipeAction
import io.github.xccxcxxc.runnel.ui.components.SwipeRow
import io.github.xccxcxxc.runnel.ui.components.pressHighlight
import io.github.xccxcxxc.runnel.ui.dayLabel
import io.github.xccxcxxc.runnel.ui.nav.LocalNavEntry
import io.github.xccxcxxc.runnel.ui.nav.ReaderSession
import io.github.xccxcxxc.runnel.ui.theme.Runnel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 列表里的一行：日期分组标题，或一篇文章。 */
@Immutable
sealed interface ListRow {
    val key: String

    data class Header(val label: String) : ListRow {
        override val key get() = "h:$label"
    }

    data class Article(val item: ArticleListItem) : ListRow {
        override val key get() = "a:${item.id}"
    }
}

/** 长按菜单对应的那一行：文章和它此刻在列表里的顶边（px）。 */
private class MenuTarget(val item: ArticleListItem, val top: Int)

/** 一次构建出的列表数据：文章顺序、带分组标题的行、以及两个下标索引。 */
class BuiltList(val items: List<ArticleListItem>, val rows: List<ListRow>) {
    val itemIndex: Map<Long, Int> = HashMap<Long, Int>(items.size * 2).also { m -> items.forEachIndexed { i, a -> m[a.id] = i } }
    val rowIndex: Map<Long, Int> = HashMap<Long, Int>(items.size * 2).also { m ->
        rows.forEachIndexed { i, r -> if (r is ListRow.Article) m[r.item.id] = i }
    }
}

/** 列表快照与“新文章”提示。快照打开后不随数据库变化重排，避免阅读时列表跳动。 */
class ArticleListState(
    private val graph: AppGraph,
    private val account: AccountSession,
    private val scope: CoroutineScope,
    val source: ArticleSource,
    initialFilter: FilterMode,
) {
    var filter by mutableStateOf(initialFilter)
        private set
    var built by mutableStateOf<BuiltList?>(null)
        private set
    var newCount by mutableIntStateOf(0)
        private set
    var session: ReaderSession? by mutableStateOf(null)

    /** Filter 输入框：只过滤当前列表（已载入的快照），不查库。 */
    var query by mutableStateOf("")
        private set
    var filterOpen by mutableStateOf(false)
        private set
    var focusPending by mutableStateOf(false)
        private set

    /** 当前快照（未过滤）；载入中为 null。 */
    private var raw: List<ArticleListItem>? = null
    private var pendingRaw: List<ArticleListItem>? = null
    private var rebuild: Job? = null

    init {
        reload()
        scope.launch {
            account.sync.dataVersion.drop(1).collect {
                val fresh = account.repo.loadList(source, filter)
                val current = raw.orEmpty().mapTo(HashSet()) { it.id }
                val added = fresh.count { it.id !in current }
                if (added > 0) {
                    pendingRaw = fresh
                    newCount = added
                }
            }
        }
    }

    private fun reload() {
        scope.launch {
            val list = account.repo.loadList(source, filter)
            raw = list
            built = build(list, query)
        }
    }

    fun switchFilter(mode: FilterMode) {
        if (mode == filter) return
        filter = mode
        built = null
        raw = null
        pendingRaw = null
        newCount = 0
        reload()
    }

    /** 点“N 篇新文章”：换成新快照。 */
    fun applyNew() {
        val fresh = pendingRaw ?: return
        pendingRaw = null
        newCount = 0
        raw = fresh
        rebuild?.cancel()
        rebuild = scope.launch { built = build(fresh, query) }
    }

    fun openFilter() {
        filterOpen = true
        focusPending = true
    }

    fun onFilterFocused() {
        focusPending = false
    }

    /** 输入有短暂防抖，打字时不每个字符都重建一遍几千行；清空时立即还原。 */
    fun updateQuery(text: String) {
        query = text
        rebuild?.cancel()
        rebuild = scope.launch {
            if (text.isNotBlank()) delay(120)
            val list = raw ?: return@launch // 还在载入：载入完成时会按当前 query 构建
            built = build(list, text)
        }
    }

    fun closeFilter() {
        filterOpen = false
        focusPending = false
        if (query.isNotEmpty()) updateQuery("")
    }

    fun open(index: Int): ReaderSession {
        val s = ReaderSession(built?.items.orEmpty().map { it.id }, index, source, filter)
        session = s
        return s
    }

    private suspend fun build(all: List<ArticleListItem>, q: String): BuiltList = withContext(Dispatchers.Default) {
        val list = filterItems(all, q)
        // 数据库按时间从新到旧；“未读”默认从旧到新（可在设置里关）
        val settings = graph.prefs.settings.value
        val sorted = if (filter == FilterMode.UNREAD && settings.oldestFirstUnread) list.asReversed() else list
        val rows = ArrayList<ListRow>(sorted.size + 32)
        val ordered: List<ArticleListItem>
        when (settings.groupBy) {
            GroupBy.DATE -> {
                ordered = sorted
                var last: String? = null
                for (a in ordered) {
                    val label = dayLabel(a.publishedAt)
                    if (label != last) {
                        rows += ListRow.Header(label)
                        last = label
                    }
                    rows += ListRow.Article(a)
                }
            }
            GroupBy.FEED -> {
                // 按订阅源分组：组按各自第一篇出现的顺序排，组内保持原顺序；阅读页翻页顺序和列表一致
                val groups = sorted.groupBy { it.feedId }
                ordered = groups.values.flatten()
                for ((_, items) in groups) {
                    rows += ListRow.Header(items.first().feedTitle)
                    items.mapTo(rows) { ListRow.Article(it) }
                }
            }
            GroupBy.NONE -> {
                ordered = sorted
                ordered.mapTo(rows) { ListRow.Article(it) }
            }
        }
        BuiltList(ordered, rows)
    }

    companion object {
        /** 空格分隔的每个词都要出现在标题、摘要或订阅源名里（不分大小写）。 */
        fun filterItems(list: List<ArticleListItem>, query: String): List<ArticleListItem> {
            val words = query.lowercase().split(' ', '\u3000').filter { it.isNotEmpty() }
            if (words.isEmpty()) return list
            return list.filter { a ->
                words.all { w ->
                    a.title.contains(w, ignoreCase = true) ||
                        a.summary.contains(w, ignoreCase = true) ||
                        a.feedTitle.contains(w, ignoreCase = true)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArticleListScreen(
    source: ArticleSource,
    filter: FilterMode,
    onBack: () -> Unit,
    onOpenArticle: (ReaderSession) -> Unit,
    onReplace: (ArticleSource, FilterMode) -> Unit,
) {
    val graph = Graph.get()
    val entry = LocalNavEntry.current
    val account = LocalAccount.current
    val state = entry.retain("list") { ArticleListState(graph, account, entry.scope, source, filter) }
    val repo = account.repo
    val flags = repo.flags
    val sync by account.sync.state.collectAsStateWithLifecycle()
    val settings by graph.prefs.settings.collectAsStateWithLifecycle()
    val accountSettings by account.prefs.settings.collectAsStateWithLifecycle()
    // 提前在空闲时组合/排版前方约一屏的行（Compose 1.9+），快速滑动时新行不必在当帧现做
    val listState = rememberLazyListState(cacheWindow = LazyLayoutCacheWindow(ahead = 720.dp, behind = 240.dp))
    val scope = rememberCoroutineScope()
    val colors = Runnel.colors
    var confirmMarkAll by remember { mutableStateOf(false) }
    val built = state.built
    // 数据到了、这一帧会排好首屏行：推入动画这才开始（Navigator.awaitOffscreenReady）
    if (built != null) SideEffect { entry.markContentReady() }

    FollowReader(state, listState)
    val thumbSize = settings.imagePreview.thumbSize()
    if (built != null && thumbSize != null) PrefetchThumbnails(listState, built.rows, thumbSize)
    if (accountSettings.markReadOnScroll && built != null) MarkReadOnScroll(listState, built.rows)
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    // 切换筛选或改了 Filter 文字后回到顶部（输入框在列表顶部）
    LaunchedEffect(state.filter, state.query) { listState.scrollToItem(0) }

    val unreadInList by remember(built) {
        derivedStateOf { built?.items?.count { !(flags.read[it.id] ?: it.isRead) } ?: 0 }
    }
    val subtitle = when {
        built == null -> " "
        state.filter == FilterMode.UNREAD -> "$unreadInList 篇未读"
        state.filter == FilterMode.STARRED -> "${built.items.size} 篇星标"
        else -> "${built.items.size} 篇文章"
    }
    // 注意：下面这些回调（局部函数）会被 Compose 编译器记住、不随重组更新——以前它们读组合时的 built / unreadInList，
    // 永远停在“列表还没载入”的那一刻，导致从底部上拉松手不标已读（2026-10-18）。所以回调里只读实时值：
    // state.built、flags、graph.prefs.settings.value。
    fun liveItems() = state.built?.items.orEmpty()
    fun liveUnread() = liveItems().count { !(flags.read[it.id] ?: it.isRead) }

    // 离开这个列表：退回上一级或打开下一个订阅源，[markRead] 时顺带把这里的未读全部标为已读
    // （正在用 Filter 筛选时只标筛选出来的）。先导航再写库——页面出栈会取消它自己的协程，所以放在 App 级的 scope 里
    fun leave(openNext: Boolean, markRead: Boolean) {
        val before = liveItems().maxOfOrNull { it.publishedAt } ?: System.currentTimeMillis()
        val filtered = if (markRead && state.query.isNotBlank()) {
            liveItems().filter { !(flags.read[it.id] ?: it.isRead) }.map { it.id }
        } else null
        val current = state.filter
        graph.scope.launch {
            val next = if (openNext) repo.nextSource(source, current) else null
            if (next != null) onReplace(next, current) else onBack()
            if (filtered != null) repo.setRead(filtered, true)
            else if (markRead) repo.markAllRead(source, before)
        }
    }
    fun markAllRead() {
        // 正在用 Filter 筛选时只标筛选结果，并留在列表页
        if (state.query.isNotBlank()) {
            val ids = liveItems().filter { !(flags.read[it.id] ?: it.isRead) }.map { it.id }
            graph.scope.launch { repo.setRead(ids, true) }
            return
        }
        leave(openNext = graph.prefs.settings.value.markAllOpenNext, markRead = true)
    }
    val searchButton: @Composable () -> Unit = { BarButton(RunnelIcons.Search, "筛选", onClick = state::openFilter) }
    fun requestMarkAll() {
        if (liveUnread() <= 0) return
        if (graph.prefs.settings.value.markAllAsk) confirmMarkAll = true else markAllRead()
    }
    val markAllButton: @Composable () -> Unit = {
        BarButton(RunnelIcons.CheckAll, "全部标为已读", enabled = unreadInList > 0) { requestMarkAll() }
    }

    // 手势 → 从底部上拉。列表里确实没有未读时“全部标为已读”直接离开。
    // “进入下一个订阅源”顺带把这个列表标为已读、不询问（用户要求，2026-10-19）
    fun onPullRelease() {
        val s = graph.prefs.settings.value
        when (s.pullBottom) {
            PullBottom.NONE -> Unit
            PullBottom.NEXT_FEED -> leave(openNext = true, markRead = liveUnread() > 0)
            PullBottom.MARK_ALL_READ ->
                if (liveUnread() > 0 || state.query.isNotBlank()) requestMarkAll()
                else leave(openNext = s.markAllOpenNext, markRead = false)
        }
    }
    val pullHint = when {
        settings.pullBottom != PullBottom.NEXT_FEED -> "松手：全部标为已读"
        unreadInList > 0 -> "松手：标为已读，进入下一个订阅源"
        else -> "松手：进入下一个订阅源"
    }
    val pull = rememberPullFromBottom(listState, enabled = settings.pullBottom != PullBottom.NONE, onRelease = ::onPullRelease)
    var sheetFor by remember { mutableStateOf<ArticleListItem?>(null) }
    // 长按某一行：浮动菜单。记下那一行此刻在列表里的位置，卡片从那里浮起
    var menuFor by remember { mutableStateOf<MenuTarget?>(null) }
    fun openMenu(item: ArticleListItem, key: String) {
        val layout = listState.layoutInfo
        val info = layout.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        focusManager.clearFocus()
        // offset 从顶部留白（状态栏 + 顶栏）之后算起，换成相对列表容器顶边
        menuFor = MenuTarget(item, info.offset - layout.viewportStartOffset)
    }
    // 动画 → 列表加载：数据载入后整个列表淡入
    val listAlpha = animateFloatAsState(if (!settings.animListLoad || built != null) 1f else 0f, tween(260), label = "listLoad")
    LargeTitleList(
        modifier = Modifier.nestedScroll(pull.connection),
        title = source.title,
        subtitle = subtitle,
        titleStyle = Runnel.type.largeTitle.copy(fontSize = 27.sp, lineHeight = 33.sp, fontWeight = FontWeight.SemiBold),
        contentStart = if (settings.showFavicons) 45.dp else 20.dp,
        listState = listState,
        overscrollEffect = pull.overscroll,
        listModifier = Modifier.testTag("article_list").graphicsLayer { alpha = listAlpha.value },
        titleExtra = {
            AnimatedVisibility(visible = state.filterOpen, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                FilterField(
                    value = state.query,
                    onValueChange = state::updateQuery,
                    onClear = {
                        if (state.query.isNotEmpty()) state.updateQuery("")
                        else {
                            focusManager.clearFocus()
                            state.closeFilter()
                        }
                    },
                    onSubmit = { focusManager.clearFocus() },
                    focusPending = state.focusPending,
                    onFocused = state::onFilterFocused,
                    modifier = Modifier.padding(start = 18.dp, end = 16.dp, bottom = 8.dp),
                )
            }
        },
        navigation = { BarButton(RunnelIcons.Back, "返回", onClick = onBack) },
        refreshing = sync is SyncState.Running,
        onRefresh = { account.sync.requestSync() },
        bottomBar = {
            FilterBottomBar(
                current = state.filter,
                onChange = state::switchFilter,
                leading = if (settings.markAllButtonLeft) markAllButton else searchButton,
                trailing = if (settings.markAllButtonLeft) searchButton else markAllButton,
            )
        },
        overlay = {
            val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + BarHeight + 8.dp
            val pullBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + ToolbarHeight + 20.dp
            // 上拉时露出一个大图标，跟着拉的距离渐显、上浮，过阈值变深
            val pullTint by animateColorAsState(if (pull.armed) colors.secondaryLabel else colors.tertiaryLabel, tween(120), label = "pullTint")
            Icon(
                if (settings.pullBottom == PullBottom.NEXT_FEED) RunnelIcons.PullChevron else RunnelIcons.Check,
                null,
                tint = pullTint,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = pullBottom + 40.dp)
                    .size(48.dp)
                    .graphicsLayer {
                        val p = pull.progress
                        alpha = p
                        scaleX = 0.7f + 0.3f * p
                        scaleY = scaleX
                        translationY = (1f - p) * 24.dp.toPx()
                    },
            )
            AnimatedVisibility(
                visible = pull.armed,
                enter = fadeIn() + scaleIn(initialScale = 0.9f),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = pullBottom),
            ) {
                Text(
                    pullHint,
                    style = Runnel.type.footnote,
                    color = colors.label,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(colors.highlight).padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
            AnimatedVisibility(
                visible = state.newCount > 0,
                enter = fadeIn() + scaleIn(initialScale = 0.8f),
                exit = fadeOut() + scaleOut(targetScale = 0.8f),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = top),
            ) {
                Text(
                    "${state.newCount} 篇新文章",
                    style = Runnel.type.footnote,
                    color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(colors.icon)
                        .pressHighlight {
                            state.applyNew()
                            scope.launch { listState.animateScrollToItem(0) }
                        }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
            menuFor?.let { target ->
                val item = target.item
                val read = flags.read[item.id] ?: item.isRead
                val starred = flags.starred[item.id] ?: item.isStarred
                val items = state.built?.items.orEmpty()
                val index = state.built?.itemIndex?.get(item.id) ?: -1
                fun markRead(part: List<ArticleListItem>) {
                    val ids = part.filter { !(flags.read[it.id] ?: it.isRead) }.map { it.id }
                    if (ids.isNotEmpty()) scope.launch { repo.setRead(ids, true) }
                }
                ContextMenuOverlay(
                    anchorTop = target.top,
                    groups = listOf(
                        listOf(
                            ContextMenuItem(if (read) "标为未读" else "标为已读", if (read) RunnelIcons.Circle else RunnelIcons.CircleOutline) {
                                scope.launch { repo.setRead(listOf(item.id), !read) }
                            },
                            ContextMenuItem(if (starred) "取消星标" else "加星标", if (starred) RunnelIcons.StarOutline else RunnelIcons.Star) {
                                scope.launch { repo.setStarred(item.id, !starred) }
                            },
                        ),
                        // 按当前列表的顺序，不含长按的这一篇；第一篇没有“以上”，最后一篇没有“以下”
                        listOfNotNull(
                            if (index > 0) ContextMenuItem("将以上标为已读", RunnelIcons.ArrowUp) { markRead(items.subList(0, index)) } else null,
                            if (index in 0 until items.lastIndex) {
                                ContextMenuItem("将以下标为已读", RunnelIcons.ArrowDown) { markRead(items.subList(index + 1, items.size)) }
                            } else null,
                        ),
                        listOf(
                            ContextMenuItem("分享…", RunnelIcons.Share) {
                                scope.launch { repo.reader(item.id)?.let { ArticleActions.share(context, it.title, it.url) } }
                            },
                        ),
                    ).filter { it.isNotEmpty() },
                    onClosed = { menuFor = null },
                ) {
                    // 点浮起的卡片 = 打开这篇
                    ArticleRow(
                        item, read, starred,
                        showFavicon = settings.showFavicons,
                        showPreview = settings.textPreview,
                        thumbSize = thumbSize,
                    ) {
                        menuFor = null
                        if (index >= 0) onOpenArticle(state.open(index))
                    }
                }
            }
        },
    ) {
        if (built != null && built.items.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                Text(
                    if (state.query.isNotBlank()) "没有找到“${state.query.trim()}”" else "这里没有文章",
                    style = Runnel.type.subhead,
                    color = colors.secondaryLabel,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
                )
            }
        }
        if (built != null) {
            items(
                built.rows,
                key = { it.key },
                contentType = { r -> if (r is ListRow.Header) "header" else if ((r as ListRow.Article).item.thumbUrl != null && thumbSize != null) "row_thumb" else "row" },
            ) { row ->
                when (row) {
                    is ListRow.Header -> Text(
                        row.label,
                        style = Runnel.type.footnote.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
                        color = colors.secondaryLabel,
                        modifier = Modifier.fillMaxWidth().padding(start = if (settings.showFavicons) 45.dp else 20.dp, end = 20.dp, top = 22.dp, bottom = 4.dp),
                    )
                    is ListRow.Article -> {
                        val item = row.item
                        val read = flags.read[item.id] ?: item.isRead
                        val starred = flags.starred[item.id] ?: item.isStarred
                        val actionFor = { kind: SwipeKind ->
                            when (kind) {
                                SwipeKind.TOGGLE_READ ->
                                    SwipeAction(if (read) RunnelIcons.Circle else RunnelIcons.CircleOutline, colors.readAction) {
                                        scope.launch { repo.setRead(listOf(item.id), !read) }
                                    }
                                SwipeKind.TOGGLE_STAR ->
                                    SwipeAction(if (starred) RunnelIcons.StarOutline else RunnelIcons.Star, colors.star) {
                                        scope.launch { repo.setStarred(item.id, !starred) }
                                    }
                                SwipeKind.OPEN_BROWSER ->
                                    SwipeAction(RunnelIcons.Compass, colors.readAction) {
                                        scope.launch { repo.reader(item.id)?.url?.let { ArticleActions.openInDefaultBrowser(context, it) } }
                                    }
                                SwipeKind.SHARE ->
                                    SwipeAction(RunnelIcons.Share, colors.accent) {
                                        scope.launch { repo.reader(item.id)?.let { ArticleActions.share(context, it.title, it.url) } }
                                    }
                                SwipeKind.ACTION_SHEET -> SwipeAction(RunnelIcons.More, colors.icon) { sheetFor = item }
                                SwipeKind.NONE -> null
                            }
                        }
                        SwipeRow(
                            start = actionFor(settings.swipeRight),
                            end = actionFor(settings.swipeLeft),
                            // 菜单打开时这一行由浮起的卡片代替
                            modifier = if (menuFor?.item?.id == item.id) Modifier.alpha(0f) else Modifier,
                        ) {
                            ArticleRow(
                                item, read, starred,
                                showFavicon = settings.showFavicons,
                                showPreview = settings.textPreview,
                                thumbSize = thumbSize,
                                onLongClick = { openMenu(item, row.key) },
                            ) {
                                focusManager.clearFocus()
                                onOpenArticle(state.open(built.itemIndex[item.id] ?: 0))
                            }
                        }
                    }
                }
            }
        }
    }

    sheetFor?.let { item ->
        val read = flags.read[item.id] ?: item.isRead
        val starred = flags.starred[item.id] ?: item.isStarred
        fun withUrl(block: (String) -> Unit) = scope.launch { repo.reader(item.id)?.url?.let(block) }
        ActionSheet(
            title = item.title,
            actions = listOf(
                SheetAction(if (read) "标为未读" else "标为已读") { scope.launch { repo.setRead(listOf(item.id), !read) } },
                SheetAction(if (starred) "取消星标" else "加星标") { scope.launch { repo.setStarred(item.id, !starred) } },
                SheetAction("在浏览器中打开") { withUrl { WebViewPool.openInBrowser(context, it.toUri()) } },
                SheetAction("分享") { scope.launch { repo.reader(item.id)?.let { ArticleActions.share(context, it.title, it.url) } } },
                SheetAction("复制链接") { withUrl { ArticleActions.copyLink(context, it) } },
            ),
            onDismiss = { sheetFor = null },
        )
    }

    if (confirmMarkAll) {
        AlertDialog(
            onDismissRequest = { confirmMarkAll = false },
            title = { Text("全部标为已读？") },
            text = { Text((if (state.query.isNotBlank()) "筛选结果" else "「${source.title}」") + "中的 $unreadInList 篇未读文章将被标为已读。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmMarkAll = false
                    markAllRead()
                }) { Text("标为已读", color = colors.accent) }
            },
            dismissButton = { TextButton(onClick = { confirmMarkAll = false }) { Text("取消", color = colors.icon) } },
            containerColor = colors.cell,
        )
    }
}

/** 从阅读页返回时，列表已经滚到刚才读到的那篇（在下层不可见时就滚好）。 */
@Composable
private fun FollowReader(state: ArticleListState, listState: LazyListState) {
    val session = state.session
    LaunchedEffect(session) {
        if (session == null) return@LaunchedEffect
        snapshotFlow { session.currentIndex }.collect { index ->
            val built = state.built ?: return@collect
            val id = session.ids.getOrNull(index) ?: return@collect
            val target = (built.rowIndex[id] ?: return@collect) + 1 // 第 0 项是大标题
            val visible = listState.layoutInfo.visibleItemsInfo
            val first = visible.firstOrNull()?.index ?: 0
            val last = visible.lastOrNull()?.index ?: 0
            if (target <= first || target >= last) listState.scrollToItem(target, scrollOffset = -200)
        }
    }
}

/** “滚动时标为已读”：完全滑出屏幕顶部的文章，攒一小批后一起标为已读。 */
@Composable
private fun MarkReadOnScroll(listState: LazyListState, rows: List<ListRow>) {
    val repo = LocalAccount.current.repo
    LaunchedEffect(listState, rows) {
        var marked = 0 // rows 里已处理到的下标
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { first ->
                // 第 0 项是大标题；rows 里下标 < first - 1 的行已完全滑出顶部
                val passed = (first - 1).coerceIn(0, rows.size)
                if (passed - marked < 3) return@collect
                val ids = rows.subList(marked, passed)
                    .mapNotNull { (it as? ListRow.Article)?.item }
                    .filter { !(repo.flags.read[it.id] ?: it.isRead) }
                    .map { it.id }
                marked = passed
                repo.setRead(ids, true)
            }
    }
}
