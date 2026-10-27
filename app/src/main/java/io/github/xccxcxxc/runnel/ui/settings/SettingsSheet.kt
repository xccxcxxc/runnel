package io.github.xccxcxxc.runnel.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.xccxcxxc.runnel.BuildConfig
import io.github.xccxcxxc.runnel.Graph
import io.github.xccxcxxc.runnel.data.AccountType
import io.github.xccxcxxc.runnel.data.AnimSpeed
import io.github.xccxcxxc.runnel.data.AppFont
import io.github.xccxcxxc.runnel.data.GroupBy
import io.github.xccxcxxc.runnel.data.ImagePreview
import io.github.xccxcxxc.runnel.data.OpenLinks
import io.github.xccxcxxc.runnel.data.PullBottom
import io.github.xccxcxxc.runnel.data.ReaderFont
import io.github.xccxcxxc.runnel.data.ReaderView
import io.github.xccxcxxc.runnel.data.SortSubs
import io.github.xccxcxxc.runnel.data.SwipeKind
import io.github.xccxcxxc.runnel.data.SyncMode
import io.github.xccxcxxc.runnel.data.Settings
import io.github.xccxcxxc.runnel.data.ThemeMode
import io.github.xccxcxxc.runnel.data.db.FeedEntity
import io.github.xccxcxxc.runnel.perf.PerfMonitor
import io.github.xccxcxxc.runnel.sync.Opml
import io.github.xccxcxxc.runnel.ui.home.TextPrompt
import io.github.xccxcxxc.runnel.ui.components.BarButton
import io.github.xccxcxxc.runnel.ui.components.BarTextButton
import io.github.xccxcxxc.runnel.ui.components.RunnelIcons
import io.github.xccxcxxc.runnel.ui.components.CheckRow
import io.github.xccxcxxc.runnel.ui.components.GroupedRow
import io.github.xccxcxxc.runnel.ui.components.GroupedSection
import io.github.xccxcxxc.runnel.ui.components.NavRow
import io.github.xccxcxxc.runnel.ui.components.PickerRow
import io.github.xccxcxxc.runnel.ui.components.RowDivider
import io.github.xccxcxxc.runnel.ui.components.SegmentedRow
import io.github.xccxcxxc.runnel.ui.components.SliderRow
import io.github.xccxcxxc.runnel.ui.components.SquareToggleGroup
import io.github.xccxcxxc.runnel.ui.components.SheetPage
import io.github.xccxcxxc.runnel.ui.components.SheetPageHost
import io.github.xccxcxxc.runnel.ui.components.SheetPages
import io.github.xccxcxxc.runnel.ui.components.SwitchRow
import io.github.xccxcxxc.runnel.ui.syncedAgo
import io.github.xccxcxxc.runnel.ui.theme.Runnel
import io.github.xccxcxxc.runnel.ui.theme.FontStepLabels
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/** 设置的各级页面。顶层是目录，点进去是二级页面（账号 / 通用 / 外观 / 阅读 …）。账号相关的页面带账号 id。 */
private sealed class SettingsPage(val title: String) {
    data object Root : SettingsPage("设置")
    data class AccountPage(val id: String) : SettingsPage("账号")
    data class ReaderViewPage(val id: String) : SettingsPage("Reader View")
    data class OfflineImagesPage(val id: String) : SettingsPage("离线图片")
    data object General : SettingsPage("通用")
    data object Appearance : SettingsPage("外观")
    data object Reading : SettingsPage("阅读")
    data object Animations : SettingsPage("动画")
    data object Gestures : SettingsPage("手势")
    data object Performance : SettingsPage("手感与性能")
    data object Licenses : SettingsPage("开源许可")
}

