package io.github.xccxcxxc.runnel.data

import androidx.compose.runtime.Immutable
import io.github.xccxcxxc.runnel.data.db.inTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import io.github.xccxcxxc.runnel.content.FullTextFetcher
import io.github.xccxcxxc.runnel.data.db.ArticleListItem
import io.github.xccxcxxc.runnel.data.db.RunnelDatabase
import io.github.xccxcxxc.runnel.data.db.PendingActionEntity
import io.github.xccxcxxc.runnel.data.db.ReaderArticle
import io.github.xccxcxxc.runnel.sync.SyncEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

enum class FilterMode { STARRED, UNREAD, ALL }

/** 文章来源：全部 / 某个文件夹 / 某个订阅源。 */
@Immutable
sealed interface ArticleSource {
    val title: String

    data object All : ArticleSource {
        override val title = "全部"
    }

    data class Folder(val id: String, override val title: String) : ArticleSource
    data class Feed(val id: String, override val title: String, val iconUrl: String?) : ArticleSource
}

/** 一个账号的文章读写（列表快照、已读/星标、全文）。 */
class ArticleRepository(
    private val db: RunnelDatabase,
    /** 全 App 的设置（未读排序）。 */
    private val prefs: Prefs,
    val flags: FlagStore,
    private val sync: SyncEngine,
    private val fullText: FullTextFetcher,
) {
    private val feedDao = db.feedDao()
    private val articleDao = db.articleDao()

    val folders = feedDao.observeFolders()
    val feeds = feedDao.observeFeeds()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun counts(filter: Flow<FilterMode>): Flow<Map<String, Int>> = filter.flatMapLatest { mode ->
        when (mode) {
            FilterMode.UNREAD -> articleDao.observeUnreadCounts()
            FilterMode.STARRED -> articleDao.observeStarredCounts()
            FilterMode.ALL -> articleDao.observeUnreadCounts()
        }
    }.map { list -> list.associate { it.feedId to it.cnt } }

    /** 列表快照：一次查齐轻量字段，最多 [limit] 篇。 */
    suspend fun loadList(source: ArticleSource, filter: FilterMode, limit: Int = 5_000): List<ArticleListItem> =
        withContext(Dispatchers.IO) {
            val (where, args) = where(source, filter)
            articleDao.listItems(
                SimpleSQLiteQuery(
                    """
                    SELECT a.id, a.feedId, COALESCE(f.title, '') AS feedTitle, COALESCE(NULLIF(f.iconUrl, ''), NULLIF(f.localIcon, '')) AS feedIcon, f.siteUrl AS feedSite, a.title,
                           a.summary, a.thumbUrl, a.publishedAt, a.readingMinutes, a.isRead, a.isStarred
                    FROM articles a LEFT JOIN feeds f ON f.id = a.feedId
                    WHERE $where
                    ORDER BY a.publishedAt DESC, a.id DESC
                    LIMIT $limit
                    """.trimIndent(),
                    args.toTypedArray(),
                ),
            )
        }

    /** 与列表页相同的排序：数据库是新→旧，“未读”默认旧→新。 */
    suspend fun loadOrdered(source: ArticleSource, filter: FilterMode): List<ArticleListItem> {
        val list = loadList(source, filter)
        return if (filter == FilterMode.UNREAD && prefs.settings.value.oldestFirstUnread) list.asReversed() else list
    }

    /**
     * 下一个“有内容”的来源，顺序和首页一致（文件夹按顺序，文件夹内订阅源按顺序，最后是未归类订阅源）。
     * 当前是订阅源 → 下一个订阅源（可以跨文件夹）；当前是文件夹 → 下一个文件夹；“全部”没有下一个。
     */
    suspend fun nextSource(current: ArticleSource, filter: FilterMode): ArticleSource? {
        if (current is ArticleSource.All) return null
        val counts = when (filter) {
            FilterMode.UNREAD -> articleDao.observeUnreadCounts()
            FilterMode.STARRED -> articleDao.observeStarredCounts()
            FilterMode.ALL -> articleDao.observeAllCounts()
        }.first().associate { it.feedId to it.cnt }
        val folders = feedDao.observeFolders().first()
        val feeds = feedDao.allFeeds().sortedWith(compareBy({ it.sortIndex }, { it.title.lowercase() }))
        val byFolder = feeds.groupBy { it.folderId }
        val folderIds = folders.mapTo(HashSet()) { it.id }
        fun has(f: io.github.xccxcxxc.runnel.data.db.FeedEntity) = (counts[f.id] ?: 0) > 0
        return when (current) {
            is ArticleSource.Feed -> {
                val ordered = folders.flatMap { byFolder[it.id].orEmpty() } +
                    feeds.filter { it.folderId == null || it.folderId !in folderIds }
                val i = ordered.indexOfFirst { it.id == current.id }
                ordered.drop(i + 1).firstOrNull(::has)?.let { ArticleSource.Feed(it.id, it.title, it.iconUrl) }
            }
            is ArticleSource.Folder -> {
                val i = folders.indexOfFirst { it.id == current.id }
                folders.drop(i + 1).firstOrNull { f -> byFolder[f.id].orEmpty().any(::has) }
                    ?.let { ArticleSource.Folder(it.id, it.title) }
            }
            ArticleSource.All -> null
        }
    }

    /** 标题/摘要子串搜索（中文没有分词，FTS 匹配不到，用 LIKE；几千篇的量足够快）。 */
    suspend fun search(query: String, limit: Int = 300): List<ArticleListItem> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        val pattern = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        articleDao.listItems(
            SimpleSQLiteQuery(
                """
                SELECT a.id, a.feedId, COALESCE(f.title, '') AS feedTitle, COALESCE(NULLIF(f.iconUrl, ''), NULLIF(f.localIcon, '')) AS feedIcon, f.siteUrl AS feedSite, a.title,
                       a.summary, a.thumbUrl, a.publishedAt, a.readingMinutes, a.isRead, a.isStarred
                FROM articles a LEFT JOIN feeds f ON f.id = a.feedId
                WHERE a.title LIKE ? ESCAPE '\' OR a.summary LIKE ? ESCAPE '\'
                ORDER BY a.publishedAt DESC
                LIMIT $limit
                """.trimIndent(),
                arrayOf(pattern, pattern),
            ),
        )
    }

    suspend fun reader(id: Long): ReaderArticle? = withContext(Dispatchers.IO) { articleDao.readerArticle(id) }

    fun observeFlags(id: Long) = articleDao.observeFlags(id)

    /** 先改内存（界面立即响应），再写库和离线队列，几秒后批量提交到服务器。 */
    suspend fun setRead(ids: List<Long>, read: Boolean) {
        if (ids.isEmpty()) return
        flags.setRead(ids, read)
        persist(ids, if (read) PendingActionEntity.READ else PendingActionEntity.UNREAD) { chunk ->
            articleDao.setRead(chunk, read)
        }
    }

    /** 打开文章时调用：已经是已读就什么都不做（不产生重复的离线操作）。 */
    suspend fun markReadIfUnread(id: Long) {
        flags.read[id]?.let { if (it) return }
        val read = withContext(Dispatchers.IO) { articleDao.isRead(id) } ?: return
        if (read && flags.read[id] == null) return
        setRead(listOf(id), true)
    }

    suspend fun setStarred(id: Long, starred: Boolean) {
        flags.setStarred(listOf(id), starred)
        persist(listOf(id), if (starred) PendingActionEntity.STAR else PendingActionEntity.UNSTAR) { chunk ->
            articleDao.setStarred(chunk, starred)
        }
    }

    /** 把来源下、[before] 之前发布的未读文章全部标为已读，返回篇数。 */
    suspend fun markAllRead(source: ArticleSource, before: Long): Int {
        val ids = withContext(Dispatchers.IO) {
            val (where, args) = where(source, FilterMode.UNREAD)
            articleDao.ids(
                SimpleSQLiteQuery(
                    "SELECT a.id FROM articles a LEFT JOIN feeds f ON f.id = a.feedId WHERE $where AND a.publishedAt <= ?",
                    (args + before).toTypedArray(),
                ),
            )
        }
        setRead(ids, true)
        return ids.size
    }

    suspend fun fetchFullText(article: ReaderArticle): String {
        val url = article.url ?: error("文章没有原文链接")
        val html = fullText.fetch(url)
        withContext(Dispatchers.IO) { articleDao.setFullHtml(article.id, html) }
        return html
    }

    private suspend fun persist(ids: List<Long>, action: Int, update: suspend (List<Long>) -> Unit) {
        val remote = sync.queuesActions
        withContext(Dispatchers.IO) {
            db.inTransaction {
                ids.chunked(SyncEngine.SQL_CHUNK).forEach { update(it) }
                if (remote) {
                    val now = System.currentTimeMillis()
                    db.pendingDao().insert(ids.map { PendingActionEntity(articleId = it, action = action, createdAt = now) })
                }
            }
        }
        if (remote) sync.scheduleFlush()
    }

    private fun where(source: ArticleSource, filter: FilterMode): Pair<String, List<Any>> {
        val clauses = ArrayList<String>()
        val args = ArrayList<Any>()
        when (source) {
            ArticleSource.All -> Unit
            is ArticleSource.Folder -> { clauses += "f.folderId = ?"; args += source.id }
            is ArticleSource.Feed -> { clauses += "a.feedId = ?"; args += source.id }
        }
        when (filter) {
            FilterMode.UNREAD -> clauses += "a.isRead = 0"
            FilterMode.STARRED -> clauses += "a.isStarred = 1"
            FilterMode.ALL -> Unit
        }
        return (clauses.joinToString(" AND ").ifEmpty { "1" }) to args
    }
}
