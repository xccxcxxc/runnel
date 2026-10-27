package io.github.xccxcxxc.runnel.sync

import io.github.xccxcxxc.runnel.data.db.inTransaction
import io.github.xccxcxxc.runnel.data.db.ArticleContentEntity
import io.github.xccxcxxc.runnel.data.db.ArticleEntity
import io.github.xccxcxxc.runnel.data.db.RunnelDatabase
import io.github.xccxcxxc.runnel.data.db.FeedEntity
import io.github.xccxcxxc.runnel.data.db.FolderEntity
import kotlin.random.Random

/**
 * 演示数据：不连服务器就能测列表滚动和阅读页性能（macrobenchmark 也用它）。
 * 内容是随机拼的，图片来自 picsum.photos。
 */
object DemoData {
    private val folders = listOf("科技" to 0, "设计" to 1, "博客" to 2)
    private val feeds = listOf(
        Triple("极客周刊", "科技", "https://geekweekly.example.com"),
        Triple("Circuit Daily", "科技", "https://circuitdaily.example.com"),
        Triple("Silicon Notes", "科技", "https://siliconnotes.example.com"),
        Triple("Byte Digest", "科技", "https://bytedigest.example.com"),
        Triple("Quiet Mornings", "博客", "https://quietmornings.example.com"),
        Triple("码农手记", "博客", "https://codernotes.example.com"),
        Triple("Margin Notes", "博客", "https://marginnotes.example.com"),
        Triple("Pixel Press", "设计", "https://pixelpress.example.com"),
        Triple("Design Digest", "设计", "https://designdigest.example.com"),
        Triple("Layout Lab", "设计", "https://layoutlab.example.com"),
        Triple("Pixel Garden", null, "https://pixelgarden.example.com"),
        Triple("Maple Notes", null, "https://maplenotes.example.com"),
    )
    private val zhWords = listOf(
        "星辰", "发布", "新版", "系统", "体验", "设计", "细节", "动画", "流畅", "阅读", "订阅", "效率", "工具",
        "开源", "隐私", "性能", "手机", "屏幕", "相机", "续航", "开发者", "大会", "人工智能", "模型", "推理",
        "浏览器", "排版", "字体", "颜色", "交互", "手势", "列表", "滚动", "缓存", "同步", "服务器", "评测",
    )
    private val enWords = listOf(
        "Nova", "announces", "new", "design", "system", "review", "the", "future", "of", "reading", "web",
        "fast", "smooth", "app", "interface", "privacy", "open", "source", "developer", "tools", "model",
        "typography", "motion", "gesture", "list", "performance", "battery", "camera", "display", "update",
    )

    suspend fun populate(db: RunnelDatabase, count: Int = 200) {
        val rnd = Random(42)
        val feedEntities = feeds.mapIndexed { i, (title, folder, site) ->
            FeedEntity(
                id = "feed/demo-$i",
                title = title,
                folderId = folder?.let { "user/-/label/$it" },
                siteUrl = site,
                feedUrl = "$site/feed",
                iconUrl = null,
                sortIndex = i,
            )
        }
        val now = System.currentTimeMillis()
        val articles = ArrayList<ArticleEntity>(count)
        val contents = ArrayList<ArticleContentEntity>(count)
        repeat(count) { n ->
            val (a, c) = article(rnd, n.toLong() + 1, feedEntities, now - n * 7L * 60_000L - rnd.nextLong(0, 300_000), now)
            articles += a
            contents += c
        }
        db.inTransaction {
            db.feedDao().upsertFolders(folders.map { (t, i) -> FolderEntity("user/-/label/$t", t, i) })
            db.feedDao().upsertFeeds(feedEntities)
            articles.chunked(500).forEach { db.articleDao().insertArticles(it) }
            contents.chunked(200).forEach { db.articleDao().insertContents(it) }
        }
    }

