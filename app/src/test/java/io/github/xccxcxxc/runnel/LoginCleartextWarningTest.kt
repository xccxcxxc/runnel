package io.github.xccxcxxc.runnel

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import io.github.xccxcxxc.runnel.data.Settings
import io.github.xccxcxxc.runnel.ui.login.LoginScreen
import io.github.xccxcxxc.runnel.ui.theme.RunnelTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode

/** 登录页：服务器填公网的 http 地址时，在表单下面提示密码会明文传输；局域网地址和 https 不提示。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
@Config(sdk = [35])
class LoginCleartextWarningTest {
    @get:Rule
    val rule = createComposeRule()

    private fun typeServer(text: String) {
        val server = rule.onAllNodes(hasSetTextAction()).onFirst() // 第一个输入框是“服务器”
        server.performTextClearance()
        server.performTextInput(text)
        rule.waitForIdle()
    }

    @Test
    fun warnsOnlyForPublicHttp() {
        rule.setContent { RunnelTheme(Settings()) { LoginScreen(io.github.xccxcxxc.runnel.data.AccountType.FRESHRSS, onBack = {}, onAdded = {}) } }
        rule.onNodeWithTag("cleartext_warning").assertDoesNotExist()

        typeServer("http://rss.example.com")
        rule.onNodeWithTag("cleartext_warning").assertExists()

        typeServer("http://192.168.1.10:8080")
        rule.onNodeWithTag("cleartext_warning").assertDoesNotExist()

        typeServer("https://rss.example.com")
        rule.onNodeWithTag("cleartext_warning").assertDoesNotExist()
    }
}
