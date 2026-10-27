package io.github.xccxcxxc.runnel.ui.home

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.xccxcxxc.runnel.AccountSession
import io.github.xccxcxxc.runnel.Graph
import io.github.xccxcxxc.runnel.LocalAccount
import io.github.xccxcxxc.runnel.data.ArticleSource
import io.github.xccxcxxc.runnel.data.FilterMode
import io.github.xccxcxxc.runnel.data.SortSubs
import io.github.xccxcxxc.runnel.sync.SyncState
import io.github.xccxcxxc.runnel.ui.components.BarButton
import io.github.xccxcxxc.runnel.ui.components.RunnelIcons
import io.github.xccxcxxc.runnel.ui.components.FeedIcon
import io.github.xccxcxxc.runnel.ui.components.FilterBottomBar
import io.github.xccxcxxc.runnel.ui.components.LargeTitleList
import io.github.xccxcxxc.runnel.ui.components.pressHighlight
import io.github.xccxcxxc.runnel.ui.nav.LocalNavEntry
import io.github.xccxcxxc.runnel.ui.syncedAgo
import io.github.xccxcxxc.runnel.ui.theme.Runnel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

@Immutable
data class FeedRow(val id: String, val title: String, val iconUrl: String?, val site: String?, val count: Int)

@Immutable
data class FolderRow(val id: String, val title: String, val count: Int, val feeds: List<FeedRow>)

@Immutable
data class HomeModel(
    val filter: FilterMode,
    val total: Int,
    val folders: List<FolderRow>,
    val looseFeeds: List<FeedRow>,
    val loaded: Boolean,
    /** 订阅源（未归类）排在文件夹前面（设置 → 账号 → 订阅源排序 = 订阅源优先）。 */
    val feedsFirst: Boolean = false,
)

class HomeState(session: AccountSession, scope: CoroutineScope) {
    val filter = MutableStateFlow(FilterMode.UNREAD)
    /** 文件夹默认展开，只记录被用户收起的。 */
    val collapsed = mutableStateMapOf<String, Boolean>()
    var foldersHidden by mutableStateOf(false)

    val model: StateFlow<HomeModel> = combine(
        session.repo.folders, session.repo.feeds, session.repo.counts(filter), filter, session.prefs.settings,
    ) { folders, feeds, counts, mode, settings ->
        val hideEmpty = mode != FilterMode.ALL
        val rows = feeds.map { FeedRow(it.id, it.title, it.iconUrl ?: it.localIcon?.takeIf(String::isNotEmpty), it.siteUrl, counts[it.id] ?: 0) }
            .filter { !hideEmpty || it.count > 0 }
        val folderOf = feeds.associate { it.id to it.folderId }
        val byFolder = rows.groupBy { folderOf[it.id] }
        var folderRows = folders.mapNotNull { f ->
            val list = byFolder[f.id].orEmpty()
            if (list.isEmpty()) null else FolderRow(f.id, f.title, list.sumOf { it.count }, list)
        }
        val folderIds = folders.mapTo(HashSet()) { it.id }
        var loose = rows.filter { folderOf[it.id] == null || folderOf[it.id] !in folderIds }
        // 订阅源排序：按名称 / 按未读数；“文件夹优先”“订阅源优先”保持服务端顺序，只是两段谁在前
        when (settings.sortSubs) {
            SortSubs.ALPHA -> {
                val byName = compareBy<String> { it.lowercase() }
                folderRows = folderRows.map { it.copy(feeds = it.feeds.sortedWith(compareBy(byName) { f -> f.title })) }.sortedWith(compareBy(byName) { it.title })
                loose = loose.sortedWith(compareBy(byName) { it.title })
            }
            SortSubs.UNREAD -> {
                folderRows = folderRows.map { it.copy(feeds = it.feeds.sortedByDescending { f -> f.count }) }.sortedByDescending { it.count }
                loose = loose.sortedByDescending { it.count }
            }
            SortSubs.FEEDS_FIRST, SortSubs.FOLDERS_FIRST -> Unit
        }
        HomeModel(mode, counts.values.sum(), folderRows, loose, loaded = true, feedsFirst = settings.sortSubs == SortSubs.FEEDS_FIRST)
    }.stateIn(scope, SharingStarted.Eagerly, HomeModel(FilterMode.UNREAD, 0, emptyList(), emptyList(), loaded = false))
}