/** 设置面板：从账号列表左上角打开，以底部表单的形式弹出（见 [io.github.xccxcxxc.runnel.ui.components.SheetHost]）。 */
@Composable
fun SettingsSheet(onDone: () -> Unit, onAddAccount: () -> Unit, onRemoveAccount: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val pages = remember { SheetPages<SettingsPage>(SettingsPage.Root, scope) }
    SheetPageHost(pages) { page ->
        SheetPage(
            title = page.title,
            subtitle = (page as? SettingsPage.AccountPage)?.let { Graph.get().prefs.account(it.id) }?.takeIf { it.isRemote }?.username,
            leading = { if (page != SettingsPage.Root) BarButton(RunnelIcons.Back, "返回", onClick = pages::pop) },
            trailing = { BarTextButton("完成", color = Runnel.colors.accent, style = Runnel.type.headline, onClick = onDone) },
        ) {
            when (page) {
                SettingsPage.Root -> rootPage(pages::push, onAddAccount)
                is SettingsPage.AccountPage -> item(key = "account") { AccountPage(page.id, onRemoveAccount, pages::push) }
                is SettingsPage.ReaderViewPage -> item(key = "readerView") { ReaderViewPage(page.id) }
                is SettingsPage.OfflineImagesPage -> item(key = "offlineImages") { OfflineImagesPage(page.id) }
                SettingsPage.General -> item(key = "general") { GeneralPage() }
                SettingsPage.Appearance -> item(key = "appearance") { AppearancePage() }
                SettingsPage.Reading -> item(key = "reading") { ReadingPage() }
                SettingsPage.Animations -> item(key = "animations") { AnimationsPage() }
                SettingsPage.Gestures -> item(key = "gestures") { GesturesPage() }
                SettingsPage.Performance -> item(key = "performance") { PerformancePage() }
                SettingsPage.Licenses -> item(key = "licenses") { LicensesPage() }
            }
        }
    }
}

