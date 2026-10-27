package io.github.xccxcxxc.runnel

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import io.github.xccxcxxc.runnel.sync.SyncWorker
import okio.Path.Companion.toOkioPath

class RunnelApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        val graph = Graph.get()
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .components { add(OkHttpNetworkFetcherFactory(callFactory = { graph.http })) }
                .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
                .diskCache {
                    DiskCache.Builder()
                        .directory(context.cacheDir.resolve("images").toOkioPath())
                        .maxSizeBytes(256L * 1024 * 1024)
                        .build()
                }
                .build()
        }
        // 每次启动按各账号的设置排一遍（UPDATE 策略，已经排好的不会重来）；单账号版本留下的任务换成按账号的
        if (graph.prefs.upgradedToMultiAccount) SyncWorker.cancelLegacy(this)
        graph.prefs.accounts.value.forEach { graph.scheduleBackgroundSync(it.id) }
    }
}
