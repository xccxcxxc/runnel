package io.github.xccxcxxc.runnel.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.xccxcxxc.runnel.LocalAccount
import io.github.xccxcxxc.runnel.data.db.FeedEntity
import io.github.xccxcxxc.runnel.data.db.LocalFeedState
import io.github.xccxcxxc.runnel.sync.FolderRef
import io.github.xccxcxxc.runnel.ui.components.BarTextButton
import io.github.xccxcxxc.runnel.ui.components.GroupedRow
import io.github.xccxcxxc.runnel.ui.components.GroupedSection
import io.github.xccxcxxc.runnel.ui.components.RowDivider
import io.github.xccxcxxc.runnel.ui.components.SheetPage
import io.github.xccxcxxc.runnel.ui.relativeTime
import io.github.xccxcxxc.runnel.ui.theme.Runnel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 编辑订阅源（首页长按订阅源）：改名、换文件夹、取消订阅；本机账号还显示上次抓取的结果。 */
@Composable
fun EditFeedSheet(feedId: String, onDone: () -> Unit) {
    val session = LocalAccount.current
    val colors = Runnel.colors
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val folders by session.repo.folders.collectAsStateWithLifecycle(emptyList())
    val loaded by produceState<Pair<FeedEntity, LocalFeedState?>?>(null, feedId) {
        value = withContext(Dispatchers.IO) {
            session.db.feedDao().feed(feedId)?.let { it to session.db.localDao().state(feedId) }
        }
    }
    var title by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf<FolderRef?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmUnsubscribe by remember { mutableStateOf(false) }
    val feed = loaded?.first
    LaunchedEffect(feed) {
        if (feed == null) return@LaunchedEffect
        title = feed.title
        folder = feed.folderId?.let { id -> FolderRef(id, folders.firstOrNull { it.id == id }?.title ?: id.substringAfterLast('/')) }
    }

    fun close() {
        focusManager.clearFocus()
        onDone()
    }

    fun run(block: suspend (FeedEntity) -> Unit) {
        val f = feed ?: return
        if (busy) return
        busy = true
        error = null
        scope.launch {
            runCatching { block(f) }
                .onSuccess { close() }
                .onFailure { error = it.message ?: it.javaClass.simpleName }
            busy = false
        }
    }

    SheetPage(
        title = "编辑订阅源",
        leading = { BarTextButton("取消", color = colors.accent, onClick = ::close) },
        trailing = {
            if (busy) {
                CircularProgressIndicator(Modifier.padding(horizontal = 18.dp).size(20.dp), color = colors.accent, strokeWidth = 2.dp)
            } else {
                BarTextButton("完成", color = colors.accent, style = Runnel.type.headline, enabled = feed != null && title.isNotBlank()) {
                    run { session.sync.editFeed(it, title.trim(), folder) }
                }
            }
        },
    ) {
        if (feed == null) return@SheetPage
        item(key = "info") {
            GroupedSection(footer = loaded?.second?.let(::fetchStatus)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        BasicTextField(
                            value = title,
                            onValueChange = { title = it },
                            enabled = !busy,
                            singleLine = true,
                            textStyle = Runnel.type.body.copy(color = colors.label),
                            cursorBrush = SolidColor(colors.accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            modifier = Modifier.fillMaxWidth().testTag("edit_feed_title"),
                        )
                    }
                }
                feed.feedUrl?.let {
                    RowDivider()
                    GroupedRow("地址", value = it, valueColor = colors.tertiaryLabel)
                }
            }
            error?.let {
                Text(it, style = Runnel.type.footnote, color = colors.destructive, modifier = Modifier.padding(horizontal = 32.dp, vertical = 10.dp))
            }
        }
        item(key = "folders") { FolderPicker(folders, folder) { folder = it } }
        item(key = "unsubscribe") {
            GroupedSection {
                GroupedRow("取消订阅…", centered = true, titleColor = colors.destructive, onClick = { confirmUnsubscribe = true })
            }
        }
    }

    if (confirmUnsubscribe) {
        AlertDialog(
            onDismissRequest = { confirmUnsubscribe = false },
            title = { Text("取消订阅「${feed?.title.orEmpty()}」？") },
            text = { Text("这个订阅源的文章（包括星标）会从这个账号里删除。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnsubscribe = false
                    run { session.sync.unsubscribe(it) }
                }) { Text("取消订阅", color = colors.destructive) }
            },
            dismissButton = { TextButton(onClick = { confirmUnsubscribe = false }) { Text("不取消", color = colors.icon) } },
            containerColor = colors.cell,
        )
    }
}

/** 本机账号：上次抓取的时间和失败原因。 */
private fun fetchStatus(state: LocalFeedState): String = when {
    state.error != null -> "上次更新失败（${relativeTime(state.checkedAt)}）：${state.error}"
    state.checkedAt > 0 -> "上次更新：${relativeTime(state.checkedAt)}"
    else -> "还没有更新过"
}
