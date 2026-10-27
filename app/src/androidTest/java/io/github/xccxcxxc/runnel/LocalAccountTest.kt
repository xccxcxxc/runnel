package io.github.xccxcxxc.runnel

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.xccxcxxc.runnel.data.AccountType
import io.github.xccxcxxc.runnel.data.ArticleSource
import io.github.xccxcxxc.runnel.data.FilterMode
import io.github.xccxcxxc.runnel.sync.FolderRef
import io.github.xccxcxxc.runnel.sync.OpmlEntry
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/**
 * 本机账号：添加订阅（从网页找订阅地址）、同步（304、新文章）、清理后的已读文章不再回来、
 * 编辑/取消订阅、导入 OPML、两个账号互不影响。网站是本机的 MockWebServer，走的都是生产代码（AccountSession、SyncEngine、Room）。
 */
@RunWith(AndroidJUnit4::class)
class LocalAccountTest {
    private val server = MockWebServer()
    /** /feed.xml 当前返回的文章（guid 列表）；ETag 跟着内容变。 */
    private val items = AtomicReference(listOf("a", "b"))
    private lateinit var account: AccountSession

    private fun rss(guids: List<String>) = buildString {
        append("<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>测试站</title><link>https://ex.com/</link>")
        guids.forEachIndexed { i, g ->
            append("<item><guid>$g</guid><title>文章 $g</title><link>https://ex.com/$g</link>")
            append("<pubDate>Mon, 0${i + 1} Jan 2024 00:00:00 GMT</pubDate><description>&lt;p&gt;正文 $g&lt;/p&gt;</description></item>")
        }
        append("</channel></rss>")
    }

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/" -> MockResponse.Builder().addHeader("Content-Type", "text/html")
                    .body("<html><head><link rel=\"alternate\" type=\"application/rss+xml\" href=\"/feed.xml\"></head></html>").build()
                "/feed.xml" -> {
                    val now = items.get()
                    val etag = "\"" + now.joinToString("-") + "\""
                    if (request.headers["If-None-Match"] == etag) MockResponse(code = 304)
                    else MockResponse.Builder().addHeader("ETag", etag).body(rss(now)).build()
                }
                "/other.xml" -> MockResponse.Builder().body(rss(listOf("x"))).build()
                else -> MockResponse(code = 404)
            }
        }
        server.start()
        account = TestAccounts.fresh(AccountType.LOCAL)
    }

    @After
    fun tearDown() {
        server.close()
        TestAccounts.removeAll()
    }

    private fun unread(source: ArticleSource = ArticleSource.All) = runBlocking { account.repo.loadList(source, FilterMode.UNREAD) }
    private fun all() = runBlocking { account.repo.loadList(ArticleSource.All, FilterMode.ALL) }

    @Test
    fun addFromHomepageThenSync() = runBlocking {
        account.sync.addFeed(server.url("/").toString(), FolderRef.named("测试"))
        val feed = account.db.feedDao().allFeeds().single()
        assertEquals("测试站", feed.title)
        assertEquals(server.url("/feed.xml").toString(), feed.feedUrl)
        assertEquals("user/-/label/测试", feed.folderId)
        assertEquals("测试", account.db.feedDao().allFolders().single().title)
        assertEquals(listOf("文章 a", "文章 b"), unread().map { it.title }.sorted())

        // 再加一次同一个源
        val again = runCatching { account.sync.addFeed(server.url("/feed.xml").toString(), null) }.exceptionOrNull()
        assertEquals("已经订阅了这个订阅源", again?.message)

        // 没变（304）：不新增；订阅源多了一篇：只加那一篇
        assertEquals(0, account.sync.sync().getOrThrow())
        items.set(listOf("a", "b", "c"))
        assertEquals(1, account.sync.sync().getOrThrow())
        assertEquals(3, unread().size)
        assertNull(account.db.localDao().state(feed.id)!!.error)
    }

    /** 已读文章按“保留已读文章 = 不保留”清掉后，订阅源里还挂着它，下次同步也不能当新文章回来。 */
    @Test
    fun cleanedUpReadArticlesDoNotComeBack() = runBlocking {
        account.prefs.update { it.copy(keepDays = 0) }
        account.sync.addFeed(server.url("/feed.xml").toString(), null)
        val a = all().first { it.title == "文章 a" }
        account.repo.setRead(listOf(a.id), true)

        account.sync.sync().getOrThrow() // 304，清理掉已读的 a
        assertEquals(listOf("文章 b"), all().map { it.title })

        items.set(listOf("a", "b", "c")) // 订阅源内容变了（200），a 还在里面
        account.sync.sync().getOrThrow()
        assertEquals(listOf("文章 b", "文章 c"), all().map { it.title }.sorted())
    }

    @Test
    fun editAndUnsubscribe() = runBlocking {
        account.sync.addFeed(server.url("/feed.xml").toString(), FolderRef.named("旧"))
        var feed = account.db.feedDao().allFeeds().single()
        account.sync.editFeed(feed, "新名字", FolderRef.named("新"))
        feed = account.db.feedDao().allFeeds().single()
        assertEquals("新名字", feed.title)
        assertEquals("user/-/label/新", feed.folderId)
        assertEquals("空了的文件夹删掉", listOf("新"), account.db.feedDao().allFolders().map { it.title })

        account.sync.unsubscribe(feed)
        assertTrue(account.db.feedDao().allFeeds().isEmpty())
        assertTrue(all().isEmpty())
        assertTrue(account.db.feedDao().allFolders().isEmpty())
        assertNull(account.db.localDao().state(feed.id))
    }

    @Test
    fun importOpmlThenSync() = runBlocking {
        val entries = listOf(
            OpmlEntry("甲", server.url("/feed.xml").toString(), null, "科技"),
            OpmlEntry("乙", server.url("/other.xml").toString(), null, null),
            OpmlEntry("坏", server.url("/missing.xml").toString(), null, "科技"),
        )
        assertEquals(3, account.importOpml(entries))
        assertEquals("已经有的跳过", 0, account.importOpml(entries.take(1)))
        assertEquals(setOf("甲", "乙", "坏"), account.db.feedDao().allFeeds().map { it.title }.toSet())

        // 有一个源失败不影响别的源，失败原因记在那个源上
        assertTrue(account.sync.sync().isSuccess)
        assertEquals(3, all().size)
        val bad = account.db.feedDao().allFeeds().first { it.title == "坏" }
        assertEquals("HTTP 404", account.db.localDao().state(bad.id)!!.error)
        val good = account.db.feedDao().allFeeds().first { it.title == "甲" }
        assertEquals("导入时的名字不被订阅源标题覆盖", "甲", good.title)
        assertEquals("https://ex.com/", good.siteUrl)
    }

    @Test
    fun allFeedsFailingIsASyncFailure() = runBlocking {
        account.importOpml(listOf(OpmlEntry("坏", server.url("/missing.xml").toString(), null, null)))
        val result = account.sync.sync()
        assertTrue(result.isFailure)
        assertEquals("HTTP 404", result.exceptionOrNull()?.message)
    }

    /** 两个账号订阅同一个源：订阅源 id、文章 id 一样，但各在各的数据库里，已读互不影响。 */
    @Test
    fun accountsAreIsolated() = runBlocking {
        account.sync.addFeed(server.url("/feed.xml").toString(), null)
        val other = Graph.get().let { g -> g.session(g.addLocalAccount().id)!! }
        other.sync.addFeed(server.url("/feed.xml").toString(), null)
        val ids = all().map { it.id }
        assertEquals(ids.sorted(), other.repo.loadList(ArticleSource.All, FilterMode.ALL).map { it.id }.sorted())

        account.repo.setRead(ids, true)
        assertEquals(0, unread().size)
        assertEquals(2, other.repo.loadList(ArticleSource.All, FilterMode.UNREAD).size)
        assertTrue("已读覆盖层也是各账号自己的", other.flags.read.isEmpty())
    }
}
