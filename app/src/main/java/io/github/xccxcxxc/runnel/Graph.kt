package io.github.xccxcxxc.runnel

import android.app.Application
import io.github.xccxcxxc.runnel.content.FullTextFetcher
import io.github.xccxcxxc.runnel.content.ReaderImageCache
import io.github.xccxcxxc.runnel.data.Account
import io.github.xccxcxxc.runnel.data.AccountType
import io.github.xccxcxxc.runnel.data.Prefs
import io.github.xccxcxxc.runnel.data.SyncMode
import io.github.xccxcxxc.runnel.sync.DemoData
import io.github.xccxcxxc.runnel.sync.SyncState
import io.github.xccxcxxc.runnel.sync.SyncWorker
import io.github.xccxcxxc.runnel.sync.greader.GReaderApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** 手写依赖装配（App 很小，不引入 DI 框架）。账号相关的东西在各自的 [AccountSession] 里。 */
class AppGraph(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val prefs = Prefs(app)
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        // 很多站点（尤其挂了 Cloudflare 的）会拒绝默认的 okhttp UA，图标和图片都拿不到
        .addInterceptor { chain ->
            val req = chain.request()
            chain.proceed(if (req.header("User-Agent") != null) req else req.newBuilder().header("User-Agent", BROWSER_UA).build())
        }
        .build()
    val fullText = FullTextFetcher(http)
    /** 阅读页图片的磁盘缓存（按地址，所有账号共用），第一次用到时才建。 */
    val readerImages by lazy { ReaderImageCache(http, app.cacheDir.resolve("reader-images")) }

    private val sessions = HashMap<String, AccountSession>()

    val accounts: StateFlow<List<Account>> get() = prefs.accounts

    /** 账号的运行时（第一次用到时打开数据库）；账号已经移除返回 null。 */
    fun session(id: String): AccountSession? = synchronized(sessions) {
        sessions[id]?.let { return it }
        val account = prefs.account(id) ?: return null
        AccountSession(this, account).also { sessions[id] = it }
    }

    /** 启动时进哪个账号：上次打开的，没有就第一个。 */
    fun startAccount(): Account? = prefs.lastAccountId?.let(prefs::account) ?: prefs.accounts.value.firstOrNull()

    /** 有账号正在同步（帧统计用）。 */
    @OptIn(ExperimentalCoroutinesApi::class)
    val anySyncing: Flow<Boolean> = prefs.accounts.flatMapLatest { list ->
        val states = list.mapNotNull { session(it.id)?.sync?.state }
        if (states.isEmpty()) flowOf(false) else combine(states) { all -> all.any { it is SyncState.Running } }
    }

    /** 登录 GReader 兼容服务，成功后加账号、做首次同步（同步失败也保留账号，首页会显示原因）。 */
    suspend fun addGReaderAccount(type: AccountType, server: String, username: String, password: String): Account {
        val base = GReaderApi.normalizeBase(type, server)
        val token = GReaderApi(http, base).login(username, password)
        val account = prefs.addAccount(type, base, username, token)
        session(account.id)!!.sync.sync()
        scheduleBackgroundSync(account.id)
        return account
    }

    /** 本机账号：订阅源由 App 自己抓。 */
    fun addLocalAccount(): Account = prefs.addAccount(AccountType.LOCAL, "", "").also { scheduleBackgroundSync(it.id) }

    /** 演示数据只有一个，已经有了就直接用它。 */
    suspend fun addDemoAccount(): Account {
        prefs.accounts.value.firstOrNull { it.type == AccountType.DEMO }?.let { return it }
        val account = prefs.addAccount(AccountType.DEMO, "", "demo")
        val s = session(account.id)!!
        withContext(Dispatchers.IO) { DemoData.populate(s.db) }
        s.prefs.lastSyncAt = System.currentTimeMillis()
        return account
    }

    /**
     * 移除账号：停后台同步、删账号记录和设置，再关掉并删除它的数据库。服务器上的数据不受影响。
     * 调用前页面要先离开这个账号；数据库晚一点再关，让还在跑的查询（刚被移出组合的页面、账号列表的未读数）先结束。
     */
    fun removeAccount(id: String) {
        val account = prefs.account(id) ?: return
        SyncWorker.cancel(app, id)
        prefs.removeAccount(id)
        val session = synchronized(sessions) { sessions.remove(id) }
        session?.scope?.cancel()
        scope.launch(Dispatchers.IO) {
            delay(1_000)
            session?.close()
            app.deleteDatabase(account.dbName)
        }
    }

    /** 按账号的“同步方式”排后台同步（演示数据不同步）。 */
    fun scheduleBackgroundSync(id: String) {
        val account = prefs.account(id) ?: return
        if (account.type == AccountType.DEMO) return
        val s = prefs.accountPrefs(id).settings.value
        SyncWorker.schedule(app, id, if (s.syncMode == SyncMode.BACKGROUND) s.syncIntervalMinutes else 0)
    }

    /** 回到前台：同步方式不是“手动”的账号，距上次同步够久就同步。 */
    fun syncAllIfStale() {
        for (a in prefs.accounts.value) {
            if (prefs.accountPrefs(a.id).settings.value.syncMode == SyncMode.MANUAL) continue
            session(a.id)?.sync?.syncIfStale()
        }
    }
}

const val BROWSER_UA =
    "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

object Graph {
    private lateinit var graph: AppGraph
    fun init(app: Application) {
        graph = AppGraph(app)
    }

    fun get(): AppGraph = graph
}
