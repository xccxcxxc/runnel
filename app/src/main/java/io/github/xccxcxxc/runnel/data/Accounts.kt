package io.github.xccxcxxc.runnel.data

import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AccountType(val label: String) {
    FRESHRSS("FreshRSS"),
    MINIFLUX("Miniflux"),
    GREADER("Google Reader API"),
    /** 本机账号：订阅源由 App 自己抓取，不连服务器。 */
    LOCAL("本机"),
    DEMO("演示数据"),
    ;

    /** 同步 Google Reader 兼容服务器（FreshRSS / Miniflux / 其他）。 */
    val isGReader: Boolean get() = this == FRESHRSS || this == MINIFLUX || this == GREADER
}

@Immutable
data class Account(
    /** App 自己生成的账号 id（每个账号的设置文件、数据库、后台任务都按它区分）。 */
    val id: String,
    val type: AccountType,
    /** 已规范化的 GReader API 根地址（不含 /reader/api/0）。本机和演示账号为空。 */
    val apiBase: String,
    val username: String,
    /** 用户给账号起的名字（首页大标题）；空 = 用服务器主机名。 */
    val description: String = "",
    /** 这个账号的数据库文件名。升级到多账号前的那个账号沿用 runnel.db，新账号各用一个。 */
    val dbName: String,
) {
    /** 连着服务器（已读/星标要提交回去）。 */
    val isRemote: Boolean get() = type.isGReader

    val host: String? get() = if (isRemote) runCatching { java.net.URI(apiBase).host }.getOrNull() else null

    /** 账号列表、首页大标题显示的名字。 */
    val displayName: String get() = description.takeIf { it.isNotBlank() } ?: host ?: type.label
}

/** “保留已读文章”能选的值（天；-1 一直保留，0 不保留）。 */
val KEEP_DAYS_OPTIONS = listOf(-1, 0, 1, 2, 3, 7, 14, 30)

/** 设置 → 账号 里的设置：每个账号一份（订阅源 id 只在本账号内有意义）。 */
@Immutable
data class AccountSettings(
    val syncMode: SyncMode = SyncMode.BACKGROUND,
    /** 后台同步间隔（分钟），只在同步方式为“后台刷新”时用。 */
    val syncIntervalMinutes: Int = 60,
    /** 已读文章保留天数：-1 = 一直保留，0 = 不保留（下次同步清掉），其余为天数。 */
    val keepDays: Int = 30,
    val sortSubs: SortSubs = SortSubs.FOLDERS_FIRST,
    val readerView: ReaderView = ReaderView.DEFAULT,
    /** 默认用 Reader View（自动抓全文）打开的订阅源 id。 */
    val readerViewFeeds: Set<String> = emptySet(),
    /** 同步后预先下载正文图片（离线阅读）的订阅源 id（设置 → 账号 → 离线图片）。 */
    val offlineImageFeeds: Set<String> = emptySet(),
    val markReadOnOpen: Boolean = true,
    val markReadOnScroll: Boolean = false,
) {
    /** 这个订阅源的文章是否默认用 Reader View（全文）打开；旧版全局 Readability 等于全选。 */
    fun autoReaderView(feedId: String): Boolean = readerView == ReaderView.READABILITY || feedId in readerViewFeeds
}

/** 一个账号的设置和同步进度，存在 `acct_<id>` 文件里。 */
class AccountPrefs(private val sp: SharedPreferences) {
    private val _settings = MutableStateFlow(read(sp))
    val settings: StateFlow<AccountSettings> = _settings.asStateFlow()

    var lastSyncAt: Long
        get() = sp.getLong("lastSyncAt", 0L)
        set(value) = sp.edit { putLong("lastSyncAt", value) }

    /** 上次同步时服务端时间（秒），增量拉取用 `ot` 参数。 */
    var lastSyncServerSec: Long
        get() = sp.getLong("lastSyncServerSec", 0L)
        set(value) = sp.edit { putLong("lastSyncServerSec", value) }

    fun update(transform: (AccountSettings) -> AccountSettings) {
        val s = transform(_settings.value)
        write(sp, s)
        _settings.value = s
    }

    companion object {
        fun fileName(id: String) = "acct_$id"

        /** 也用来读升级前放在全局 `runnel` 文件里的同名设置（迁移时）。 */
        fun read(sp: SharedPreferences): AccountSettings {
            val d = AccountSettings()
            fun <E : Enum<E>> enumOf(key: String, values: Array<E>, default: E): E =
                sp.getString(key, null)?.let { name -> values.firstOrNull { it.name == name } } ?: default
            return AccountSettings(
                // 旧版本里“后台同步”间隔 0 = 关闭，对应现在的“启动时”
                syncMode = if (sp.contains("syncMode")) enumOf("syncMode", SyncMode.entries.toTypedArray(), d.syncMode)
                else if (sp.getInt("syncIntervalMinutes", d.syncIntervalMinutes) == 0) SyncMode.ON_START else SyncMode.BACKGROUND,
                syncIntervalMinutes = sp.getInt("syncIntervalMinutes", d.syncIntervalMinutes).takeIf { it > 0 } ?: d.syncIntervalMinutes,
                keepDays = sp.getInt("keepDays", d.keepDays).takeIf { it in KEEP_DAYS_OPTIONS } ?: d.keepDays,
                sortSubs = enumOf("sortSubs", SortSubs.entries.toTypedArray(), d.sortSubs),
                readerView = enumOf("readerView", ReaderView.entries.toTypedArray(), d.readerView),
                readerViewFeeds = sp.getStringSet("readerViewFeeds", null)?.toSet() ?: d.readerViewFeeds,
                offlineImageFeeds = sp.getStringSet("offlineImageFeeds", null)?.toSet() ?: d.offlineImageFeeds,
                markReadOnOpen = sp.getBoolean("markReadOnOpen", d.markReadOnOpen),
                markReadOnScroll = sp.getBoolean("markReadOnScroll", d.markReadOnScroll),
            )
        }

        fun write(sp: SharedPreferences, s: AccountSettings, commit: Boolean = false) = sp.edit(commit = commit) {
            putString("syncMode", s.syncMode.name)
            putInt("syncIntervalMinutes", s.syncIntervalMinutes)
            putInt("keepDays", s.keepDays)
            putString("sortSubs", s.sortSubs.name)
            putString("readerView", s.readerView.name)
            putStringSet("readerViewFeeds", s.readerViewFeeds)
            putStringSet("offlineImageFeeds", s.offlineImageFeeds)
            putBoolean("markReadOnOpen", s.markReadOnOpen)
            putBoolean("markReadOnScroll", s.markReadOnScroll)
        }
    }
}
