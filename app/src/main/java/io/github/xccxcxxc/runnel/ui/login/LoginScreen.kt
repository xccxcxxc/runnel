package io.github.xccxcxxc.runnel.ui.login

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Icon
import io.github.xccxcxxc.runnel.Graph
import io.github.xccxcxxc.runnel.data.Account
import io.github.xccxcxxc.runnel.data.AccountType
import io.github.xccxcxxc.runnel.ui.components.BarButton
import io.github.xccxcxxc.runnel.ui.components.GroupedSection
import io.github.xccxcxxc.runnel.ui.components.RowDivider
import io.github.xccxcxxc.runnel.ui.components.RunnelIcons
import io.github.xccxcxxc.runnel.ui.components.pressHighlight
import io.github.xccxcxxc.runnel.ui.home.accountIcon
import io.github.xccxcxxc.runnel.ui.theme.Runnel
import io.github.xccxcxxc.runnel.sync.isPublicCleartext
import kotlinx.coroutines.launch

/**
 * 添加账号（照 Reeder 的服务列表）：本机、FreshRSS / Miniflux / 其他 GReader 服务器、演示数据。
 * 没有账号时是根页面（没有返回按钮）；服务器类型点进去是 [LoginScreen]。
 */
@Composable
fun AddAccountScreen(
    autoDemo: Boolean,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onServer: (AccountType) -> Unit,
    onAdded: (Account) -> Unit,
) {
    val graph = Graph.get()
    val colors = Runnel.colors
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun add(block: suspend () -> Account) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            runCatching { block() }
                .onSuccess(onAdded)
                .onFailure { error = it.message ?: it.javaClass.simpleName }
            busy = false
        }
    }

    // macrobenchmark 启动时带参数：直接进演示数据
    LaunchedEffect(autoDemo) { if (autoDemo) add { graph.addDemoAccount() } }

    FormPage(canGoBack, onBack) {
        Text(if (canGoBack) "添加账号" else "Runnel", style = Runnel.type.largeTitle, color = colors.label, modifier = Modifier.padding(horizontal = 32.dp))
        Text(
            "在这台设备上订阅，或者连接你的 FreshRSS、Miniflux 服务器",
            style = Runnel.type.subhead,
            color = colors.secondaryLabel,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp),
        )
        GroupedSection(footer = "订阅源由这台设备自己定时抓取，已读和星标只保存在这台设备上。可以导入别的阅读器导出的 OPML。") {
            ServiceRow(AccountType.LOCAL, "本机", Modifier.testTag("add_local")) { add { graph.addLocalAccount() } }
        }
        GroupedSection("同步服务", footer = "抓取由服务器完成，已读和星标和其他设备同步。") {
            ServiceRow(AccountType.FRESHRSS, "FreshRSS") { onServer(AccountType.FRESHRSS) }
            RowDivider()
            ServiceRow(AccountType.MINIFLUX, "Miniflux") { onServer(AccountType.MINIFLUX) }
            RowDivider()
            ServiceRow(AccountType.GREADER, "其他 Google Reader API 服务") { onServer(AccountType.GREADER) }
        }
        GroupedSection {
            ServiceRow(AccountType.DEMO, "先用演示数据看看", Modifier.testTag("demo_button")) { add { graph.addDemoAccount() } }
        }
        error?.let {
            Text(it, style = Runnel.type.footnote, color = colors.destructive, modifier = Modifier.padding(horizontal = 32.dp, vertical = 10.dp))
        }
        if (busy) {
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp).size(22.dp), color = colors.accent, strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun ServiceRow(type: AccountType, title: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = Runnel.colors
    Row(
        modifier.fillMaxWidth().pressHighlight(onClick).heightIn(min = 52.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(accountIcon(type), null, tint = colors.accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(title, style = Runnel.type.body, color = colors.label, modifier = Modifier.weight(1f))
        Icon(RunnelIcons.ChevronRight, null, tint = colors.tertiaryLabel, modifier = Modifier.size(16.dp))
    }
}

/** 登录页、添加账号页共用的外框：分组背景、可滚动，[canGoBack] 时左上角有返回。 */
@Composable
private fun FormPage(canGoBack: Boolean, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val colors = Runnel.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.groupedBackground)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Box(Modifier.fillMaxWidth().height(44.dp).padding(start = 4.dp)) {
            if (canGoBack) BarButton(RunnelIcons.Back, "返回", onClick = onBack)
        }
        content()
        Spacer(Modifier.height(32.dp))
    }
}