private fun LazyListScope.rootPage(open: (SettingsPage) -> Unit, onAddAccount: () -> Unit) {
    item(key = "account") {
        val accounts by Graph.get().prefs.accounts.collectAsStateWithLifecycle()
        GroupedSection("账号") {
            for (a in accounts) {
                NavRow(a.displayName, value = a.username.takeIf { a.isRemote }) { open(SettingsPage.AccountPage(a.id)) }
                RowDivider()
            }
            GroupedRow("添加账号…", titleColor = Runnel.colors.accent, onClick = onAddAccount)
        }
    }
    item(key = "sections") {
        GroupedSection {
            NavRow("通用") { open(SettingsPage.General) }
            RowDivider()
            NavRow("外观") { open(SettingsPage.Appearance) }
            RowDivider()
            NavRow("阅读") { open(SettingsPage.Reading) }
            RowDivider()
            NavRow("动画") { open(SettingsPage.Animations) }
            RowDivider()
            NavRow("手势") { open(SettingsPage.Gestures) }
            RowDivider()
            NavRow("手感与性能") { open(SettingsPage.Performance) }
        }
    }
    item(key = "about") {
        GroupedSection {
            GroupedRow("版本", value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            RowDivider()
            NavRow("开源许可") { open(SettingsPage.Licenses) }
        }
        Text(
            "Runnel — 跟手、流畅的 RSS 阅读器",
            style = Runnel.type.caption,
            color = Runnel.colors.tertiaryLabel,
            modifier = Modifier.padding(start = 32.dp, top = 16.dp),
        )
    }
}

@Composable
private fun AccountPage(id: String, onRemove: (String) -> Unit, open: (SettingsPage) -> Unit) {
    val graph = Graph.get()
    val session = graph.session(id) ?: return
    val prefs = session.prefs
    val a by session.account.collectAsStateWithLifecycle()
    val settings by prefs.settings.collectAsStateWithLifecycle()
    val lastSync by session.sync.lastSyncAt.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val colors = Runnel.colors
    var editName by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    val local = a.type == AccountType.LOCAL
    val demo = a.type == AccountType.DEMO

    fun toast(text: String) = android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()

    // 导出 OPML：先让用户选保存位置，再把本地的订阅源和文件夹写进去
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-opml")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val folders = session.db.feedDao().allFolders()
                    val feeds = session.db.feedDao().allFeeds()
                    val xml = Opml.build(a.displayName, folders, feeds)
                    context.contentResolver.openOutputStream(uri)?.use { it.write(xml.toByteArray()) } ?: error("无法写入文件")
                }.isSuccess
            }
            toast(if (ok) "已导出订阅" else "导出失败")
        }
    }
    // 导入 OPML（本机账号）：读文件 → 建订阅源和文件夹 → 去抓
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching {
                val entries = withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("无法读取文件")
                    Opml.parse(bytes)
                }
                if (entries.isEmpty()) error("文件里没有订阅源")
                entries.size to session.importOpml(entries)
            }
            result.onSuccess { (total, added) ->
                toast(if (added == 0) "这 $total 个订阅源都已经订阅了" else "已导入 $added 个订阅源，正在更新")
            }.onFailure { toast("导入失败：${it.message ?: it.javaClass.simpleName}") }
        }
    }

    GroupedSection("常规") {
        GroupedRow("名称", value = a.displayName, onClick = { editName = a.description })
        RowDivider()
        GroupedRow("类型", value = a.type.label)
        if (a.isRemote) {
            RowDivider()
            GroupedRow("服务器", value = a.apiBase, valueColor = colors.tertiaryLabel)
        }
    }
    if (!demo) {
        GroupedSection(
            "同步",
            footer = (if (local) "订阅源由这台设备自己抓取。" else "抓取订阅由服务器完成。") +
                "“启动时”：打开 App 时同步；“后台刷新”：另外按下面的间隔在后台同步（部分系统会限制后台）。",
        ) {
            PickerRow(
                "同步",
                listOf(SyncMode.MANUAL to "手动", SyncMode.ON_START to "启动时", SyncMode.BACKGROUND to "后台刷新"),
                settings.syncMode,
            ) { mode ->
                prefs.update { it.copy(syncMode = mode) }
                graph.scheduleBackgroundSync(id)
            }
            if (settings.syncMode == SyncMode.BACKGROUND) {
                RowDivider()
                PickerRow(
                    "后台同步间隔",
                    listOf(30 to "30 分钟", 60 to "1 小时", 180 to "3 小时"),
                    settings.syncIntervalMinutes,
                ) { minutes ->
                    prefs.update { it.copy(syncIntervalMinutes = minutes) }
                    graph.scheduleBackgroundSync(id)
                }
            }
            RowDivider()
            PickerRow(
                "保留已读文章",
                listOf(-1 to "一直保留", 0 to "不保留", 1 to "1 天", 2 to "2 天", 3 to "3 天", 7 to "1 周", 14 to "2 周", 30 to "1 个月"),
                settings.keepDays,
            ) { days -> prefs.update { it.copy(keepDays = days) } }
        }
    }
    GroupedSection("阅读", footer = "开启“滚动时标为已读”后，向下滚动时滑出屏幕的文章会自动标为已读；向上滚动不会。") {
        NavRow("Reader View（全文）") { open(SettingsPage.ReaderViewPage(id)) }
        RowDivider()
        NavRow("离线图片") { open(SettingsPage.OfflineImagesPage(id)) }
        RowDivider()
        PickerRow(
            "订阅源排序",
            listOf(
                SortSubs.ALPHA to "按名称", SortSubs.FEEDS_FIRST to "订阅源优先",
                SortSubs.FOLDERS_FIRST to "文件夹优先", SortSubs.UNREAD to "按未读数",
            ),
            settings.sortSubs,
        ) { v -> prefs.update { it.copy(sortSubs = v) } }
        RowDivider()
        PickerRow("标记文章为已读", listOf(true to "打开时", false to "手动"), settings.markReadOnOpen) { v ->
            prefs.update { it.copy(markReadOnOpen = v) }
        }
        RowDivider()
        SwitchRow("滚动时标为已读", settings.markReadOnScroll) { v -> prefs.update { it.copy(markReadOnScroll = v) } }
    }
    GroupedSection("订阅", footer = if (local) "导入别的阅读器导出的 OPML 文件：已经订阅的跳过，文件夹照原样建好。" else null) {
        if (session.canImportOpml) {
            GroupedRow("从 OPML 导入…", centered = true, onClick = { importLauncher.launch(arrayOf("*/*")) })
            RowDivider()
        }
        GroupedRow("导出为 OPML…", centered = true, onClick = { exportLauncher.launch("Runnel.opml") })
    }
    GroupedSection("账号") {
        if (!demo) {
            GroupedRow("立即同步", value = syncedAgo(lastSync), titleColor = colors.accent, onClick = { session.sync.requestSync() })
            RowDivider()
        }
        GroupedRow("移除账号…", centered = true, titleColor = colors.destructive, onClick = { confirmRemove = true })
    }

    editName?.let { text ->
        TextPrompt(
            title = "账号名称",
            value = text,
            onChange = { editName = it },
            onConfirm = {
                graph.prefs.setAccountDescription(id, text)
                editName = null
            },
            onDismiss = { editName = null },
        )
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("移除账号「${a.displayName}」？") },
            text = {
                Text(
                    if (a.isRemote) "会移除这个账号，并清掉这台手机上缓存的订阅和文章。服务器上的数据不受影响。"
                    else if (local) "这个账号的订阅源、文章和星标都只存在这台设备上，移除后无法恢复。可以先导出 OPML 备份订阅源。"
                    else "会移除演示数据。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    onRemove(id)
                }) { Text("移除", color = colors.destructive) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("取消", color = colors.icon) } },
            containerColor = colors.cell,
        )
    }
}

