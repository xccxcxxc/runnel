package io.github.xccxcxxc.runnel.data.db

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

/** 文件夹。id 是 GReader 的 label 流 id，如 `user/-/label/科技`。 */
@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val id: String,
    val title: String,
    val sortIndex: Int,
)

/** 订阅源。id 是 GReader 的 feed 流 id，如 `feed/12`。 */
@Entity(tableName = "feeds", indices = [Index("folderId")])
data class FeedEntity(
    @PrimaryKey val id: String,
    val title: String,
    val folderId: String?,
    val siteUrl: String?,
    val feedUrl: String?,
    val iconUrl: String?,
    val sortIndex: Int,
    /** App 自己从网站首页找到的图标（服务端没给图标时用），空串表示找过但没找到。 */
    val localIcon: String? = null,
)

/**
 * 文章的轻量字段。列表只查这张表（加 feeds 联表），正文放在 [ArticleContentEntity]，
 * 滚动列表时不会把正文读进内存。摘要、缩略图、阅读时长都在同步时算好。
 */
@Entity(
    tableName = "articles",
    indices = [
        Index(value = ["feedId", "publishedAt"]),
        Index(value = ["isRead", "publishedAt"]),
        Index(value = ["isStarred", "publishedAt"]),
        Index(value = ["publishedAt"]),
    ],
)
data class ArticleEntity(
    @PrimaryKey val id: Long,
    val feedId: String,
    val title: String,
    val author: String?,
    val url: String?,
    val publishedAt: Long,
    val summary: String,
    val thumbUrl: String?,
    val readingMinutes: Int,
    val isRead: Boolean,
    val isStarred: Boolean,
    val fetchedAt: Long,
)

/** 正文：同步时清洗好的 HTML；[fullHtml] 是手动抓取的全文。 */
@Entity(tableName = "article_content")
data class ArticleContentEntity(
    @PrimaryKey val articleId: Long,
    val html: String,
    val fullHtml: String?,
)

/** 离线时的已读/星标操作，下次同步先提交再拉取。 */
@Entity(tableName = "pending_actions", indices = [Index("articleId")])
data class PendingActionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val articleId: Long,
    val action: Int,
    val createdAt: Long,
) {
    companion object {
        const val READ = 0
        const val UNREAD = 1
        const val STAR = 2
        const val UNSTAR = 3
    }
}

/**
 * 本机账号：每个订阅源的抓取状态。[etag] / [lastModified] 用于条件请求（没变时服务器回 304，不用重新下载解析）。
 * [seenStamp] 是上次成功拿到订阅源（200 或 304）的时间，那次订阅源里的文章 [LocalSeen.lastSeenAt] 都等于它。
 */
@Entity(tableName = "local_feed_state")
data class LocalFeedState(
    @PrimaryKey val feedId: String,
    val etag: String?,
    val lastModified: String?,
    val seenStamp: Long,
    val checkedAt: Long,
    /** 上次抓取失败的原因；成功后清空。 */
    val error: String?,
)

/**
 * 本机账号：订阅源里出现过的文章 id。已读文章按“保留已读文章”清理掉以后，订阅源里还挂着它，
 * 下次抓取也不会当成新文章再加回来。
 */
@Entity(tableName = "local_seen", indices = [Index("feedId")])
data class LocalSeen(
    @PrimaryKey val articleId: Long,
    val feedId: String,
    val lastSeenAt: Long,
)

@Fts4(contentEntity = ArticleEntity::class)
@Entity(tableName = "articles_fts")
data class ArticleFts(
    val title: String,
    val summary: String,
)

/** 文章列表行需要的全部字段（一次查询拿齐，行内不再查库）。 */
@Immutable
data class ArticleListItem(
    val id: Long,
    val feedId: String,
    val feedTitle: String,
    val feedIcon: String?,
    val feedSite: String?,
    val title: String,
    val summary: String,
    val thumbUrl: String?,
    val publishedAt: Long,
    val readingMinutes: Int,
    val isRead: Boolean,
    val isStarred: Boolean,
)

/** 阅读页需要的字段。 */
data class ReaderArticle(
    val id: Long,
    val feedId: String,
    val feedTitle: String,
    val title: String,
    val author: String?,
    val url: String?,
    val publishedAt: Long,
    val readingMinutes: Int,
    val isRead: Boolean,
    val isStarred: Boolean,
    val html: String?,
    val fullHtml: String?,
)

data class FeedCount(val feedId: String, val cnt: Int)

data class ArticleFlags(val id: Long, val isRead: Boolean, val isStarred: Boolean)
