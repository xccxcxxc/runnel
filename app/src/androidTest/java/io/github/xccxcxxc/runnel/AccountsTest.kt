package io.github.xccxcxxc.runnel

import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.xccxcxxc.runnel.data.AccountType
import io.github.xccxcxxc.runnel.data.ArticleSource
import io.github.xccxcxxc.runnel.data.FilterMode
import io.github.xccxcxxc.runnel.sync.DemoData
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 多账号（照 Reeder）：启动进上次打开的账号的首页，返回是账号列表（带未读数），点别的账号进它的首页；
 * 在设置里移除一个账号后回到账号列表，另一个账号不受影响。
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class AccountsTest {
    @get:Rule
    val rule = createEmptyComposeRule()

    private var scenario: ActivityScenario<MainActivity>? = null
    private val graph get() = Graph.get()
    private lateinit var demo: AccountSession
    private lateinit var local: AccountSession

    @Before
    fun setUp() {
        demo = TestAccounts.fresh(AccountType.DEMO)
        runBlocking { DemoData.populate(demo.db, count = 60) }
        local = graph.session(graph.addLocalAccount().id)!!
        local.prefs.update { it.copy(syncMode = io.github.xccxcxxc.runnel.data.SyncMode.MANUAL) }
    }

    @After
    fun tearDown() {
        scenario?.close()
        TestAccounts.removeAll()
    }

    private fun launch() {
        scenario = ActivityScenario.launch(Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java))
    }

    @Test
    fun opensLastAccountAndSwitchesThroughAccountList() {
        launch()
        // 最后加的是本机账号：直接进它的首页（空的，可以添加订阅）
        rule.waitUntilAtLeastOneExists(hasText("没有未读文章"), 30_000)
        rule.onNodeWithContentDescription("添加订阅").assertExists()
        rule.onNodeWithContentDescription("账号").performClick()

        rule.waitUntilAtLeastOneExists(hasTestTag("accounts_list"), 10_000)
        val unread = runBlocking { demo.repo.loadList(ArticleSource.All, FilterMode.UNREAD).size }
        rule.onNodeWithTag("account_${demo.id}").assertExists()
        rule.onNodeWithTag("account_${local.id}").assertExists()
        rule.onAllNodes(hasText(unread.toString())).onFirst().assertExists()

        rule.onNodeWithTag("account_${demo.id}").performClick()
        rule.waitUntilAtLeastOneExists(hasText("Maple Notes"), 10_000)
        // 演示账号不能添加订阅
        rule.onNodeWithContentDescription("添加订阅").assertDoesNotExist()
        rule.waitForIdle()
        assertEquals("下次启动进演示账号", demo.id, graph.prefs.lastAccountId)

        scenario?.close()
        launch()
        rule.waitUntilAtLeastOneExists(hasText("Maple Notes"), 30_000)
    }

    @Test
    fun removeAccountFromSettings() {
        launch()
        rule.waitUntilAtLeastOneExists(hasContentDescription("账号"), 30_000)
        rule.onNodeWithContentDescription("账号").performClick()
        rule.waitUntilAtLeastOneExists(hasContentDescription("设置"), 10_000)
        rule.onNodeWithContentDescription("设置").performClick()
        rule.waitUntilAtLeastOneExists(hasText("添加账号…"), 10_000)
        // 账号列表（在表单下面）也有“本机”这一行，点表单里的那个（最后画的）
        rule.onAllNodes(hasText("本机") and hasClickAction()).onLast().performClick()
        rule.waitUntilAtLeastOneExists(hasText("从 OPML 导入…"), 10_000)
        rule.onNodeWithText("移除账号…").performScrollTo().performClick()
        rule.waitUntilAtLeastOneExists(hasText("移除"), 5_000)
        rule.onNode(hasText("移除") and hasClickAction()).performClick()

        rule.waitUntilAtLeastOneExists(hasTestTag("accounts_list"), 10_000)
        rule.waitUntil(5_000) { graph.accounts.value.map { it.id } == listOf(demo.id) }
        rule.onNodeWithTag("account_${local.id}").assertDoesNotExist()
        rule.onNodeWithTag("account_${demo.id}").assertExists()
        // 另一个账号的数据还在
        assertEquals(60, runBlocking { demo.repo.loadList(ArticleSource.All, FilterMode.ALL).size })
    }
}
