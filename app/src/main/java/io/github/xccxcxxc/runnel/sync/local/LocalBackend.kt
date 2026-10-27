package io.github.xccxcxxc.runnel.sync.local

import io.github.xccxcxxc.runnel.content.ArticleProcessor
import io.github.xccxcxxc.runnel.data.db.ArticleContentEntity
import io.github.xccxcxxc.runnel.data.db.ArticleEntity
import io.github.xccxcxxc.runnel.data.db.FeedEntity
import io.github.xccxcxxc.runnel.data.db.LocalFeedState
import io.github.xccxcxxc.runnel.data.db.LocalSeen
import io.github.xccxcxxc.runnel.data.db.RunnelDatabase
import io.github.xccxcxxc.runnel.data.db.inTransaction
import io.github.xccxcxxc.runnel.sync.AccountBackend
import io.github.xccxcxxc.runnel.sync.FeedException
import io.github.xccxcxxc.runnel.sync.FolderRef
import io.github.xccxcxxc.runnel.sync.OpmlEntry
import io.github.xccxcxxc.runnel.sync.ensureFolder
import io.github.xccxcxxc.runnel.sync.normalizeUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 本机账号：订阅源由 App 自己抓（不连服务器），已读/星标只存在本地。
 *
 * 同步 = 并发抓所有订阅源（条件请求，没变的回 304），新文章清洗后写库。
 * 文章 id 由订阅源 id + guid（没有时用链接、标题）算哈希，同一篇反复抓到也是同一个 id；
 * 出现过的 id 记在 local_seen 里，已读文章被清理后不会再当新文章回来。
 */
