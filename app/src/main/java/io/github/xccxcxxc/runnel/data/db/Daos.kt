package io.github.xccxcxxc.runnel.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface FeedDao {
    @Query("SELECT * FROM folders ORDER BY sortIndex, title COLLATE NOCASE")
    fun observeFolders(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM feeds ORDER BY sortIndex, title COLLATE NOCASE")
    fun observeFeeds(): Flow<List<FeedEntity>>

    @Query("SELECT * FROM feeds")
    suspend fun allFeeds(): List<FeedEntity>

    @Query("SELECT * FROM folders ORDER BY sortIndex, title COLLATE NOCASE")
    suspend fun allFolders(): List<FolderEntity>

    @Upsert
    suspend fun upsertFolders(folders: List<FolderEntity>)

    @Upsert
    suspend fun upsertFeeds(feeds: List<FeedEntity>)

    @Query("DELETE FROM folders WHERE id NOT IN (:keep)")
    suspend fun deleteFoldersExcept(keep: List<String>)

    @Query("DELETE FROM feeds WHERE id NOT IN (:keep)")
    suspend fun deleteFeedsExcept(keep: List<String>)

    @Query("SELECT * FROM feeds WHERE id = :id")
    suspend fun feed(id: String): FeedEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFeeds(feeds: List<FeedEntity>)

    @Query("SELECT COALESCE(MAX(sortIndex) + 1, 0) FROM feeds")
    suspend fun nextFeedIndex(): Int

    @Query("SELECT COALESCE(MAX(sortIndex) + 1, 0) FROM folders")
    suspend fun nextFolderIndex(): Int

    @Query("UPDATE feeds SET title = :title, folderId = :folderId WHERE id = :id")
    suspend fun editFeed(id: String, title: String, folderId: String?)

    @Query("UPDATE feeds SET title = :title, siteUrl = :siteUrl WHERE id = :id")
    suspend fun setFeedInfo(id: String, title: String, siteUrl: String?)

    @Query("DELETE FROM feeds WHERE id = :id")
    suspend fun deleteFeed(id: String)

    /** 里面已经没有订阅源的文件夹（本机账号移走/取消订阅后用；服务器账号的文件夹由同步对齐）。 */
    @Query("DELETE FROM folders WHERE id NOT IN (SELECT folderId FROM feeds WHERE folderId IS NOT NULL)")
    suspend fun deleteEmptyFolders()

    @Query("UPDATE feeds SET localIcon = :icon WHERE id = :id")
    suspend fun setLocalIcon(id: String, icon: String)

    @Query("DELETE FROM folders")
    suspend fun clearFolders()

    @Query("DELETE FROM feeds")
    suspend fun clearFeeds()
}

@Dao
interface ArticleDao {
    @RawQuery
    suspend fun listItems(query: SupportSQLiteQuery): List<ArticleListItem>

    @RawQuery
    suspend fun ids(query: SupportSQLiteQuery): List<Long>

    @Query("SELECT feedId, COUNT(*) AS cnt FROM articles WHERE isRead = 0 GROUP BY feedId")
    fun observeUnreadCounts(): Flow<List<FeedCount>>

    @Query("SELECT feedId, COUNT(*) AS cnt FROM articles WHERE isStarred = 1 GROUP BY feedId")
    fun observeStarredCounts(): Flow<List<FeedCount>>

    @Query("SELECT feedId, COUNT(*) AS cnt FROM articles GROUP BY feedId")
    fun observeAllCounts(): Flow<List<FeedCount>>

    /** 账号列表里每个账号的未读数（只算还在订阅的源）。 */
    @Query("SELECT COUNT(*) FROM articles WHERE isRead = 0 AND feedId IN (SELECT id FROM feeds)")
    fun observeUnreadTotal(): Flow<Int>

    @Query(
        """
        SELECT a.id, a.feedId, COALESCE(f.title, '') AS feedTitle, a.title, a.author, a.url,
               a.publishedAt, a.readingMinutes, a.isRead, a.isStarred, c.html, c.fullHtml
        FROM articles a
        LEFT JOIN feeds f ON f.id = a.feedId
        LEFT JOIN article_content c ON c.articleId = a.id
        WHERE a.id = :id
        """,
    )
    suspend fun readerArticle(id: Long): ReaderArticle?

    /** 离线图片要预下载的文章：这些订阅源里未读或加了星标的，新的在前。 */
    @Query("SELECT id FROM articles WHERE feedId IN (:feedIds) AND (isRead = 0 OR isStarred = 1) ORDER BY publishedAt DESC")
    suspend fun offlineCandidateIds(feedIds: List<String>): List<Long>

    @Query("SELECT id, isRead, isStarred FROM articles WHERE id = :id")
    fun observeFlags(id: Long): Flow<ArticleFlags?>

    @Query("SELECT isRead FROM articles WHERE id = :id")
    suspend fun isRead(id: Long): Boolean?

    @Query("SELECT id, isRead, isStarred FROM articles")
    suspend fun allFlags(): List<ArticleFlags>

    @Query("SELECT id FROM articles")
    suspend fun allIds(): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertArticles(articles: List<ArticleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContents(contents: List<ArticleContentEntity>)

    @Query("UPDATE article_content SET fullHtml = :html WHERE articleId = :id")
    suspend fun setFullHtml(id: Long, html: String)

    @Query("UPDATE articles SET isRead = :read WHERE id IN (:ids)")
    suspend fun setRead(ids: List<Long>, read: Boolean)

    @Query("UPDATE articles SET isStarred = :starred WHERE id IN (:ids)")
    suspend fun setStarred(ids: List<Long>, starred: Boolean)

    @Query("SELECT MAX(fetchedAt) FROM articles")
    suspend fun lastFetchedAt(): Long?

    /** 清理：已读、未加星且早于 [before] 的文章。 */
    @Query("DELETE FROM articles WHERE isRead = 1 AND isStarred = 0 AND publishedAt < :before")
    suspend fun deleteOldRead(before: Long): Int

    @Query("DELETE FROM articles WHERE feedId NOT IN (SELECT id FROM feeds)")
    suspend fun deleteOrphans(): Int

    @Query("DELETE FROM article_content WHERE articleId NOT IN (SELECT id FROM articles)")
    suspend fun deleteOrphanContent(): Int

    @Query("DELETE FROM articles")
    suspend fun clearArticles()

    @Query("DELETE FROM article_content")
    suspend fun clearContent()
}

@Dao
interface PendingDao {
    @Insert
    suspend fun insert(actions: List<PendingActionEntity>)

    @Query("SELECT * FROM pending_actions ORDER BY id")
    suspend fun all(): List<PendingActionEntity>

    @Query("SELECT DISTINCT articleId FROM pending_actions")
    suspend fun pendingArticleIds(): List<Long>

    @Query("DELETE FROM pending_actions WHERE id <= :maxId")
    suspend fun deleteUpTo(maxId: Long)

    @Query("DELETE FROM pending_actions")
    suspend fun clear()
}

/** 本机账号的抓取状态（见 [LocalFeedState]、[LocalSeen]）。 */
@Dao
interface LocalDao {
    @Query("SELECT * FROM local_feed_state")
    suspend fun states(): List<LocalFeedState>

    @Query("SELECT * FROM local_feed_state WHERE feedId = :feedId")
    suspend fun state(feedId: String): LocalFeedState?

    @Upsert
    suspend fun upsertState(state: LocalFeedState)

    @Query("UPDATE local_feed_state SET checkedAt = :now, error = :error WHERE feedId = :feedId")
    suspend fun setError(feedId: String, now: Long, error: String)

    @Query("SELECT articleId FROM local_seen WHERE feedId = :feedId")
    suspend fun seenIds(feedId: String): List<Long>

    @Upsert
    suspend fun upsertSeen(rows: List<LocalSeen>)

    /** 订阅源没变（304）：上次在订阅源里的文章这次也还在。 */
    @Query("UPDATE local_seen SET lastSeenAt = :now WHERE feedId = :feedId AND lastSeenAt = :stamp")
    suspend fun touchSeen(feedId: String, stamp: Long, now: Long)

    /** 已经从订阅源里消失超过 [marginMs]、本地也没有了的文章，不用再记。 */
    @Query(
        """
        DELETE FROM local_seen
        WHERE lastSeenAt < (SELECT s.seenStamp FROM local_feed_state s WHERE s.feedId = local_seen.feedId) - :marginMs
          AND articleId NOT IN (SELECT id FROM articles)
        """,
    )
    suspend fun pruneSeen(marginMs: Long): Int

    @Query("DELETE FROM local_seen WHERE feedId = :feedId")
    suspend fun deleteSeen(feedId: String)

    @Query("DELETE FROM local_feed_state WHERE feedId = :feedId")
    suspend fun deleteState(feedId: String)
}
