package io.github.xccxcxxc.runnel

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.xccxcxxc.runnel.data.SyncMode
import io.github.xccxcxxc.runnel.data.db.ArticleContentEntity
import io.github.xccxcxxc.runnel.data.db.ArticleEntity
import io.github.xccxcxxc.runnel.data.db.FeedEntity
import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.github.xccxcxxc.runnel.data.Account
import io.github.xccxcxxc.runnel.data.AccountType
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Headers.Companion.headersOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.Collections

/**
 * 离线图片（设置 → 账号 → 离线图片）：只下勾选订阅源里未读和星标文章的图片，断网后阅读页能从缓存拿到。
 * 用 App 自己的数据库和图片缓存（Graph），图片服务器是本机的 MockWebServer。
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class OfflineImagesTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private val graph get() = Graph.get()
    private lateinit var account: AccountSession
    private var scenario: ActivityScenario<MainActivity>? = null
    private val server = MockWebServer()
    private val requested = Collections.synchronizedList(mutableListOf<String>())
    // 每次运行换个路径，不受之前缓存的影响
    private val run = System.nanoTime()

    @Before
    fun setUp() = runBlocking {
        account = TestAccounts.fresh()
        val png = ByteArrayOutputStream().also {
            Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }.compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requested += request.url.encodedPath
                return MockResponse.Builder()
                    .headers(headersOf("Content-Type", "image/png", "Cache-Control", "no-store"))
                    .body(okio.Buffer().write(png))
                    .build()
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        scenario?.close()
        server.close()
    }

    private fun img(name: String) = server.url("/$run/$name.png").toString()

    @Test
    fun downloadsSelectedFeedsUnreadAndStarredOnly() = runBlocking {
        val now = System.currentTimeMillis()
        account.db.feedDao().upsertFeeds(
            listOf(FeedEntity("feed/a", "A", null, null, null, null, 0), FeedEntity("feed/b", "B", null, null, null, null, 1)),
        )
        fun article(id: Long, feed: String, read: Boolean, starred: Boolean) =
            ArticleEntity(id, feed, "文章 $id", null, "https://ex.com/$id", now - id, "摘要", null, 1, read, starred, now)
        account.db.articleDao().insertArticles(
            listOf(
                article(1, "feed/a", read = false, starred = false),
                article(2, "feed/a", read = true, starred = false),
                article(3, "feed/a", read = true, starred = true),
                article(4, "feed/b", read = false, starred = false),
            ),
        )
        account.db.articleDao().insertContents(
            (1L..4L).map { ArticleContentEntity(it, "<p>正文</p><p><img src=\"${img("a$it")}\"></p>", null) },
        )

        account.prefs.update { it.copy(offlineImageFeeds = setOf("feed/a")) }
        account.offlineImages.requestPrefetch().join()

        assertEquals(setOf("/$run/a1.png", "/$run/a3.png"), requested.toSet())

        val urls = listOf("a1", "a3", "a4").map(::img)
        server.close() // 断网
        val headers = mapOf("Accept" to "image/webp,*/*", "Referer" to "https://ex.com/1")
        assertNotNull(graph.readerImages.fetch(urls[0], headers))
        assertNotNull(graph.readerImages.fetch(urls[1], headers))
        assertNull(graph.readerImages.fetch(urls[2], headers))
    }

    /** 设置 → 账号 → 离线图片：默认一个都不选，点订阅源勾上，页面上显示缓存用量。 */
    @Test
    fun settingsPageSelectsFeeds() {
        runBlocking { account.db.feedDao().upsertFeeds(listOf(FeedEntity("feed/offline", "离线测试源", null, null, null, null, 0))) }
        scenario = ActivityScenario.launch(Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java))
        // 启动进的是账号首页；设置在账号列表左上角
        rule.waitUntilAtLeastOneExists(hasContentDescription("账号"), 30_000)
        rule.onNodeWithContentDescription("账号").performClick()
        rule.waitUntilAtLeastOneExists(hasContentDescription("设置"), 10_000)
        rule.onNodeWithContentDescription("设置").performClick()
        rule.waitUntilAtLeastOneExists(hasText("添加账号…"), 10_000)
        // 账号列表（在表单下面）也有“演示数据”这一行，点表单里的那个（最后画的）
        rule.onAllNodes(hasText("演示数据") and hasClickAction()).onLast().performClick()
        rule.waitUntilAtLeastOneExists(hasText("离线图片"), 10_000)
        rule.onNode(hasText("离线图片") and hasClickAction()).performScrollTo().performClick()
        rule.waitUntilAtLeastOneExists(hasText("全不选"), 10_000)
        rule.onNode(hasText("图片缓存已用", substring = true)).assertExists()
        assertEquals(emptySet<String>(), account.prefs.settings.value.offlineImageFeeds)
        // 首页列表（在表单下面）也有这个订阅源，点表单里的那个（最后画的）
        rule.onAllNodes(hasText("离线测试源") and hasClickAction()).onLast().performScrollTo().performClick()
        rule.waitUntil(5_000) { account.prefs.settings.value.offlineImageFeeds == setOf("feed/offline") }
        rule.onNodeWithContentDescription("已选").assertExists()
    }
}
