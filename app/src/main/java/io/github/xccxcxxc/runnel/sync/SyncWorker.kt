package io.github.xccxcxxc.runnel.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.xccxcxxc.runnel.Graph
import java.util.concurrent.TimeUnit

/**
 * 后台定时同步（尽力而为：部分国产系统会限制后台），每个账号一个任务，间隔按各自的设置。
 * 服务器账号只是把服务端抓好的结果拉下来；本机账号在这里抓订阅源。回到前台时还会再同步一次。
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ACCOUNT) ?: return Result.success()
        // 账号已经移除：任务也一并取消
        val session = Graph.get().session(id) ?: return Result.success().also { cancel(applicationContext, id) }
        if (session.sync.sync().isFailure) return Result.retry()
        // 等离线图片下完：worker 一结束进程就可能被系统回收
        session.offlineImages.requestPrefetch().join()
        return Result.success()
    }

    companion object {
        private const val KEY_ACCOUNT = "account"
        /** 单账号版本的任务名（升级后取消，换成按账号的）。 */
        private const val LEGACY_NAME = "runnel-sync"

        private fun name(accountId: String) = "runnel-sync-$accountId"

        fun schedule(context: Context, accountId: String, intervalMinutes: Int) {
            val wm = WorkManager.getInstance(context)
            if (intervalMinutes <= 0) {
                wm.cancelUniqueWork(name(accountId))
                return
            }
            val request = PeriodicWorkRequestBuilder<SyncWorker>(intervalMinutes.toLong().coerceAtLeast(15), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(KEY_ACCOUNT to accountId))
                .build()
            wm.enqueueUniquePeriodicWork(name(accountId), ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancel(context: Context, accountId: String) = WorkManager.getInstance(context).cancelUniqueWork(name(accountId))

        fun cancelLegacy(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(LEGACY_NAME)
    }
}