/** 账号 → Reader View：默认提取器 + 勾选默认用 Reader View（自动抓全文）打开的订阅源。 */
@Composable
private fun ReaderViewPage(id: String) {
    val session = Graph.get().session(id) ?: return
    val prefs = session.prefs
    val settings by prefs.settings.collectAsStateWithLifecycle()
    val feeds by produceState(emptyList<FeedEntity>()) {
        value = withContext(Dispatchers.IO) { session.db.feedDao().allFeeds() }.sortedBy { it.title.lowercase() }
    }
    // 旧版全局 Readability 等于全选；在这里改过一次后就换成按订阅源保存
    fun update(selected: (Set<String>) -> Set<String>) {
        if (feeds.isEmpty()) return // 订阅源还没读出来时不写，免得把旧版的“全选”清掉
        prefs.update { s ->
            val current = if (s.readerView == ReaderView.READABILITY) feeds.mapTo(HashSet()) { it.id } else s.readerViewFeeds
            s.copy(readerView = ReaderView.DEFAULT, readerViewFeeds = selected(current))
        }
    }

    GroupedSection(footer = "选择默认用 Reader View 打开的订阅源：打开这些订阅源的文章时会自动抓取原网页全文。") {
        PickerRow("默认提取器", listOf(Unit to "Readability"), Unit) {}
    }
    GroupedSection {
        GroupedRow("全选", centered = true, onClick = { update { feeds.mapTo(HashSet()) { it.id } } })
        RowDivider()
        GroupedRow("全不选", centered = true, onClick = { update { emptySet() } })
    }
    if (feeds.isNotEmpty()) {
        GroupedSection("订阅源") {
            feeds.forEachIndexed { i, feed ->
                if (i > 0) RowDivider()
                CheckRow(feed.title, settings.autoReaderView(feed.id)) {
                    update { if (feed.id in it) it - feed.id else it + feed.id }
                }
            }
        }
    }
}

/** 账号 → 离线图片：勾选的订阅源，同步后预先下载未读和星标文章的正文图片，没网也能看。 */
@Composable
private fun OfflineImagesPage(id: String) {
    val graph = Graph.get()
    val session = graph.session(id) ?: return
    val prefs = session.prefs
    val settings by prefs.settings.collectAsStateWithLifecycle()
    val feeds by produceState(emptyList<FeedEntity>()) {
        value = withContext(Dispatchers.IO) { session.db.feedDao().allFeeds() }.sortedBy { it.title.lowercase() }
    }
    val used by produceState(0L) {
        while (true) {
            value = withContext(Dispatchers.IO) { graph.readerImages.usedBytes() }
            delay(2_000)
        }
    }
    fun update(selected: (Set<String>) -> Set<String>) {
        prefs.update { it.copy(offlineImageFeeds = selected(it.offlineImageFeeds)) }
        // 新勾的订阅源马上开始下，不等下次同步
        session.offlineImages.requestPrefetch()
    }

    val mb = { bytes: Long -> "%.0f MB".format(Locale.US, bytes / 1024.0 / 1024.0) }
    GroupedSection(
        footer = "勾选的订阅源，每次同步后会把未读和加了星标的文章里的图片下载好，没网时打开也能看到。" +
            "不限 Wi-Fi。图片缓存已用 ${mb(used)}，上限 ${mb(graph.readerImages.maxBytes)}，满了会先清掉最久没用的。",
    ) {
        GroupedRow("全选", centered = true, onClick = { update { feeds.mapTo(HashSet()) { it.id } } })
        RowDivider()
        GroupedRow("全不选", centered = true, onClick = { update { emptySet() } })
    }
    if (feeds.isNotEmpty()) {
        GroupedSection("订阅源") {
            feeds.forEachIndexed { i, feed ->
                if (i > 0) RowDivider()
                CheckRow(feed.title, feed.id in settings.offlineImageFeeds) {
                    update { if (feed.id in it) it - feed.id else it + feed.id }
                }
            }
        }
    }
}

