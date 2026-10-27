package io.github.xccxcxxc.runnel

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.CompositionLocalProvider
import io.github.xccxcxxc.runnel.data.Account
import io.github.xccxcxxc.runnel.perf.PerfMonitor
import io.github.xccxcxxc.runnel.perf.RefreshRate
import io.github.xccxcxxc.runnel.ui.components.SheetHost
import io.github.xccxcxxc.runnel.ui.components.SheetState
import io.github.xccxcxxc.runnel.ui.components.behindSheet
import io.github.xccxcxxc.runnel.ui.home.AccountsScreen
import io.github.xccxcxxc.runnel.ui.home.AddFeedSheet
import io.github.xccxcxxc.runnel.ui.home.EditFeedSheet
import io.github.xccxcxxc.runnel.ui.home.HomeScreen
import io.github.xccxcxxc.runnel.ui.list.ArticleListScreen
import io.github.xccxcxxc.runnel.ui.login.AddAccountScreen
import io.github.xccxcxxc.runnel.ui.login.LoginScreen
import io.github.xccxcxxc.runnel.ui.nav.NavHost
import io.github.xccxcxxc.runnel.ui.nav.Navigator
import io.github.xccxcxxc.runnel.ui.nav.Route
import io.github.xccxcxxc.runnel.ui.reader.ReaderScreen
import io.github.xccxcxxc.runnel.ui.reader.WebViewPool
import io.github.xccxcxxc.runnel.ui.search.SearchScreen
import io.github.xccxcxxc.runnel.ui.settings.SettingsSheet
import io.github.xccxcxxc.runnel.ui.theme.Runnel
import io.github.xccxcxxc.runnel.ui.theme.RunnelTheme
import io.github.xccxcxxc.runnel.ui.theme.Motion

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val graph = Graph.get()
        val autoDemo = intent.getBooleanExtra(EXTRA_DEMO, false)
        PerfMonitor.install(this)
        RefreshRate.apply(this, graph.prefs.settings.value.maxRefreshRate)
        setContent { RunnelRoot(this, autoDemo) }
        // 首帧之后空闲时预热 WebView，第一次打开文章不用等 Chromium 初始化
        window.decorView.post { WebViewPool.prewarm(this) }
    }

    override fun onStart() {
        super.onStart()
        // 同步方式为“手动”的账号不自动同步
        Graph.get().syncAllIfStale()
    }

    override fun onResume() {
        super.onResume()
        PerfMonitor.setTracking(true)
    }

    override fun onPause() {
        PerfMonitor.setTracking(false)
        super.onPause()
    }

    companion object {
        /** macrobenchmark 用：没有账号时自动载入演示数据。 */
        const val EXTRA_DEMO = "runnel.demo"
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun RunnelRoot(activity: MainActivity, autoDemo: Boolean) {
    val graph = Graph.get()
    val settings by graph.prefs.settings.collectAsStateWithLifecycle()

    // 设置 → 动画 → 速度：全局缩放所有弹簧动画
    SideEffect { Motion.speed = settings.animSpeed.factor }
    LaunchedEffect(settings.maxRefreshRate) {
        RefreshRate.apply(activity, settings.maxRefreshRate)
        PerfMonitor.updateRefreshRate(activity)
    }
    // 帧统计：卡顿时是否正在同步
    LaunchedEffect(Unit) { graph.anySyncing.collect { PerfMonitor.setSyncing(it) } }

    RunnelTheme(settings) {
        val scope = rememberCoroutineScope()
        val navigator = remember {
            // 有账号时进上次打开的账号的首页，下面垫着账号列表（照 Reeder，返回就到账号列表）
            val start = graph.startAccount()
            Navigator(if (start == null) listOf(Route.AddAccount) else listOf(Route.Accounts, Route.Home(start.id)), scope)
        }
        val sheet = remember { SheetState<Sheet>(scope) }
        val dark = Runnel.colors.isDark
        val view = LocalView.current
        SideEffect {
            val controller = WindowCompat.getInsetsController(activity.window, view)
            // 表单弹出后状态栏后面是黑底（下层页面缩小露出的部分），图标改成浅色
            controller.isAppearanceLightStatusBars = !dark && !sheet.isOpen
            controller.isAppearanceLightNavigationBars = !dark
        }
        LaunchedEffect(navigator.top, sheet.content) {
            PerfMonitor.setScreen(
                when (sheet.content) {
                    Sheet.Settings -> "Settings"
                    is Sheet.AddFeed, is Sheet.EditFeed -> "AddFeed"
                    null -> navigator.top.route.perfName
                },
            )
        }
        // 账号加好了：账号列表垫底，进它的首页
        fun opened(account: Account) = navigator.replaceAll(Route.Accounts, Route.Home(account.id))
        Box(
            Modifier
                .fillMaxSize()
                .background(Runnel.colors.background)
                .drawBehind { if (sheet.progress > 0f) drawRect(Color.Black, alpha = sheet.progress) }
                .semantics { testTagsAsResourceId = true },
        ) {
            Box(Modifier.fillMaxSize().behindSheet(sheet)) {
                NavHost(navigator) { entry ->
                    val route = entry.route
                    val accountId = route.accountId
                    if (accountId == null) {
                        when (route) {
                            Route.AddAccount -> AddAccountScreen(
                                autoDemo = autoDemo,
                                canGoBack = navigator.stack.firstOrNull()?.route != Route.AddAccount,
                                onBack = navigator::pop,
                                onServer = { navigator.push(Route.Login(it)) },
                                onAdded = ::opened,
                            )
                            is Route.Login -> LoginScreen(route.type, onBack = navigator::pop, onAdded = ::opened)
                            Route.Accounts -> AccountsScreen(
                                onOpen = { navigator.push(Route.Home(it)) },
                                onSettings = { sheet.show(Sheet.Settings) },
                                onAdd = { navigator.push(Route.AddAccount) },
                            )
                            else -> Unit
                        }
                        return@NavHost
                    }
                    // 账号刚被移除、页面还没出栈的那一帧：什么都不画
                    val session = remember(accountId) { graph.session(accountId) } ?: return@NavHost
                    CompositionLocalProvider(LocalAccount provides session) {
                        when (route) {
                            is Route.Home -> HomeScreen(
                                onBack = navigator::pop,
                                onOpen = { source, filter -> navigator.push(Route.Articles(accountId, source, filter)) },
                                onSearch = { navigator.push(Route.Search(accountId)) },
                                onAdd = { sheet.show(Sheet.AddFeed(accountId)) },
                                onEditFeed = { sheet.show(Sheet.EditFeed(accountId, it)) },
                            )
                            is Route.Articles -> ArticleListScreen(
                                source = route.source,
                                filter = route.filter,
                                onBack = navigator::pop,
                                onOpenArticle = { navigator.push(Route.Reader(accountId, it)) },
                                onReplace = { source, filter -> navigator.replaceTop(Route.Articles(accountId, source, filter)) },
                            )
                            is Route.Reader -> ReaderScreen(route.session, onBack = navigator::pop)
                            is Route.Search -> SearchScreen(
                                onBack = navigator::pop,
                                onOpenArticle = { navigator.push(Route.Reader(accountId, it)) },
                            )
                            else -> Unit
                        }
                    }
                }
            }
            SheetHost(sheet) { kind ->
                when (kind) {
                    Sheet.Settings -> SettingsSheet(
                        onDone = { sheet.dismiss() },
                        onAddAccount = {
                            sheet.dismiss()
                            navigator.push(Route.AddAccount)
                        },
                        onRemoveAccount = { id ->
                            // 先离开这个账号的页面、收起表单，再删它的数据库
                            sheet.hideNow()
                            val rest = graph.accounts.value.filter { it.id != id }
                            if (rest.isEmpty()) navigator.replaceAll(Route.AddAccount) else navigator.replaceAll(Route.Accounts)
                            graph.removeAccount(id)
                        },
                    )
                    is Sheet.AddFeed -> graph.session(kind.accountId)?.let { s ->
                        CompositionLocalProvider(LocalAccount provides s) { AddFeedSheet(onDone = { sheet.dismiss() }) }
                    }
                    is Sheet.EditFeed -> graph.session(kind.accountId)?.let { s ->
                        CompositionLocalProvider(LocalAccount provides s) { EditFeedSheet(kind.feedId, onDone = { sheet.dismiss() }) }
                    }
                }
            }
        }
    }
}

/** 从底部弹出的表单（设置、添加/编辑订阅都是表单，不是推入的页面）。 */
private sealed interface Sheet {
    data object Settings : Sheet
    data class AddFeed(val accountId: String) : Sheet
    data class EditFeed(val accountId: String, val feedId: String) : Sheet
}
