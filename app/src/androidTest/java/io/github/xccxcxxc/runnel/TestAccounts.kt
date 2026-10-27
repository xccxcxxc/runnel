package io.github.xccxcxxc.runnel

import io.github.xccxcxxc.runnel.data.AccountType
import io.github.xccxcxxc.runnel.data.SyncMode

/**
 * 仪器测试的账号准备：走生产的入口（[AppGraph.removeAccount]、[io.github.xccxcxxc.runnel.data.Prefs.addAccount]），
 * 每个测试一个全新的空账号（自己的空数据库），启动 App 时直接进它的首页。
 */
object TestAccounts {
    /** 移除所有账号，再加一个 [type] 账号，同步方式设为“手动”（演示账号启动时的“同步”会加几篇新文章，未读数就不固定了）。 */
    fun fresh(type: AccountType = AccountType.DEMO, apiBase: String = "", token: String = ""): AccountSession {
        val graph = Graph.get()
        removeAll()
        val account = graph.prefs.addAccount(type, apiBase, if (type == AccountType.DEMO) "demo" else "u", token)
        val session = graph.session(account.id)!!
        session.prefs.update { it.copy(syncMode = SyncMode.MANUAL) }
        return session
    }

    fun removeAll() {
        val graph = Graph.get()
        graph.accounts.value.forEach { graph.removeAccount(it.id) }
    }
}