@Composable
private fun GeneralPage() {
    val graph = Graph.get()
    val prefs = graph.prefs
    val settings by prefs.settings.collectAsStateWithLifecycle()
    GroupedSection("浏览器") {
        PickerRow("打开链接", listOf(OpenLinks.DEFAULT_BROWSER to "默认浏览器", OpenLinks.IN_APP to "应用内浏览器"), settings.openLinks) { v ->
            prefs.updateSettings { it.copy(openLinks = v) }
        }
    }
    GroupedSection("文章列表", footer = "排序和分组在下次打开列表时生效。") {
        PickerRow("未读文章排序", listOf(true to "最旧优先", false to "最新优先"), settings.oldestFirstUnread) { v ->
            prefs.updateSettings { it.copy(oldestFirstUnread = v) }
        }
        RowDivider()
        PickerRow("分组方式", listOf(GroupBy.DATE to "日期", GroupBy.FEED to "订阅源", GroupBy.NONE to "不分组"), settings.groupBy) { v ->
            prefs.updateSettings { it.copy(groupBy = v) }
        }
    }
    GroupedSection(
        "全部标为已读",
        footer = "“打开下一个订阅源”：全部标为已读后，直接打开下一个有内容的订阅源（或文件夹）；没有下一个就退回上一级。关闭时直接退回上一级。",
    ) {
        SwitchRow("操作前先询问", settings.markAllAsk) { v -> prefs.updateSettings { it.copy(markAllAsk = v) } }
        RowDivider()
        SwitchRow("全部已读后打开下一个订阅源", settings.markAllOpenNext) { v -> prefs.updateSettings { it.copy(markAllOpenNext = v) } }
        RowDivider()
        PickerRow("按钮位置", listOf(false to "右边", true to "左边"), settings.markAllButtonLeft) { left ->
            prefs.updateSettings { it.copy(markAllButtonLeft = left) }
        }
    }
}

@Composable
private fun AppearancePage() {
    val prefs = Graph.get().prefs
    val settings by prefs.settings.collectAsStateWithLifecycle()
    GroupedSection("常规") {
        PickerRow("主题", listOf(ThemeMode.SYSTEM to "跟随系统", ThemeMode.LIGHT to "浅色", ThemeMode.DARK to "深色"), settings.theme) { v ->
            prefs.updateSettings { it.copy(theme = v) }
        }
        RowDivider()
        SwitchRow("订阅源图标灰度显示", settings.grayFavicons) { v -> prefs.updateSettings { it.copy(grayFavicons = v) } }
    }
    AppIconSection()
    GroupedSection("文字") {
        PickerRow("App 字体", listOf(AppFont.SANS to "无衬线", AppFont.SERIF to "衬线"), settings.appFont) { v ->
            prefs.updateSettings { it.copy(appFont = v) }
        }
        RowDivider()
        SwitchRow("使用系统字号", settings.useSystemFontSize) { v -> prefs.updateSettings { it.copy(useSystemFontSize = v) } }
        RowDivider()
        SliderRow(
            "大小", settings.appFontStep.toFloat(), 0f..4f, steps = 3,
            display = FontStepLabels[settings.appFontStep.coerceIn(0, 4)], enabled = !settings.useSystemFontSize,
        ) { v -> prefs.updateSettings { it.copy(appFontStep = v.toInt()) } }
    }
    GroupedSection("显示数量") {
        SwitchRow("星标列表", settings.countStarred) { v -> prefs.updateSettings { it.copy(countStarred = v) } }
        RowDivider()
        SwitchRow("未读列表", settings.countUnread) { v -> prefs.updateSettings { it.copy(countUnread = v) } }
        RowDivider()
        SwitchRow("全部文章列表", settings.countAll) { v -> prefs.updateSettings { it.copy(countAll = v) } }
    }
    GroupedSection("文章列表") {
        PickerRow(
            "图片预览",
            listOf(ImagePreview.NONE to "关闭", ImagePreview.SMALL to "小", ImagePreview.MEDIUM to "中", ImagePreview.LARGE to "大"),
            settings.imagePreview,
        ) { v -> prefs.updateSettings { it.copy(imagePreview = v) } }
        RowDivider()
        SwitchRow("显示订阅源图标", settings.showFavicons) { v -> prefs.updateSettings { it.copy(showFavicons = v) } }
        RowDivider()
        SwitchRow("文字预览", settings.textPreview) { v -> prefs.updateSettings { it.copy(textPreview = v) } }
    }
}

