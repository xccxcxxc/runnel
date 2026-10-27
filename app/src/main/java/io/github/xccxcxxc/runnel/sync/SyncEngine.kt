package io.github.xccxcxxc.runnel.sync

import android.util.Log
import io.github.xccxcxxc.runnel.data.AccountPrefs
import io.github.xccxcxxc.runnel.data.db.FeedEntity
import io.github.xccxcxxc.runnel.data.db.RunnelDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

sealed interface SyncState {
    data object Idle : SyncState
    data class Running(val message: String) : SyncState
    data class Failed(val message: String) : SyncState
}

/**
 * 一个账号的同步引擎：同步状态、同一时间只跑一次、离线操作的延迟提交、同步后的清理和找图标。
 * 具体怎么同步由 [backend] 决定（服务器 / 本机抓取 / 演示数据）。
 */
class SyncEngine(
    private val db: RunnelDatabase,
    private val prefs: AccountPrefs,
    private val backend: AccountBackend,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    /** 同步成功后调用（离线图片在这里开始预下载，不拖慢同步）。 */
    private val afterSync: () -> Unit = {},
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    private val _lastSyncAt = MutableStateFlow(prefs.lastSyncAt)
    val lastSyncAt: StateFlow<Long> = _lastSyncAt.asStateFlow()

    /** 每次同步插入新文章后加一，列表据此显示“N 篇新文章”。 */
    private val _dataVersion = MutableStateFlow(0L)
    val dataVersion: StateFlow<Long> = _dataVersion.asStateFlow()

    private var flushJob: Job? = null

    val queuesActions: Boolean get() = backend.queuesActions
    val canManageFeeds: Boolean get() = backend.canManageFeeds

    fun requestSync() {
        scope.launch { sync() }
    }

    /** 正在同步时等它结束再同步一次（导入 OPML 后，新订阅源要被抓到）。 */
    fun requestSyncAfterCurrent() {
        scope.launch {
            mutex.withLock { }
            sync()
        }
    }

    /** 回到前台时调用：距上次同步超过 [maxAgeMs] 才同步。 */
    fun syncIfStale(maxAgeMs: Long = 10 * 60_000L) {
        if (System.currentTimeMillis() - prefs.lastSyncAt > maxAgeMs) requestSync()
    }

    /** 本地标记后延迟几秒批量提交，不等完整同步。 */
    fun scheduleFlush() {
        if (!backend.queuesActions) return
        flushJob?.cancel()
        flushJob = scope.launch {
            delay(3_000)
            runCatching { mutex.withLock { withContext(Dispatchers.IO) { backend.pushPending(null) } } }
                .onFailure { Log.w(TAG, "flush pending failed", it) }
        }
    }

    /** 返回新增文章数；正在同步时直接返回 0。 */
    suspend fun sync(): Result<Int> {
        if (!mutex.tryLock()) return Result.success(0)
        return try {
            _state.value = SyncState.Running("正在同步")
            val added = withContext(Dispatchers.IO) {
                backend.sync { _state.value = SyncState.Running(it) }.also { cleanup() }
            }
            val now = System.currentTimeMillis()
            prefs.lastSyncAt = now
            _lastSyncAt.value = now
            if (added > 0) _dataVersion.value++
            _state.value = SyncState.Idle
            // 图标在后台慢慢找，不拖慢同步
            if (backend.findsIcons) scope.launch(Dispatchers.IO) { runCatching { IconFinder(http).fillMissing(db) } }
            afterSync()
            Result.success(added)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "sync failed", e)
            _state.value = SyncState.Failed(e.message ?: e.javaClass.simpleName)
            Result.failure(e)
        } finally {
            mutex.unlock()
        }
    }

    /** 添加订阅（可指定文件夹）。服务器账号之后同步一次，新源和它的文章才会出现在首页；本机账号添加时已经抓好。 */
    suspend fun addFeed(input: String, folder: FolderRef?) {
        withContext(Dispatchers.IO) { backend.addFeed(input, folder) }
        if (backend.queuesActions) {
            requestSync()
        } else if (backend.findsIcons) {
            scope.launch(Dispatchers.IO) { runCatching { IconFinder(http).fillMissing(db) } }
        }
    }

    suspend fun editFeed(feed: FeedEntity, title: String, folder: FolderRef?) {
        withContext(Dispatchers.IO) { backend.editFeed(feed, title, folder) }
    }

    suspend fun unsubscribe(feed: FeedEntity) {
        withContext(Dispatchers.IO) { backend.unsubscribe(feed) }
    }

    private suspend fun cleanup() {
        val articleDao = db.articleDao()
        val keepDays = prefs.settings.value.keepDays
        // -1 = 一直保留已读文章；0 = 不保留
        if (keepDays >= 0) articleDao.deleteOldRead(System.currentTimeMillis() - keepDays * 24L * 3600_000L)
        articleDao.deleteOrphanContent()
    }

    companion object {
        private const val TAG = "RunnelSync"
        const val SQL_CHUNK = 500
    }
}
