package io.github.xccxcxxc.runnel

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.xccxcxxc.runnel.content.OfflineImages
import io.github.xccxcxxc.runnel.data.Account
import io.github.xccxcxxc.runnel.data.AccountPrefs
import io.github.xccxcxxc.runnel.data.AccountType
import io.github.xccxcxxc.runnel.data.ArticleRepository
import io.github.xccxcxxc.runnel.data.FlagStore
import io.github.xccxcxxc.runnel.data.db.RunnelDatabase
import io.github.xccxcxxc.runnel.sync.AccountBackend
import io.github.xccxcxxc.runnel.sync.DemoBackend
import io.github.xccxcxxc.runnel.sync.SyncEngine
import io.github.xccxcxxc.runnel.sync.greader.GReaderBackend
import io.github.xccxcxxc.runnel.sync.local.FeedFetcher
import io.github.xccxcxxc.runnel.sync.local.LocalBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn

/**
 * 一个账号在内存里的全部东西：自己的数据库、设置、已读/星标覆盖层、同步引擎、文章仓库、离线图片。
 * 不同账号的订阅源 id、文章 id 可能重复，所以这些都按账号分开（[AppGraph.session]）。
 */
class AccountSession(graph: AppGraph, initial: Account) {
    val id: String = initial.id
    val type: AccountType = initial.type
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 账号信息（改名后跟着变）。 */
    val account: StateFlow<Account> = graph.prefs.accounts
        .mapNotNull { list -> list.firstOrNull { it.id == id } }
        .stateIn(scope, SharingStarted.Eagerly, initial)

    val prefs: AccountPrefs = graph.prefs.accountPrefs(id)
    val db: RunnelDatabase = RunnelDatabase.create(graph.app, initial.dbName)
    val flags = FlagStore()

    private val localBackend: LocalBackend? = if (type == AccountType.LOCAL) LocalBackend(db, FeedFetcher(graph.http)) else null
    private val backend: AccountBackend = when {
        type.isGReader -> GReaderBackend(initial, db, prefs, graph.http, flags) { graph.prefs.authToken(id) }
        localBackend != null -> localBackend
        else -> DemoBackend(db)
    }

    val offlineImages = OfflineImages(db, prefs, { graph.readerImages }, scope)
    val sync = SyncEngine(db, prefs, backend, graph.http, scope, afterSync = { offlineImages.requestPrefetch() })
    val repo = ArticleRepository(db, graph.prefs, flags, sync, graph.fullText)

    /** 本机账号才能导入 OPML（服务器账号在服务器上导入）。 */
    val canImportOpml: Boolean get() = localBackend != null

    /** 导入 OPML（只有本机账号），返回新增的订阅源数，然后去抓这些源。 */
    suspend fun importOpml(entries: List<io.github.xccxcxxc.runnel.sync.OpmlEntry>): Int {
        val added = localBackend?.import(entries) ?: 0
        if (added > 0) sync.requestSyncAfterCurrent()
        return added
    }

    fun close() {
        scope.cancel()
        db.close()
    }
}

/** 当前页面所属的账号（首页、文章列表、阅读页、搜索都在某个账号里）。 */
val LocalAccount = staticCompositionLocalOf<AccountSession> { error("no account") }