@Composable
private fun ReadingPage() {
    val prefs = Graph.get().prefs
    val settings by prefs.settings.collectAsStateWithLifecycle()
    GroupedSection(footer = "Reader View 抓取原网页并提取全文用的方式（目前只有 Readability）。哪些订阅源默认用 Reader View 打开，在 设置 → 账号 → Reader View 里选；阅读页底栏的“全文”按钮随时可以手动切换。") {
        PickerRow("Reader View", listOf(Unit to "Readability"), Unit) {}
    }
    ReaderStyleSections(compact = false)
    GroupedSection("行为", footer = "“读完后进入下一个订阅源”：在阅读页读到该来源的最后一篇后继续上拉，会把当前订阅源（或文件夹）的文章全部标为已读，并打开下一个有内容的来源。") {
        SwitchRow("读完后进入下一个订阅源", settings.autoNextFeed) { v -> prefs.updateSettings { it.copy(autoNextFeed = v) } }
    }
    ReaderStyleReset()
}

@Composable
private fun GesturesPage() {
    val prefs = Graph.get().prefs
    val settings by prefs.settings.collectAsStateWithLifecycle()
    // 选项顺序；Android 没有 Safari 阅读列表，不做 “Add to Reading List”
    val kinds = listOf(
        SwipeKind.NONE to "无", SwipeKind.TOGGLE_READ to "切换已读", SwipeKind.TOGGLE_STAR to "切换星标",
        SwipeKind.OPEN_BROWSER to "在默认浏览器打开", SwipeKind.SHARE to "分享", SwipeKind.ACTION_SHEET to "操作菜单",
    )
    GroupedSection("文章列表") {
        PickerRow("右滑", kinds, settings.swipeRight) { v -> prefs.updateSettings { it.copy(swipeRight = v) } }
        RowDivider()
        PickerRow("左滑", kinds, settings.swipeLeft) { v -> prefs.updateSettings { it.copy(swipeLeft = v) } }
    }
    GroupedSection(footer = "列表已经到底时继续往上拉，出现提示并震动后松手就会执行，然后离开这个列表。“进入下一个订阅源”会先把这个列表的未读全部标为已读（不询问）。“全部标为已读”遵守 通用 → 全部标为已读 的设置（先询问、打开下一个订阅源）；没有未读时直接离开。") {
        PickerRow(
            "从底部上拉",
            listOf(PullBottom.NONE to "无", PullBottom.NEXT_FEED to "进入下一个订阅源", PullBottom.MARK_ALL_READ to "全部标为已读"),
            settings.pullBottom,
        ) { v -> prefs.updateSettings { it.copy(pullBottom = v) } }
    }
    GroupedSection("阅读页") {
        SwitchRow("拉动切换上一篇/下一篇", settings.pullToSwitch) { v -> prefs.updateSettings { it.copy(pullToSwitch = v) } }
    }
}