/** 一个账号的首页：全部未读/星标/文章、文件夹和订阅源。长按订阅源 → 编辑、取消订阅（[onEditFeed]）。 */
@Composable
fun HomeScreen(
    onBack: () -> Unit,
    onOpen: (ArticleSource, FilterMode) -> Unit,
    onSearch: () -> Unit,
    onAdd: () -> Unit,
    onEditFeed: (String) -> Unit,
) {
    val graph = Graph.get()
    val session = LocalAccount.current
    val entry = LocalNavEntry.current
    val state = entry.retain("home") { HomeState(session, entry.scope) }
    val model by state.model.collectAsStateWithLifecycle()
    val sync by session.sync.state.collectAsStateWithLifecycle()
    val lastSync by session.sync.lastSyncAt.collectAsStateWithLifecycle()
    val account by session.account.collectAsStateWithLifecycle()
    val settings by graph.prefs.settings.collectAsStateWithLifecycle()
    val canManage = session.sync.canManageFeeds
    val view = LocalView.current
    // 长按订阅源：震一下，弹出编辑订阅源表单
    val editFeed = { id: String ->
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onEditFeed(id)
    }
    // 下次启动直接进这个账号
    LaunchedEffect(session.id) { graph.prefs.lastAccountId = session.id }
    // 设置 → 外观 → 显示数量：星标 / 未读 / 全部 三种筛选各自可以关掉数字
    val showCounts = when (model.filter) {
        FilterMode.STARRED -> settings.countStarred
        FilterMode.UNREAD -> settings.countUnread
        FilterMode.ALL -> settings.countAll
    }
    val showFavicons = settings.showFavicons
    val listState = rememberLazyListState()
    val colors = Runnel.colors

    val title = account.displayName
    val subtitle = when (val s = sync) {
        is SyncState.Running -> s.message
        is SyncState.Failed -> "同步失败：${s.message}"
        SyncState.Idle -> syncedAgo(lastSync)
    }
    LargeTitleList(
        title = title,
        subtitle = subtitle,
        subtitleColor = if (sync is SyncState.Failed) colors.destructive else null,
        listState = listState,
        listModifier = Modifier.testTag("home_list"),
        navigation = { BarButton(RunnelIcons.Back, "账号", onClick = onBack) },
        actions = {
            BarButton(RunnelIcons.Search, "搜索", onClick = onSearch)
            if (canManage) BarButton(RunnelIcons.Plus, "添加订阅", onClick = onAdd)
        },
        refreshing = sync is SyncState.Running,
        onRefresh = { session.sync.requestSync() },
        bottomBar = { FilterBottomBar(model.filter, onChange = { state.filter.value = it }) },
    ) {
        item(key = "all", contentType = "source") {
            SourceRow(
                title = when (model.filter) {
                    FilterMode.UNREAD -> "未读"
                    FilterMode.STARRED -> "星标"
                    FilterMode.ALL -> "全部文章"
                },
                count = if (showCounts) model.total else 0,
                modifier = Modifier.testTag("row_all"),
            ) { onOpen(ArticleSource.All, model.filter) }
        }
        if (model.loaded && model.folders.isEmpty() && model.looseFeeds.isEmpty()) {
            item(key = "empty", contentType = "empty") {
                Text(
                    when (model.filter) {
                        FilterMode.UNREAD -> "没有未读文章"
                        FilterMode.STARRED -> "还没有星标文章"
                        FilterMode.ALL -> if (canManage) "还没有订阅，点右上角 + 添加" else "还没有订阅"
                    },
                    style = Runnel.type.subhead,
                    color = colors.secondaryLabel,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
        val foldersBlock: LazyListScope.() -> Unit = {
            if (model.folders.isNotEmpty()) {
                item(key = "folders_header", contentType = "header") {
                    SectionHeader("文件夹", collapsed = state.foldersHidden) { state.foldersHidden = !state.foldersHidden }
                }
            }
            if (!state.foldersHidden) {
                for (folder in model.folders) {
                    val open = state.collapsed[folder.id] != true
                    item(key = "folder:${folder.id}", contentType = "folder") {
                        FolderRowView(
                            folder = folder,
                            showCount = showCounts,
                            expanded = open,
                            onToggle = { state.collapsed[folder.id] = open },
                            onClick = { onOpen(ArticleSource.Folder(folder.id, folder.title), model.filter) },
                        )
                    }
                    if (open) {
                        for (feed in folder.feeds) {
                            item(key = "feed:${folder.id}:${feed.id}", contentType = "feed") {
                                FeedRowView(feed, indent = true, showCount = showCounts, showFavicon = showFavicons, onLongClick = if (canManage) ({ editFeed(feed.id) }) else null) {
                                    onOpen(ArticleSource.Feed(feed.id, feed.title, feed.iconUrl), model.filter)
                                }
                            }
                        }
                    }
                }
            }
        }
        val looseBlock: LazyListScope.() -> Unit = {
            if (model.looseFeeds.isNotEmpty()) {
                item(key = "feeds_header", contentType = "header") { SectionHeader("订阅源", collapsed = null) {} }
                for (feed in model.looseFeeds) {
                    item(key = "feed:${feed.id}", contentType = "feed") {
                        FeedRowView(feed, indent = false, showCount = showCounts, showFavicon = showFavicons, onLongClick = if (canManage) ({ editFeed(feed.id) }) else null) {
                            onOpen(ArticleSource.Feed(feed.id, feed.title, feed.iconUrl), model.filter)
                        }
                    }
                }
            }
        }
        if (model.feedsFirst) {
            looseBlock()
            foldersBlock()
        } else {
            foldersBlock()
            looseBlock()
        }
    }
}

/** 粗体的小节标题；[collapsed] 非空时右侧有展开/收起箭头。 */
@Composable
private fun SectionHeader(text: String, collapsed: Boolean?, onToggle: () -> Unit) {
    val colors = Runnel.colors
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (collapsed != null) Modifier.clickable(remember { MutableInteractionSource() }, indication = null, onClick = onToggle) else Modifier)
            .padding(start = 20.dp, end = 20.dp, top = 26.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = Runnel.type.title.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold), color = colors.label, modifier = Modifier.weight(1f))
        if (collapsed != null) {
            Icon(
                RunnelIcons.ChevronDown, null, tint = colors.icon,
                modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = if (collapsed) -90f else 0f },
            )
        }
    }
}