    /** 演示账号“同步”：加几篇新文章，用来测“N 篇新文章”提示。 */
    suspend fun addFresh(db: RunnelDatabase, n: Int): Int {
        val rnd = Random(System.nanoTime())
        val feedEntities = db.feedDao().allFeeds().ifEmpty { return 0 }
        val now = System.currentTimeMillis()
        val pairs = (0 until n).map { article(rnd, now * 10 + it, feedEntities, now - it * 1000L, now) }
        db.inTransaction {
            db.articleDao().insertArticles(pairs.map { it.first })
            db.articleDao().insertContents(pairs.map { it.second })
        }
        return n
    }

    private fun article(rnd: Random, id: Long, feeds: List<FeedEntity>, publishedAt: Long, now: Long): Pair<ArticleEntity, ArticleContentEntity> {
        val feed = feeds[rnd.nextInt(feeds.size)]
        val zh = rnd.nextBoolean()
        val title = if (zh) sentence(rnd, zhWords, 6, 14, "") else sentence(rnd, enWords, 5, 12, " ").replaceFirstChar { it.uppercase() }
        val hasImage = rnd.nextInt(3) != 0
        // 正文是这里拼出来的干净 HTML，不用再走 ArticleProcessor 清洗（逐篇解析很慢）；摘要取正文纯文本
        val text = StringBuilder()
        val html = buildString {
            if (hasImage) append("<figure><img src=\"https://picsum.photos/seed/$id/1200/800\" width=\"1200\" height=\"800\"><figcaption>图片说明 $id</figcaption></figure>")
            repeat(rnd.nextInt(4, 12)) { p ->
                val para = if (zh) sentence(rnd, zhWords, 40, 120, "") + "。" else sentence(rnd, enWords, 30, 90, " ") + "."
                append("<p>").append(para).append("</p>")
                text.append(para).append(' ')
                if (p == 2) append("<blockquote><p>${sentence(rnd, zhWords, 12, 24, "")}。</p></blockquote>")
                if (p == 4 && rnd.nextInt(4) == 0) append("<pre><code>fun main() {\n    println(\"Hello, Runnel #$id\")\n}</code></pre>")
                if (p == 5 && rnd.nextInt(5) == 0) append("<table><tr><th>项目</th><th>数值</th></tr><tr><td>帧时间 P99</td><td>7.9 ms</td></tr><tr><td>卡顿帧</td><td>0.3%</td></tr></table>")
            }
        }
        val read = publishedAt < now - 3 * 24 * 3600_000L && rnd.nextInt(5) != 0
        val article = ArticleEntity(
            id = id,
            feedId = feed.id,
            title = title,
            author = if (rnd.nextBoolean()) "作者 ${rnd.nextInt(1, 30)}" else null,
            url = "${feed.siteUrl}/p/$id",
            publishedAt = publishedAt,
            summary = text.take(200).trim().toString(),
            thumbUrl = if (hasImage) "https://picsum.photos/seed/$id/240/240" else null,
            readingMinutes = (text.length / 500).coerceAtLeast(1),
            isRead = read,
            isStarred = rnd.nextInt(25) == 0,
            fetchedAt = now,
        )
        return article to ArticleContentEntity(id, html, null)
    }

    private fun sentence(rnd: Random, words: List<String>, min: Int, max: Int, sep: String): String =
        (0 until rnd.nextInt(min, max)).joinToString(sep) { words[rnd.nextInt(words.size)] }
}

/** 演示账号：“同步”只是加几篇随机文章，不能管理订阅。 */
class DemoBackend(private val db: RunnelDatabase) : AccountBackend {
    override val canManageFeeds get() = false
    override val findsIcons get() = false

    override suspend fun sync(progress: (String) -> Unit): Int = DemoData.addFresh(db, 3)

    override suspend fun addFeed(input: String, folder: FolderRef?) = throw FeedException("演示数据不能添加订阅")

    override suspend fun editFeed(feed: FeedEntity, title: String, folder: FolderRef?) = throw FeedException("演示数据不能修改订阅")

    override suspend fun unsubscribe(feed: FeedEntity) = throw FeedException("演示数据不能取消订阅")
}