/** 服务器账号的登录表单。 */
@Composable
fun LoginScreen(type: AccountType, onBack: () -> Unit, onAdded: (Account) -> Unit) {
    val graph = Graph.get()
    val colors = Runnel.colors
    val scope = rememberCoroutineScope()
    var server by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun login() {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            runCatching { graph.addGReaderAccount(type, server, user, pass) }
                .onSuccess(onAdded)
                .onFailure { error = it.message ?: it.javaClass.simpleName }
            busy = false
        }
    }

    FormPage(canGoBack = true, onBack = onBack) {
        Text(if (type == AccountType.GREADER) "Google Reader API" else type.label, style = Runnel.type.largeTitle, color = colors.label, modifier = Modifier.padding(horizontal = 32.dp))
        Text(
            "连接你的服务器",
            style = Runnel.type.subhead,
            color = colors.secondaryLabel,
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 6.dp),
        )
        GroupedSection(
            footer = when (type) {
                AccountType.FRESHRSS -> "地址填 FreshRSS 首页地址（会自动加上 /api/greader.php）。需要在 FreshRSS「设置 → 认证」里允许 API 访问，并在「设置 → 账户」里设置 API 密码，这里填 API 密码。"
                AccountType.MINIFLUX -> "地址填 Miniflux 首页地址。在 Miniflux「设置 → 集成 → Google Reader」里启用，并设置单独的用户名和密码，这里填那一组。"
                else -> "填 Google Reader 兼容 API 的根地址（/accounts/ClientLogin 的上一级）。"
            },
        ) {
            Field("服务器", server, "https://rss.example.com", KeyboardType.Uri) { server = it }
            RowDivider()
            Field("用户名", user, "username", KeyboardType.Text) { user = it }
            RowDivider()
            Field("密码", pass, "API 密码", KeyboardType.Password, secret = true) { pass = it }
        }
        if (isPublicCleartext(server)) {
            Text(
                "这个地址用的是 http，登录时的密码和之后的内容都会明文传输。服务器在公网上的话，建议改用 https。",
                style = Runnel.type.footnote,
                color = colors.destructive,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp).testTag("cleartext_warning"),
            )
        }
        error?.let {
            Text(it, style = Runnel.type.footnote, color = colors.destructive, modifier = Modifier.padding(horizontal = 32.dp, vertical = 10.dp))
        }
        Spacer(Modifier.height(20.dp))
        PrimaryButton(
            text = if (busy) "正在连接…" else "登录",
            busy = busy,
            enabled = server.isNotBlank() && user.isNotBlank() && pass.isNotBlank(),
            modifier = Modifier.testTag("login_button"),
        ) { login() }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    placeholder: String,
    keyboard: KeyboardType,
    secret: Boolean = false,
    onChange: (String) -> Unit,
) {
    val colors = Runnel.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = Runnel.type.body, color = colors.label, modifier = Modifier.width(72.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = Runnel.type.body, color = colors.tertiaryLabel)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = Runnel.type.body.copy(color = colors.label),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PrimaryButton(text: String, busy: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = Runnel.colors
    Row(
        modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) colors.accent else colors.highlight)
            .then(if (enabled && !busy) Modifier.pressHighlight(onClick) else Modifier),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = Runnel.type.headline, color = if (enabled) Color.White else colors.tertiaryLabel)
    }
}
