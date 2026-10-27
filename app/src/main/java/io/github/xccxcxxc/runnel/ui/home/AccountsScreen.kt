package io.github.xccxcxxc.runnel.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.xccxcxxc.runnel.AppGraph
import io.github.xccxcxxc.runnel.Graph
import io.github.xccxcxxc.runnel.data.Account
import io.github.xccxcxxc.runnel.data.AccountType
import io.github.xccxcxxc.runnel.sync.SyncState
import io.github.xccxcxxc.runnel.ui.components.BarButton
import io.github.xccxcxxc.runnel.ui.components.LargeTitleList
import io.github.xccxcxxc.runnel.ui.components.RunnelIcons
import io.github.xccxcxxc.runnel.ui.components.pressHighlight
import io.github.xccxcxxc.runnel.ui.nav.LocalNavEntry
import io.github.xccxcxxc.runnel.ui.theme.Runnel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

@Immutable
data class AccountRow(val account: Account, val unread: Int, val syncing: Boolean)

class AccountsState(graph: AppGraph, scope: CoroutineScope) {
    @OptIn(ExperimentalCoroutinesApi::class)
    val rows: StateFlow<List<AccountRow>> = graph.accounts.flatMapLatest { list ->
        if (list.isEmpty()) return@flatMapLatest flowOf(emptyList())
        val perAccount = list.mapNotNull { a ->
            val s = graph.session(a.id) ?: return@mapNotNull null
            combine(s.db.articleDao().observeUnreadTotal(), s.sync.state, s.account) { unread, sync, account ->
                AccountRow(account, unread, sync is SyncState.Running)
            }
        }
        combine(perAccount) { it.toList() }
    }.stateIn(scope, SharingStarted.Eagerly, graph.accounts.value.map { AccountRow(it, 0, false) })
}

/** 账号列表（根页面，照 Reeder）：每个账号一行，显示未读数；点进去是这个账号的订阅源。 */
@Composable
fun AccountsScreen(onOpen: (String) -> Unit, onSettings: () -> Unit, onAdd: () -> Unit) {
    val graph = Graph.get()
    val entry = LocalNavEntry.current
    val state = entry.retain("accounts") { AccountsState(graph, entry.scope) }
    val rows by state.rows.collectAsStateWithLifecycle()
    val settings by graph.prefs.settings.collectAsStateWithLifecycle()
    LargeTitleList(
        title = "账号",
        listState = rememberLazyListState(),
        listModifier = Modifier.testTag("accounts_list"),
        navigation = { BarButton(RunnelIcons.Settings, "设置", onClick = onSettings) },
        actions = { BarButton(RunnelIcons.Plus, "添加账号", onClick = onAdd) },
        refreshing = rows.any { it.syncing },
        onRefresh = { graph.prefs.accounts.value.forEach { a -> graph.session(a.id)?.sync?.requestSync() } },
    ) {
        item(key = "top", contentType = "spacer") { Spacer(Modifier.height(8.dp)) }
        items(rows, key = { it.account.id }, contentType = { "account" }) { row ->
            AccountRowView(row, showCount = settings.countUnread) { onOpen(row.account.id) }
        }
    }
}

@Composable
private fun AccountRowView(row: AccountRow, showCount: Boolean, onClick: () -> Unit) {
    val colors = Runnel.colors
    val a = row.account
    Row(
        Modifier.fillMaxWidth().pressHighlight(onClick).height(64.dp).padding(horizontal = 20.dp).testTag("account_${a.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(accountIcon(a.type), null, tint = colors.accent, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(a.displayName, style = Runnel.type.headline, color = colors.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(accountSubtitle(a), style = Runnel.type.footnote, color = colors.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (showCount && row.unread > 0) {
            Text(row.unread.toString(), style = Runnel.type.body, color = colors.secondaryLabel, modifier = Modifier.padding(start = 12.dp))
        }
    }
}

fun accountIcon(type: AccountType) = when (type) {
    AccountType.LOCAL -> RunnelIcons.Device
    AccountType.DEMO -> RunnelIcons.Sparkle
    else -> RunnelIcons.Server
}

/** 账号名下面那行小字：服务和用户名。 */
fun accountSubtitle(a: Account): String = when {
    a.isRemote -> listOfNotNull(a.type.label, a.username.takeIf(String::isNotBlank)).joinToString(" · ")
    a.type == AccountType.LOCAL -> "订阅源由这台设备抓取"
    else -> "随机生成的文章，用来试用"
}
