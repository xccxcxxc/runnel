package io.github.xccxcxxc.runnel.content

import android.util.Log
import io.github.xccxcxxc.runnel.data.AccountPrefs
import io.github.xccxcxxc.runnel.data.db.RunnelDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.Jsoup
import java.util.concurrent.atomic.AtomicInteger

/**
 * 离线图片（设置 → 账号 → 离线图片，按订阅源勾选）：同步后把勾选订阅源里未读和星标文章的正文图片
 * 预先下载进 [ReaderImageCache]，没网时打开这些文章图片也在。不限 Wi-Fi（用户决定，2026-10-20）。
 */
class OfflineImages(
    private val db: RunnelDatabase,
    private val prefs: AccountPrefs,
    private val cache: () -> ReaderImageCache,
    private val scope: CoroutineScope,
) {
    private var job: Job? = null
    private var again = false

    /** 开始下载；正在下载时记下来，这一轮完了按最新的勾选再跑一轮。返回的 Job 在全部跑完后结束（后台同步要等它）。 */
    fun requestPrefetch(): Job = synchronized(this) {
        job?.takeIf { it.isActive }?.let {
            again = true
            return it
        }
        scope.launch(Dispatchers.IO) {
            do {
                synchronized(this@OfflineImages) { again = false }
                runCatching { runOnce() }.onFailure { Log.w(TAG, "prefetch failed", it) }
            } while (synchronized(this@OfflineImages) { again })
        }.also { job = it }
    }

    private suspend fun runOnce() {
        val feeds = prefs.settings.value.offlineImageFeeds
        if (feeds.isEmpty()) return
        val dao = db.articleDao()
        val ids = feeds.toList().chunked(500).flatMap { dao.offlineCandidateIds(it) }
        val cache = cache()
        val gate = Semaphore(PARALLEL)
        val failures = AtomicInteger(0)
        for (id in ids) {
            // 连续失败多次多半是没网了，这一轮先停
            if (failures.get() >= MAX_CONSECUTIVE_FAILURES) return
            val article = dao.readerArticle(id) ?: continue
            val urls = (imageUrls(article.html, article.url) + imageUrls(article.fullHtml, article.url)).distinct()
            coroutineScope {
                urls.map { url ->
                    async {
                        gate.withPermit {
                            if (failures.get() >= MAX_CONSECUTIVE_FAILURES) return@withPermit
                            if (cache.prefetch(url, article.url)) failures.set(0) else failures.incrementAndGet()
                        }
                    }
                }.awaitAll()
            }
        }
    }

    companion object {
        private const val TAG = "OfflineImages"
        private const val PARALLEL = 4
        private const val MAX_CONSECUTIVE_FAILURES = 8

        /**
         * 正文里 WebView 会请求的图片地址：`<img src>`，按文章地址补全相对路径（和阅读页 loadDataWithBaseURL 一样）。
         * 阅读页显示时去掉了 srcset（[io.github.xccxcxxc.runnel.ui.reader.ReaderHtml]），所以只看 src。
         */
        fun imageUrls(html: String?, baseUrl: String?): List<String> {
            if (html.isNullOrBlank()) return emptyList()
            return Jsoup.parseBodyFragment(html, baseUrl.orEmpty()).select("img[src]")
                .map { it.absUrl("src").ifBlank { it.attr("src") } }
                .filter { it.startsWith("http://") || it.startsWith("https://") }
                .distinct()
        }
    }
}