@Composable
private fun SourceRow(title: String, count: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = Runnel.colors
    Row(
        modifier.fillMaxWidth().pressHighlight(onClick).height(52.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = Runnel.type.title.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold), color = colors.label, modifier = Modifier.weight(1f))
        CountLabel(count)
    }
}

@Composable
private fun FolderRowView(folder: FolderRow, expanded: Boolean, showCount: Boolean, onToggle: () -> Unit, onClick: () -> Unit) {
    val colors = Runnel.colors
    Row(
        Modifier.fillMaxWidth().pressHighlight(onClick).height(48.dp).padding(start = 8.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clickable(remember { MutableInteractionSource() }, indication = null, onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                RunnelIcons.ChevronDown, if (expanded) "收起" else "展开", tint = colors.icon,
                modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = if (expanded) 0f else -90f },
            )
        }
        Text(folder.title, style = Runnel.type.body, color = colors.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (showCount) CountLabel(folder.count)
    }
}

@Composable
private fun FeedRowView(feed: FeedRow, indent: Boolean, showCount: Boolean, showFavicon: Boolean, onLongClick: (() -> Unit)?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().pressHighlight(onLongClick, onClick).height(46.dp).padding(start = if (indent) 52.dp else 20.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showFavicon) {
            FeedIcon(feed.title, feed.iconUrl, size = 22.dp, site = feed.site)
            Spacer(Modifier.width(12.dp))
        }
        Text(feed.title, style = Runnel.type.body, color = Runnel.colors.label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (showCount) CountLabel(feed.count)
    }
}

@Composable
private fun CountLabel(count: Int) {
    if (count > 0) Text(count.toString(), style = Runnel.type.body, color = Runnel.colors.secondaryLabel)
}
