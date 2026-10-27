package io.github.xccxcxxc.runnel

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.xccxcxxc.runnel.data.Account
import io.github.xccxcxxc.runnel.data.AccountType
import io.github.xccxcxxc.runnel.data.ArticleSource
import io.github.xccxcxxc.runnel.data.FilterMode
import io.github.xccxcxxc.runnel.data.SyncMode
import io.github.xccxcxxc.runnel.data.db.ArticleEntity
import io.github.xccxcxxc.runnel.data.db.PendingActionEntity
import io.github.xccxcxxc.runnel.data.db.inTransaction
import io.github.xccxcxxc.runnel.sync.DemoData
import io.github.xccxcxxc.runnel.sync.SyncEngine
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * App 自己的数据库跑在模拟器上（和手机同一种 SQLite）：写事务、离线已读/星标队列、写库时列表照常能读。
 * 走的都是生产代码的入口（ArticleRepository、inTransaction），不直接拼 SQL。
 */
@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    private lateinit var account: AccountSession
    private val db get() = account.db
    private val repo get() = account.repo

    /** 每个测试一个新账号（[type]），填 200 篇演示文章。演示文章要逐篇清洗正文，多了很慢（用户：最多 200 篇）；要跨 SQL 分块的测试自己插轻量文章。 */
    private fun start(type: AccountType = AccountType.DEMO, apiBase: String = "") = runBlocking {
        account = TestAccounts.fresh(type, apiBase, token = if (type.isGReader) "t" else "")
        DemoData.populate(db, count = 200)
    }

    @Before
    fun setUp() = start()

    private fun unread(source: ArticleSource = ArticleSource.All) = runBlocking { repo.loadList(source, FilterMode.UNREAD).size }

    /** 事务中途出错：块里已经写的也要全部撤销。 */
    @Test
    fun transactionRollsBackOnError() = runBlocking {
        val before = unread()
        val ids = db.articleDao().allIds()
        val result = runCatching {
            db.inTransaction {
                db.articleDao().setRead(ids.take(SyncEngine.SQL_CHUNK), true)
                error("中途出错")
            }
        }
        assertTrue(result.isFailure)
        assertEquals(before, unread())
    }

    /** “全部标为已读”超过一个 SQL 分块（500）时也要全部标上。 */
    @Test
    fun markAllReadAcrossChunks() = runBlocking {
        // 不带正文的轻量文章，凑够一个分块以上
        val feedId = db.feedDao().allFeeds().first().id
        val now = System.currentTimeMillis()
        db.articleDao().insertArticles(
            (1..SyncEngine.SQL_CHUNK + 100).map {
                ArticleEntity(1_000_000L + it, feedId, "分块 $it", null, null, now - it, "", null, 1, false, false, now)
            },
        )
        assertTrue(unread() > SyncEngine.SQL_CHUNK)
        repo.markAllRead(ArticleSource.All, Long.MAX_VALUE)
        assertEquals(0, unread())
    }

    /**
     * 远程账号离线时标已读、加星标：文章立刻变，同时在同一个事务里排进离线队列，下次同步再提交。
     * 服务器地址故意不通，后台提交失败，队列要原样留着。
     */
    @Test
    fun remoteActionsAreQueuedForSync() = runBlocking {
        start(AccountType.FRESHRSS, "http://127.0.0.1:9")
        val feed = db.feedDao().allFeeds().first().let { ArticleSource.Feed(it.id, it.title, null) }
        val ids = repo.loadList(feed, FilterMode.UNREAD).map { it.id }
        assertTrue(ids.size > 3)

        repo.setRead(ids, true)
        repo.setStarred(ids.first(), true)

        assertEquals(0, unread(feed))
        assertEquals(true, db.articleDao().allFlags().first { it.id == ids.first() }.isStarred)
        val pending = db.pendingDao().all()
        assertEquals(ids.toSet(), pending.filter { it.action == PendingActionEntity.READ }.map { it.articleId }.toSet())
        assertEquals(listOf(ids.first()), pending.filter { it.action == PendingActionEntity.STAR }.map { it.articleId })

        Thread.sleep(4_000) // 后台提交在 3 秒后尝试，连不上
        assertEquals(pending.size, db.pendingDao().all().size)
    }

    /** 演示账号、本机账号不排离线队列（没有服务器可提交）。 */
    @Test
    fun localAccountDoesNotQueue() = runBlocking {
        repo.setRead(db.articleDao().allIds().take(10), true)
        assertEquals(0, db.pendingDao().all().size)
        start(AccountType.LOCAL)
        repo.setRead(db.articleDao().allIds().take(10), true)
        assertEquals(0, db.pendingDao().all().size)
    }

    /** 写事务进行中（同步写库）时，列表读库不被挡住（WAL）。 */
    @Test
    fun readsDoNotWaitForWriteTransaction() = runBlocking {
        val inside = CompletableDeferred<Unit>()
        // 事务绑定线程：块里不能挂起（挂起后可能换线程，结束事务时报“没有事务”，写锁永远不放，后面的测试全卡死）。
        // 所以用阻塞等待，最多 5 秒，测试失败也会放行。
        val release = CountDownLatch(1)
        val writer = async(Dispatchers.IO) {
            db.inTransaction {
                db.articleDao().setRead(db.articleDao().allIds().take(5), true)
                inside.complete(Unit)
                release.await(5, TimeUnit.SECONDS)
            }
        }
        try {
            inside.await()
            val list = withTimeout(2_000) { withContext(Dispatchers.IO) { repo.loadList(ArticleSource.All, FilterMode.ALL) } }
            assertTrue(list.isNotEmpty())
        } finally {
            release.countDown()
            writer.await()
        }
    }
}
