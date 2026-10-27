package io.github.xccxcxxc.runnel

import android.content.Intent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.xccxcxxc.runnel.data.Account
import io.github.xccxcxxc.runnel.data.AccountType
import io.github.xccxcxxc.runnel.data.ArticleSource
import io.github.xccxcxxc.runnel.data.FilterMode
import io.github.xccxcxxc.runnel.data.PullBottom
import io.github.xccxcxxc.runnel.data.SyncMode
import io.github.xccxcxxc.runnel.sync.DemoData
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 整个 App 跑在模拟器上（真实的数据库、真实的手势和回弹）：首页 → 订阅源列表 → 拉到底继续上拉松手。
 * 断言用户看到的最终结果：数据库里真的标了已读、列表页真的离开了。
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ListGesturesTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null
    private val graph get() = Graph.get()
    private lateinit var account: AccountSession

    /** 演示数据里的订阅源；列表里没有文件夹的那个，首页直接能点到。 */
    private lateinit var feed: ArticleSource.Feed
    private lateinit var firstTitle: String

    @Before
    fun setUp() {
        // 同步方式设为“手动”（用户可选的设置）：演示账号启动时的“同步”会加几篇新文章，未读数就不固定了
        account = TestAccounts.fresh()
        graph.prefs.updateSettings { it.copy(pullBottom = PullBottom.MARK_ALL_READ, markAllAsk = false, markAllOpenNext = false) }
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun unreadInFeed(): Int = runBlocking { account.repo.loadList(feed, FilterMode.UNREAD).size }

    /**
     * 小份演示数据，只留 [title] 最新的 4 篇未读（内容不满一屏，和用户的订阅源一样），进入它的列表。
     * [next] 不为空时那个订阅源也留 2 篇未读，“下一个订阅源”就是它。
     */
    private fun openShortFeed(title: String = "Maple Notes", next: String? = null) {
        runBlocking {
            DemoData.populate(account.db, count = 150)
            val feeds = account.db.feedDao().allFeeds()
            feed = feeds.first { it.title == title }.let { ArticleSource.Feed(it.id, it.title, null) }
            val keep = account.repo.loadList(feed, FilterMode.ALL).take(4) +
                (next?.let { n -> account.repo.loadList(feeds.first { it.title == n }.let { ArticleSource.Feed(it.id, it.title, null) }, FilterMode.ALL).take(2) } ?: emptyList())
            account.db.articleDao().setRead(account.db.articleDao().allIds().filter { id -> keep.none { it.id == id } }, true)
            firstTitle = keep.take(4).minBy { it.publishedAt }.title // “未读”默认旧→新
        }
        check(unreadInFeed() == 4)
        scenario = ActivityScenario.launch(Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java))
        rule.waitUntilAtLeastOneExists(hasText(feed.title), 30_000)
        rule.onNodeWithText(feed.title).performClick()
        rule.waitUntilAtLeastOneExists(hasTestTag("article_list"), 10_000)
        // 副标题“4 篇未读”出现 = 列表数据载入完成
        rule.waitUntilAtLeastOneExists(hasText("4 篇未读"), 15_000)
        rule.waitForIdle()
    }

    private fun pullUp(release: Boolean = true) {
        rule.onNodeWithTag("article_list").performTouchInput {
            down(Offset(centerX, height * 0.85f))
            val steps = 40
            val dy = height * 0.6f / steps
            repeat(steps) { moveBy(Offset(0f, -dy), delayMillis = 16) }
            repeat(5) { moveBy(Offset.Zero, delayMillis = 16) }
            if (release) up()
        }
        rule.waitForIdle()
    }

    private fun assertUnreadBecomesZero() {
        rule.waitUntil(10_000) { unreadInFeed() == 0 }
        assertEquals(0, unreadInFeed())
    }

    private fun listGone() =
        rule.waitUntil(10_000) { rule.onAllNodes(hasTestTag("article_list")).fetchSemanticsNodes().isEmpty() }

    @Test
    fun pullFromBottomMarksAllReadAndLeaves() {
        openShortFeed()
        pullUp()
        listGone()
        assertUnreadBecomesZero()
    }

    /** 底栏“全部标为已读”按钮和上拉走同一个回调，同样要真的标已读。 */
    @Test
    fun markAllButtonMarksAllReadAndLeaves() {
        openShortFeed()
        rule.onNodeWithContentDescription("全部标为已读").performClick()
        listGone()
        assertUnreadBecomesZero()
    }

    /** 文章都已读过（列表是快照，文章还留在里面）时上拉松手直接离开。 */
    @Test
    fun pullFromBottomLeavesWhenNothingUnread() {
        openShortFeed()
        runBlocking {
            val ids = account.repo.loadList(feed, FilterMode.ALL).map { it.id }
            account.repo.setRead(ids, true)
        }
        rule.waitForIdle()
        pullUp()
        listGone()
    }

    /**
     * 内容不满一屏的列表上拉时也有橡皮筋回弹（和文件夹的长列表一样，照 iOS）：
     * 手指还按着时，列表内容要跟着往上移。以前短列表拉不动，松手直接跳走。
     */
    @Test
    fun shortListBouncesWhenPulledUp() {
        openShortFeed()
        val before = rule.onNodeWithText(firstTitle).getBoundsInRoot().top
        pullUp(release = false)
        val during = rule.onNodeWithText(firstTitle).getBoundsInRoot().top
        rule.onNodeWithTag("article_list").performTouchInput { up() }
        assertTrue("上拉时列表没有跟着移动：before=$before during=$during", before - during > 40.dp)
        listGone()
        assertUnreadBecomesZero()
    }

    /** 列表里的文章，按显示顺序（“未读”默认旧→新）。 */
    private fun shownTitles(): List<String> = runBlocking {
        account.repo.loadList(feed, FilterMode.UNREAD).sortedBy { it.publishedAt }.map { it.title }
    }

    private fun isRead(title: String): Boolean = runBlocking {
        account.repo.loadList(feed, FilterMode.ALL).first { it.title == title }.isRead
    }

    private fun longPress(title: String) {
        rule.onAllNodes(hasText(title))[0].performTouchInput { longClick() }
        rule.waitUntilAtLeastOneExists(hasTestTag("context_menu"), 5_000)
        rule.waitForIdle()
    }

    private fun menuGone() =
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("context_menu")).fetchSemanticsNodes().isEmpty() }

    /**
     * 长按文章 = 浮动菜单：已读/星标、将以上/以下标为已读、分享。
     * “将以上标为已读”只标它上面的，不含它自己；“将以下”同理。
     */
    @Test
    fun longPressMenuMarksAboveAndBelow() {
        openShortFeed()
        val titles = shownTitles()
        val rowTop = rule.onAllNodes(hasText(titles[1]))[0].getBoundsInRoot().top
        longPress(titles[1])
        // 卡片从原来那一行原地浮起（只缩小一点），不是跳到别处
        with(rule.density) {
            // 原来那一行（透明）和卡片里各有一个同样的标题，取离原位最远的那个
            val cardTop = rule.onAllNodes(hasText(titles[1])).fetchSemanticsNodes()
                .map { it.boundsInRoot.top }.maxBy { kotlin.math.abs(it - rowTop.toPx()) }
            assertTrue("卡片位置不对：row=${rowTop.toPx()} card=$cardTop", kotlin.math.abs(cardTop - rowTop.toPx()) < 12.dp.toPx())
        }
        listOf("标为已读", "加星标", "将以上标为已读", "将以下标为已读", "分享…").forEach {
            rule.onNode(hasTestTag("context_menu")).assert(hasAnyDescendant(hasText(it)))
        }
        rule.onNodeWithText("将以上标为已读").performClick()
        menuGone()
        rule.waitUntil(5_000) { isRead(titles[0]) }
        assertEquals(listOf(true, false, false, false), titles.map(::isRead))

        longPress(titles[2])
        rule.onNodeWithText("将以下标为已读").performClick()
        menuGone()
        rule.waitUntil(5_000) { isRead(titles[3]) }
        assertEquals(listOf(true, false, false, true), titles.map(::isRead))
    }

    /** 第一篇没有“将以上”，最后一篇没有“将以下”；点菜单外面收起，什么都不改。 */
    @Test
    fun longPressMenuEdgesAndDismiss() {
        openShortFeed()
        val titles = shownTitles()
        longPress(titles[0])
        assertTrue(rule.onAllNodesWithText("将以上标为已读").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText("将以下标为已读").assertExists()
        rule.onNodeWithTag("context_scrim").performTouchInput { click(Offset(centerX, height * 0.05f)) }
        menuGone()
        assertEquals(4, unreadInFeed())
        longPress(titles[3])
        assertTrue(rule.onAllNodesWithText("将以下标为已读").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText("标为已读").performClick()
        menuGone()
        rule.waitUntil(5_000) { isRead(titles[3]) }
        assertEquals(3, unreadInFeed())
    }

    /**
     * 从底部上拉 = “进入下一个订阅源”：先把当前列表全部标为已读，再打开下一个订阅源（下一个源的未读不动）。
     * 关掉“全部已读后打开下一个订阅源”，确认这是上拉选项自己的行为。
     */
    @Test
    fun pullToNextFeedMarksCurrentRead() {
        graph.prefs.updateSettings { it.copy(pullBottom = PullBottom.NEXT_FEED) }
        openShortFeed(title = "Pixel Garden", next = "Maple Notes")
        val nextFeed = runBlocking {
            account.db.feedDao().allFeeds().first { it.title == "Maple Notes" }.let { ArticleSource.Feed(it.id, it.title, null) }
        }
        pullUp()
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("2 篇未读")).fetchSemanticsNodes().isNotEmpty() }
        assertUnreadBecomesZero()
        assertEquals(2, runBlocking { account.repo.loadList(nextFeed, FilterMode.UNREAD).size })
    }

    /**
     * “全部标为已读后打开下一个订阅源”：下一个订阅源从底部升上来，不是从右边推进来。
     * 转场中途停住时钟量位置：新列表的大标题横向位置已经就位，纵向还在下面。
     */
    @Test
    fun nextFeedEntersFromBottom() {
        graph.prefs.updateSettings { it.copy(markAllOpenNext = true) }
        openShortFeed(title = "Pixel Garden", next = "Maple Notes")
        fun nextTitleBounds() = rule.onAllNodes(hasText("Maple Notes")).fetchSemanticsNodes()
            .map { it.boundsInRoot }.minByOrNull { it.top }
        pullUp(release = false)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("article_list").performTouchInput { up() }
        val end = System.currentTimeMillis() + 10_000
        while (nextTitleBounds() == null && System.currentTimeMillis() < end) {
            rule.mainClock.advanceTimeByFrame()
            Thread.sleep(16)
        }
        rule.mainClock.advanceTimeBy(120)
        val mid = checkNotNull(nextTitleBounds()) { "下一个订阅源没有出现" }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("2 篇未读")).fetchSemanticsNodes().isNotEmpty() }
        val final = checkNotNull(nextTitleBounds())
        val density = rule.density
        with(density) {
            assertTrue("横向不该有位移：mid=${mid.left} final=${final.left}", kotlin.math.abs(mid.left - final.left) < 2.dp.toPx())
            assertTrue("转场中新列表应还在下方：mid=${mid.top} final=${final.top}", mid.top - final.top > 100.dp.toPx())
        }
        assertUnreadBecomesZero()
    }
}
