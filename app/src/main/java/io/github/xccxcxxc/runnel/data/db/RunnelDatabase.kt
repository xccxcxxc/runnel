package io.github.xccxcxxc.runnel.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import kotlinx.coroutines.Dispatchers

@Database(
    entities = [
        FolderEntity::class,
        FeedEntity::class,
        ArticleEntity::class,
        ArticleContentEntity::class,
        PendingActionEntity::class,
        ArticleFts::class,
        LocalFeedState::class,
        LocalSeen::class,
    ],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class RunnelDatabase : RoomDatabase() {
    abstract fun feedDao(): FeedDao
    abstract fun articleDao(): ArticleDao
    abstract fun pendingDao(): PendingDao
    abstract fun localDao(): LocalDao

    companion object {
        /**
         * 用系统自带的 SQLite（不打包自己的，省 1.9 MB）。数据库相关的测试在模拟器上跑（app/src/androidTest），
         * 和手机是同一种引擎。数据库文件格式通用，#29–#31 用自带 SQLite 建的 runnel.db 直接打开。
         * 每个账号一个数据库文件（[name] 见 [io.github.xccxcxxc.runnel.data.Account.dbName]）。
         */
        fun create(context: Context, name: String): RunnelDatabase =
            Room.databaseBuilder(context, RunnelDatabase::class.java, name)
                .setQueryCoroutineContext(Dispatchers.IO)
                // WAL：同步写库时，列表读库不被阻塞
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}

/**
 * 写事务（BEGIN IMMEDIATE）。块里的 DAO 调用复用同一个写连接（Room 用协程上下文里的 ConnectionElement 识别），
 * 所以块里只能调 suspend 的 DAO 方法，不要真正挂起（await、delay、换 dispatcher）：事务绑定线程，挂起后在别的线程恢复，
 * 结束事务时会报“没有事务”，写锁再也不放，之后所有写库都卡住（2026-10-20 仪器测试里出过）。
 */
suspend fun <R> RunnelDatabase.inTransaction(block: suspend () -> R): R =
    useWriterConnection { transactor -> transactor.immediateTransaction { block() } }