class LocalBackend(
    private val db: RunnelDatabase,
    private val fetcher: FeedFetcher,
) : AccountBackend {

    override suspend fun sync(progress: (String) -> Unit): Int {
        val feeds = db.feedDao().allFeeds().filter { !it.feedUrl.isNullOrBlank() }
        if (feeds.isEmpty()) return 0
        val states = db.localDao().states().associateBy { it.feedId }
        val done = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val firstError = AtomicReference<String?>(null)
        val gate = Semaphore(PARALLEL)
        progress("更新订阅源 0/${feeds.size}")
        val added = coroutineScope {
            feeds.map { feed ->
                async {
                    gate.withPermit {
                        val n = try {
                            refresh(feed, states[feed.id])
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                            failed.incrementAndGet()
                            val message = e.message ?: e.javaClass.simpleName
                            firstError.compareAndSet(null, message)
                            recordError(feed, states[feed.id], message)
                            0
                        }
                        progress("更新订阅源 ${done.incrementAndGet()}/${feeds.size}")
                        n
                    }
                }
            }.awaitAll().sum()
        }
        db.localDao().pruneSeen(SEEN_MARGIN_MS)
        // 全部失败多半是没网：报同步失败；部分失败记在各订阅源上（编辑订阅源时能看到原因）
        if (failed.get() == feeds.size) throw IOException(firstError.get() ?: "订阅源都没有更新成功")
        return added
    }

    /** 抓一个订阅源，返回新增文章数。 */
    private suspend fun refresh(feed: FeedEntity, state: LocalFeedState?): Int {
        val now = System.currentTimeMillis()
        return when (val r = fetcher.fetch(feed.feedUrl!!, state?.etag, state?.lastModified)) {
            FetchResult.NotModified -> {
                db.inTransaction {
                    if (state != null) db.localDao().touchSeen(feed.id, state.seenStamp, now)
                    db.localDao().upsertState(LocalFeedState(feed.id, state?.etag, state?.lastModified, now, now, null))
                }
                0
            }
            is FetchResult.Ok -> store(feed, r, now)
        }
    }

    private suspend fun store(feed: FeedEntity, r: FetchResult.Ok, now: Long): Int {
        val items = r.feed.items
        val known = db.localDao().seenIds(feed.id).toHashSet()
        val withIds = items.map { articleId(feed.id, it) to it }.distinctBy { it.first }
        val fresh = withIds.filter { it.first !in known }
        val converted = withContext(Dispatchers.Default) {
            fresh.mapIndexed { index, (id, item) -> toEntities(id, feed.id, item, now, index) }
        }
        db.inTransaction {
            val articleDao = db.articleDao()
            converted.chunked(200).forEach { chunk ->
                articleDao.insertArticles(chunk.map { it.first })
                articleDao.insertContents(chunk.map { it.second })
            }
            db.localDao().upsertSeen(withIds.map { LocalSeen(it.first, feed.id, now) })
            db.localDao().upsertState(LocalFeedState(feed.id, r.etag, r.lastModified, now, now, null))
            // OPML 导入的订阅源可能没有网站地址；名字是地址时换成订阅源自己的标题
            val title = ArticleProcessor.cleanTitle(r.feed.title)
            if ((feed.siteUrl == null && r.feed.siteUrl != null) || (title.isNotEmpty() && feed.title == feed.feedUrl)) {
                db.feedDao().setFeedInfo(feed.id, if (feed.title == feed.feedUrl && title.isNotEmpty()) title else feed.title, feed.siteUrl ?: r.feed.siteUrl)
            }
        }
        return converted.size
    }

    private suspend fun recordError(feed: FeedEntity, state: LocalFeedState?, message: String) {
        val now = System.currentTimeMillis()
        if (state == null) db.localDao().upsertState(LocalFeedState(feed.id, null, null, 0, now, message))
        else db.localDao().setError(feed.id, now, message)
    }

    override suspend fun addFeed(input: String, folder: FolderRef?) {
        val (feedUrl, result) = fetcher.discover(normalizeUrl(input))
        val id = feedId(feedUrl)
        if (db.feedDao().feed(id) != null) throw FeedException("已经订阅了这个订阅源")
        val feed = FeedEntity(
            id = id,
            title = ArticleProcessor.cleanTitle(result.feed.title).ifBlank { runCatching { java.net.URI(feedUrl).host }.getOrNull() ?: feedUrl },
            folderId = folder?.id,
            siteUrl = result.feed.siteUrl,
            feedUrl = feedUrl,
            iconUrl = null,
            sortIndex = db.feedDao().nextFeedIndex(),
        )
        db.inTransaction {
            if (folder != null) db.ensureFolder(folder)
            db.feedDao().insertFeeds(listOf(feed))
        }
        store(feed, result, System.currentTimeMillis())
    }

    override suspend fun editFeed(feed: FeedEntity, title: String, folder: FolderRef?) {
        db.inTransaction {
            if (folder != null) db.ensureFolder(folder)
            db.feedDao().editFeed(feed.id, title.ifBlank { feed.title }, folder?.id)
            db.feedDao().deleteEmptyFolders()
        }
    }

    override suspend fun unsubscribe(feed: FeedEntity) {
        db.inTransaction {
            db.feedDao().deleteFeed(feed.id)
            db.articleDao().deleteOrphans()
            db.articleDao().deleteOrphanContent()
            db.localDao().deleteSeen(feed.id)
            db.localDao().deleteState(feed.id)
            db.feedDao().deleteEmptyFolders()
        }
    }

    /** 导入 OPML：只建订阅源和文件夹（已经订阅的跳过），文章由之后的同步去抓。返回新增的订阅源数。 */
    suspend fun import(entries: List<OpmlEntry>): Int = withContext(Dispatchers.IO) {
        db.inTransaction {
            val feedDao = db.feedDao()
            val existing = feedDao.allFeeds().mapTo(HashSet()) { it.id }
            var index = feedDao.nextFeedIndex()
            var added = 0
            for (e in entries) {
                val url = e.xmlUrl.trim()
                val id = feedId(url)
                if (id in existing) continue
                val folder = e.folder?.takeIf(String::isNotBlank)?.let { FolderRef.named(it) }
                if (folder != null) db.ensureFolder(folder)
                feedDao.insertFeeds(listOf(FeedEntity(id, e.title.ifBlank { url }, folder?.id, e.htmlUrl, url, null, index++)))
                existing += id
                added++
            }
            added
        }
    }

    companion object {
        private const val PARALLEL = 6
        /** 从订阅源里消失一周后才忘掉（有的订阅源每次返回的文章不完全一样）。 */
        private const val SEEN_MARGIN_MS = 7L * 24 * 3600_000L

        /** 订阅源 id：和 GReader 的 `feed/...` 同样的形式，导出 OPML、Reader View 勾选都按它。 */
        fun feedId(feedUrl: String) = "feed/" + feedUrl.trim()

        /** 订阅源内稳定的文章 id（正数）。 */
        fun articleId(feedId: String, item: ParsedItem): Long {
            val key = item.guid?.takeIf(String::isNotBlank)
                ?: item.url?.takeIf(String::isNotBlank)
                ?: (item.title + "\u0000" + (item.published ?: 0))
            val digest = MessageDigest.getInstance("SHA-256").digest((feedId + "\n" + key).toByteArray())
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or (digest[i].toLong() and 0xFF)
            return (v and Long.MAX_VALUE).takeIf { it != 0L } ?: 1L
        }

        /**
         * 转成库里的文章。没写发布时间的按第一次抓到的时间，同一批里按订阅源顺序往前错开几毫秒（列表顺序和订阅源一致）；
         * 时间在将来的（时区写错等）按现在算。
         */
        fun toEntities(id: Long, feedId: String, item: ParsedItem, now: Long, index: Int): Pair<ArticleEntity, ArticleContentEntity> {
            val p = ArticleProcessor.process(item.html, item.url, item.imageUrl)
            val published = (item.published ?: (now - index)).coerceAtMost(now)
            val article = ArticleEntity(
                id = id,
                feedId = feedId,
                title = ArticleProcessor.cleanTitle(item.title).ifBlank { p.summary.take(60).ifBlank { "（无标题）" } },
                author = item.author?.takeIf(String::isNotBlank),
                url = item.url,
                publishedAt = published,
                summary = p.summary,
                thumbUrl = p.thumbUrl,
                readingMinutes = p.readingMinutes,
                isRead = false,
                isStarred = false,
                fetchedAt = now,
            )
            return article to ArticleContentEntity(id, p.html, null)
        }
    }
}
