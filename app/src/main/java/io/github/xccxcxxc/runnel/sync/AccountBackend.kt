package io.github.xccxcxxc.runnel.sync

import io.github.xccxcxxc.runnel.data.db.FeedEntity
import io.github.xccxcxxc.runnel.data.db.FolderEntity
import io.github.xccxcxxc.runnel.data.db.RunnelDatabase

/**
 * 一种账号（服务）怎么同步、怎么管理订阅：FreshRSS / Miniflux 等 GReader 服务器、本机抓取、演示数据。
 * 通用的流程（同步状态、互斥、清理旧文章、找图标、离线图片）在 [SyncEngine] 里，这里只放各服务不同的部分。
 * 以后加 Feedly 之类的服务，实现这个接口即可。
 */
interface AccountBackend {
    /** 本地的已读/星标操作要不要排进离线队列、提交给服务器。 */
    val queuesActions: Boolean get() = false

    /** 能不能添加订阅、改名、移动、取消订阅。 */
    val canManageFeeds: Boolean get() = true

    /** 找订阅源自己的网站图标（[IconFinder]）。 */
    val findsIcons: Boolean get() = true

    /** 一次完整同步，返回新增文章数。[progress] 更新同步状态文字。 */
    suspend fun sync(progress: (String) -> Unit): Int

    /** 提交离线队列里的已读/星标操作（[queuesActions] 为 true 时才会调用）。 */
    suspend fun pushPending(progress: ((String) -> Unit)?) {}

    /** 添加订阅（[input] 可以是订阅源地址或网站首页），放进文件夹 [folder]（null = 不放）。 */
    suspend fun addFeed(input: String, folder: FolderRef?)

    /** 改名、换文件夹（[folder] 为 null = 移出文件夹）。 */
    suspend fun editFeed(feed: FeedEntity, title: String, folder: FolderRef?)

    suspend fun unsubscribe(feed: FeedEntity)
}

/** 选中的文件夹：已有的，或新建的（按名字生成 GReader 的 label 流 id，本机账号也用同样的 id）。 */
data class FolderRef(val id: String, val title: String) {
    companion object {
        const val LABEL_PREFIX = "user/-/label/"
        fun named(title: String) = FolderRef(LABEL_PREFIX + title.trim(), title.trim())
    }
}

/** 用户能看懂的失败原因（添加订阅、管理订阅源时显示）。 */
class FeedException(message: String) : Exception(message)

/** 用户填的地址补上协议。 */
fun normalizeUrl(input: String): String =
    input.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }

/** 文件夹在本地还没有就建一个（排在最后）。只调 DAO，可以放在 inTransaction 里。 */
suspend fun RunnelDatabase.ensureFolder(folder: FolderRef) {
    val dao = feedDao()
    if (dao.allFolders().none { it.id == folder.id }) dao.upsertFolders(listOf(FolderEntity(folder.id, folder.title, dao.nextFolderIndex())))
}
