package io.github.xccxcxxc.runnel.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import io.github.xccxcxxc.runnel.data.db.FolderEntity
import io.github.xccxcxxc.runnel.ui.components.GroupedRow
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.xccxcxxc.runnel.LocalAccount
import io.github.xccxcxxc.runnel.data.AccountType
import io.github.xccxcxxc.runnel.sync.FolderRef
import io.github.xccxcxxc.runnel.ui.components.BarTextButton
import io.github.xccxcxxc.runnel.ui.components.CheckRow
import io.github.xccxcxxc.runnel.ui.components.GroupedSection
import io.github.xccxcxxc.runnel.ui.components.RowDivider
import io.github.xccxcxxc.runnel.ui.components.SheetPage
import io.github.xccxcxxc.runnel.ui.theme.Runnel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 添加订阅（首页右上角 “+”）：填地址、选文件夹。服务器账号交给服务器订阅后同步一次；
 * 本机账号自己找订阅源、抓一次再关表单。
 */
@Composable
fun AddFeedSheet(onDone: () -> Unit) {
    val session = LocalAccount.current
    val colors = Runnel.colors
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val folders by session.repo.folders.collectAsStateWithLifecycle(emptyList())
    var url by rememberSaveable { mutableStateOf("") }
    var folder by remember { mutableStateOf<FolderRef?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val remote = session.sync.canManageFeeds
    val focus = remember { FocusRequester() }

    // 表单滑上来之后再弹键盘：两个动画叠在一起会掉帧
    LaunchedEffect(remote) {
        if (!remote) return@LaunchedEffect
        delay(450)
        runCatching { focus.requestFocus() }
    }

    fun close() {
        focusManager.clearFocus()
        onDone()
    }

    fun add() {
        if (busy || !remote || url.isBlank()) return
        busy = true
        error = null
        scope.launch {
            runCatching { session.sync.addFeed(url, folder) }
                .onSuccess { close() }
                .onFailure { error = it.message ?: it.javaClass.simpleName }
            busy = false
        }
    }

    SheetPage(
        title = "添加订阅",
        leading = { BarTextButton("取消", color = colors.accent, onClick = ::close) },
        trailing = {
            if (busy) {
                CircularProgressIndicator(Modifier.padding(horizontal = 18.dp).size(20.dp), color = colors.accent, strokeWidth = 2.dp)
            } else {
                BarTextButton("添加", color = colors.accent, style = Runnel.type.headline, enabled = remote && url.isNotBlank(), onClick = ::add)
            }
        },
    ) {
        item(key = "url") {
            GroupedSection(
                footer = when {
                    !remote -> "演示数据不能添加订阅。请在账号列表右上角添加本机账号，或连接自己的 FreshRSS、Miniflux。"
                    session.type == AccountType.LOCAL -> "填订阅源地址，也可以直接填网站首页地址，会从网页里找到订阅源。"
                    else -> "填订阅源地址，也可以直接填网站首页地址，服务器会从网页里找到订阅源。"
                },
            ) {
                Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        if (url.isEmpty()) Text("https://example.com/feed", style = Runnel.type.body, color = colors.tertiaryLabel)
                        BasicTextField(
                            value = url,
                            onValueChange = {
                                url = it
                                error = null
                            },
                            enabled = remote && !busy,
                            singleLine = true,
                            textStyle = Runnel.type.body.copy(color = colors.label),
                            cursorBrush = SolidColor(colors.accent),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                            keyboardActions = KeyboardActions(onDone = { add() }),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("add_feed_url"),
                        )
                    }
                }
            }
            error?.let {
                Text(it, style = Runnel.type.footnote, color = colors.destructive, modifier = Modifier.padding(horizontal = 32.dp, vertical = 10.dp))
            }
        }
        if (remote) {
            item(key = "folders") { FolderPicker(folders, folder) { folder = it } }
        }
    }
}

/** 选文件夹：不放进文件夹 / 已有的文件夹 / 新建（输入名字）。 */
@Composable
fun FolderPicker(folders: List<FolderEntity>, selected: FolderRef?, onSelect: (FolderRef?) -> Unit) {
    val colors = Runnel.colors
    var naming by remember { mutableStateOf<String?>(null) }
    // 新建的文件夹还不在库里，也要显示成选中的那一项
    val extra = selected?.takeIf { s -> folders.none { it.id == s.id } }
    GroupedSection("文件夹") {
        CheckRow("无", selected == null) { onSelect(null) }
        for (f in folders) {
            RowDivider()
            CheckRow(f.title, selected?.id == f.id) { onSelect(FolderRef(f.id, f.title)) }
        }
        if (extra != null) {
            RowDivider()
            CheckRow(extra.title, true) {}
        }
        RowDivider()
        GroupedRow("新建文件夹…", titleColor = colors.accent, onClick = { naming = "" })
    }
    naming?.let { text ->
        TextPrompt(
            title = "新建文件夹",
            value = text,
            onChange = { naming = it },
            onConfirm = {
                naming = null
                val name = text.trim()
                if (name.isNotEmpty()) onSelect(folders.firstOrNull { it.title == name }?.let { FolderRef(it.id, it.title) } ?: FolderRef.named(name))
            },
            onDismiss = { naming = null },
        )
    }
}

/** 一行文字输入的对话框（文件夹名、账号名）。 */
@Composable
fun TextPrompt(title: String, value: String, onChange: (String) -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = Runnel.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = Runnel.type.body.copy(color = colors.label),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConfirm() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("prompt_field"),
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("好", color = colors.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消", color = colors.icon) } },
        containerColor = colors.cell,
    )
}
