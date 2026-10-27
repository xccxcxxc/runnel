package io.github.xccxcxxc.runnel.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.xccxcxxc.runnel.AccountSession
import io.github.xccxcxxc.runnel.LocalAccount
import io.github.xccxcxxc.runnel.Graph
import io.github.xccxcxxc.runnel.data.db.ArticleListItem
import io.github.xccxcxxc.runnel.ui.components.BarHeight
import io.github.xccxcxxc.runnel.ui.components.BarTextButton
import io.github.xccxcxxc.runnel.ui.components.RunnelIcons
import io.github.xccxcxxc.runnel.ui.list.ArticleRow
import io.github.xccxcxxc.runnel.ui.nav.LocalNavEntry
import io.github.xccxcxxc.runnel.ui.nav.ReaderSession
import io.github.xccxcxxc.runnel.ui.theme.Runnel
import io.github.xccxcxxc.runnel.ui.theme.listFling
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class SearchState(account: AccountSession, scope: CoroutineScope) {
    val query = MutableStateFlow("")
    var results by mutableStateOf<List<ArticleListItem>?>(null)
        private set

    init {
        scope.launch {
            query.debounce(200).distinctUntilChanged().collect { q ->
                results = if (q.isBlank()) null else account.repo.search(q)
            }
        }
    }
}

@Composable
fun SearchScreen(onBack: () -> Unit, onOpenArticle: (ReaderSession) -> Unit) {
    val account = LocalAccount.current
    val entry = LocalNavEntry.current
    val state = entry.retain("search") { SearchState(account, entry.scope) }
    val colors = Runnel.colors
    var text by remember { mutableStateOf(state.query.value) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val flags = account.repo.flags
    val results = state.results

    LaunchedEffect(Unit) { if (text.isEmpty()) focus.requestFocus() }

    Box(Modifier.fillMaxSize().background(colors.background)) {
        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + BarHeight + 8.dp
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = top, bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp),
            flingBehavior = listFling(),
        ) {
            if (results != null && results.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "没有找到“${state.query.value}”",
                        style = Runnel.type.subhead,
                        color = colors.secondaryLabel,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
                    )
                }
            }
            if (results != null) {
                items(results, key = { it.id }, contentType = { if (it.thumbUrl != null) "row_thumb" else "row" }) { item ->
                    ArticleRow(
                        item,
                        read = flags.read[item.id] ?: item.isRead,
                        starred = flags.starred[item.id] ?: item.isStarred,
                        showFeed = true,
                        clock = false,
                    ) {
                        keyboard?.hide()
                        onOpenArticle(ReaderSession(results.map { it.id }, results.indexOf(item)))
                    }
                }
            }
        }
        // 顶部搜索栏
        Row(
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawRect(colors.bar)
                    drawLine(colors.separator, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f)
                }
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(BarHeight + 8.dp)
                .padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.highlight)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(RunnelIcons.Search, null, tint = colors.secondaryLabel, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Box(Modifier.weight(1f)) {
                    if (text.isEmpty()) Text("搜索标题和摘要", style = Runnel.type.body, color = colors.tertiaryLabel)
                    BasicTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            state.query.value = it
                        },
                        singleLine = true,
                        textStyle = Runnel.type.body.copy(color = colors.label),
                        cursorBrush = SolidColor(colors.icon),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
            }
            BarTextButton("取消") {
                keyboard?.hide()
                onBack()
            }
        }
    }
}