@Composable
private fun AnimationsPage() {
    val prefs = Graph.get().prefs
    val settings by prefs.settings.collectAsStateWithLifecycle()
    GroupedSection("常规", footer = "速度会影响页面转场、底部表单、翻页等所有弹簧动画。") {
        PickerRow(
            "速度",
            listOf(AnimSpeed.SLOW to "慢", AnimSpeed.DEFAULT to "默认", AnimSpeed.FAST to "快", AnimSpeed.FASTER to "更快"),
            settings.animSpeed,
        ) { v -> prefs.updateSettings { it.copy(animSpeed = v) } }
    }
    GroupedSection("文章") {
        SwitchRow("上一篇/下一篇", settings.animArticleSwitch) { v -> prefs.updateSettings { it.copy(animArticleSwitch = v) } }
    }
    GroupedSection("列表") {
        SwitchRow("列表加载", settings.animListLoad) { v -> prefs.updateSettings { it.copy(animListLoad = v) } }
    }
}

@Composable
private fun PerformancePage() {
    val prefs = Graph.get().prefs
    val settings by prefs.settings.collectAsStateWithLifecycle()
    GroupedSection(footer = "柔和惯性：快速滑动后滑行更远、减速更柔和。最高刷新率：请求屏幕支持的最高刷新率（如 120Hz）。") {
        SwitchRow("柔和惯性滚动", settings.iosFling) { v -> prefs.updateSettings { it.copy(iosFling = v) } }
        RowDivider()
        SwitchRow("使用最高刷新率", settings.maxRefreshRate) { v -> prefs.updateSettings { it.copy(maxRefreshRate = v) } }
    }
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }
    val stats = remember(tick) { PerfMonitor.snapshot() }
    GroupedSection("帧统计", footer = "统计本次启动以来的帧（JankStats）。反馈卡顿时，先点“重新统计”，操作一段时间后把这里的数字截图发给开发者。") {
        GroupedRow("总帧数", value = stats.total.toString())
        RowDivider()
        GroupedRow("卡顿帧", value = "${stats.janky}（${"%.2f".format(stats.jankPercent)}%）")
        RowDivider()
        GroupedRow("最慢一帧", value = "${"%.1f".format(stats.worstMs)} ms")
        RowDivider()
        GroupedRow("刷新率", value = "${"%.0f".format(stats.refreshRate)} Hz")
        for ((name, st, worst) in remember(tick) { PerfMonitor.perScreenSnapshot() }) {
            RowDivider()
            GroupedRow(
                "· ${screenLabel(name)}",
                value = "${st.janky}/${st.total} 帧卡顿，最慢 ${"%.0f".format(worst)} ms",
            )
        }
        RowDivider()
        GroupedRow("重新统计", titleColor = Runnel.colors.accent, onClick = { PerfMonitor.reset() })
    }
    val byAction = remember(tick) { PerfMonitor.perActionSnapshot() }
    if (byAction.isNotEmpty()) {
        GroupedSection("按操作") {
            byAction.forEachIndexed { i, (name, st, worst) ->
                if (i > 0) RowDivider()
                GroupedRow(name, value = "${st.janky}/${st.total} 帧卡顿，最慢 ${"%.0f".format(worst)} ms")
            }
        }
    }
    val recent = remember(tick) { PerfMonitor.recentJanks() }
    if (recent.isNotEmpty()) {
        val clock = remember { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()) }
        GroupedSection("最近卡顿", footer = "最多记 12 次，新的在前：时间、页面、当时在做什么、这一帧的时长。") {
            recent.forEachIndexed { i, e ->
                if (i > 0) RowDivider()
                GroupedRow("${clock.format(java.util.Date(e.timeMillis))}  ${screenLabel(e.screen)} · ${e.action}", value = "${"%.0f".format(e.ms)} ms")
            }
        }
    }
}

private fun screenLabel(name: String) = when (name) {
    "Home" -> "首页"; "Articles" -> "文章列表"; "Reader" -> "阅读页"
    "Settings" -> "设置"; "AddFeed" -> "添加订阅"; "Search" -> "搜索"; "Login" -> "登录"; "Accounts" -> "账号列表"; else -> name
}
