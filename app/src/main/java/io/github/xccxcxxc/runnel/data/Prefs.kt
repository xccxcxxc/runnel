package io.github.xccxcxxc.runnel.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** 点链接怎么打开：系统默认浏览器 / 应用内的浏览器标签页（Custom Tabs）。 */
enum class OpenLinks { DEFAULT_BROWSER, IN_APP }

/** 文章列表的分组方式。 */
enum class GroupBy { DATE, FEED, NONE }

/**
 * 旧版的全局“阅读视图”开关：READABILITY = 所有订阅源都自动用 Reader View（全文）打开。
 * 新版改为按订阅源选（[Settings.readerViewFeeds]），在 Reader View 页改动后写回 DEFAULT。
 */
enum class ReaderView { DEFAULT, READABILITY }

/** 同步时机：手动 / 启动时 / 后台刷新。 */
enum class SyncMode { MANUAL, ON_START, BACKGROUND }

/** 首页订阅源排序。 */
enum class SortSubs { ALPHA, FEEDS_FIRST, FOLDERS_FIRST, UNREAD }

/** 列表行侧滑动作。 */
enum class SwipeKind { NONE, TOGGLE_READ, TOGGLE_STAR, OPEN_BROWSER, SHARE, ACTION_SHEET }

/** 列表拉到底继续上拉。 */
enum class PullBottom { NONE, NEXT_FEED, MARK_ALL_READ }

/** 动画速度。 */
enum class AnimSpeed(val factor: Float) { SLOW(0.7f), DEFAULT(1f), FAST(1.5f), FASTER(2.2f) }

enum class ReaderFont { DEFAULT, SERIF, MONO }

enum class AppFont { SANS, SERIF }

/** 列表缩略图大小。 */
enum class ImagePreview { NONE, SMALL, MEDIUM, LARGE }

@Immutable
data class Settings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    // 阅读
    val autoHideBars: Boolean = true,
    val coloredLinks: Boolean = false,
    val highContrast: Boolean = false,
    val readerFont: ReaderFont = ReaderFont.DEFAULT,
    val useDefaultFontSize: Boolean = false,
    val readerFontSize: Int = 20,
    val readerLineSpacing: Float = 1.8f,
    val titleSystemFont: Boolean = false,
    val titleScale: Float = 1.5f,
    val titleBold: Boolean = true,
    val titleUppercase: Boolean = false,
    val titleCenter: Boolean = false,
    // 手势
    val swipeRight: SwipeKind = SwipeKind.TOGGLE_READ,
    val swipeLeft: SwipeKind = SwipeKind.TOGGLE_STAR,
    /** 列表拉到底继续上拉松手后做什么（默认全部标为已读，和之前的行为一致）。 */
    val pullBottom: PullBottom = PullBottom.MARK_ALL_READ,
    /** 阅读页拉动切换上一篇/下一篇。 */
    val pullToSwitch: Boolean = true,
    // 动画
    val animSpeed: AnimSpeed = AnimSpeed.DEFAULT,
    val animArticleSwitch: Boolean = true,
    val animListLoad: Boolean = true,
    // 通用
    val openLinks: OpenLinks = OpenLinks.DEFAULT_BROWSER,
    val oldestFirstUnread: Boolean = true,
    val groupBy: GroupBy = GroupBy.DATE,
    val autoNextFeed: Boolean = false,
    /** 列表页“全部标为已读”：先弹窗确认。 */
    val markAllAsk: Boolean = false,
    /** 全部标为已读后打开下一个有内容的订阅源/文件夹，而不是退回上一级。 */
    val markAllOpenNext: Boolean = false,
    /** “全部标为已读”按钮放在底栏左边（默认右边，搜索在另一边）。 */
    val markAllButtonLeft: Boolean = false,
    // 外观
    val grayFavicons: Boolean = false,
    val appFont: AppFont = AppFont.SANS,
    val useSystemFontSize: Boolean = true,
    /** App 字号档位 0..4（XS S M L XL），“跟随系统字号”关闭时生效。 */
    val appFontStep: Int = 2,
    val countStarred: Boolean = true,
    val countUnread: Boolean = true,
    val countAll: Boolean = true,
    val imagePreview: ImagePreview = ImagePreview.MEDIUM,
    val showFavicons: Boolean = true,
    val textPreview: Boolean = true,
    // 手感
    val iosFling: Boolean = true,
    val maxRefreshRate: Boolean = true,
)

/**
 * 账号和设置都放 SharedPreferences（量小，读一次进内存），对外暴露 StateFlow。
 * 账号列表单独放 `account` 文件，不进系统备份（`res/xml/backup_rules.xml`、`data_extraction_rules.xml`）；
 * 密码只在登录时用一次、不保存，服务器发的 token 用 [SecretBox]（Android Keystore）加密后保存。
 * 每个账号的设置和同步进度在各自的 [AccountPrefs] 文件里，这里的 [settings] 是全 App 共用的。
 *
 * `account` 文件里：`ids` = 账号 id（逗号分隔，按添加顺序），`last` = 上次打开的账号，
 * 每个账号的字段是 `<id>.type` / `.base` / `.user` / `.desc` / `.db` / `.token`。
 */
class Prefs(private val context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("runnel", Context.MODE_PRIVATE)
    private val acc: SharedPreferences = context.getSharedPreferences(ACCOUNT_FILE, Context.MODE_PRIVATE)

    /** 解密后的 token（按账号 id），第一次 [authToken] 时填上。 */
    private val cachedTokens = HashMap<String, String>()
    private val accountPrefs = HashMap<String, AccountPrefs>()

    /** 这次启动刚从单账号版本升级上来（旧的后台同步任务要换掉）。 */
    var upgradedToMultiAccount = false
        private set

    init {
        migrateLegacyAccount()
        migrateToMultiAccount()
    }

    private val _accounts = MutableStateFlow(readAccounts())
    val accounts: StateFlow<List<Account>> = _accounts.asStateFlow()

    private val _settings = MutableStateFlow(readSettings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    /** 上次打开的账号（启动时直接进它的首页）。 */
    var lastAccountId: String?
        get() = acc.getString("last", null)
        set(value) = acc.edit { putString("last", value) }

    fun account(id: String): Account? = _accounts.value.firstOrNull { it.id == id }

    /** 这个账号的设置和同步进度。 */
    fun accountPrefs(id: String): AccountPrefs = synchronized(accountPrefs) {
        accountPrefs.getOrPut(id) { AccountPrefs(context.getSharedPreferences(AccountPrefs.fileName(id), Context.MODE_PRIVATE)) }
    }

    /** 新加一个账号（登录成功后，带服务器发的 token），返回它。 */
    fun addAccount(type: AccountType, apiBase: String, username: String, authToken: String = ""): Account {
        val id = newId()
        val account = Account(id, type, apiBase, username, dbName = "account-$id.db")
        val encrypted = if (authToken.isEmpty()) "" else SecretBox.encrypt(authToken)
        val ids = _accounts.value.map { it.id } + id
        acc.edit(commit = true) {
            putString("$id.type", type.name)
            putString("$id.base", apiBase)
            putString("$id.user", username)
            putString("$id.desc", "")
            putString("$id.db", account.dbName)
            putString("$id.token", encrypted)
            putString("ids", ids.joinToString(","))
            putString("last", id)
        }
        synchronized(cachedTokens) { cachedTokens[id] = authToken }
        _accounts.value = _accounts.value + account
        return account
    }

    /** 删掉账号记录和它的设置文件（数据库由调用方先关闭、删除）。 */
    fun removeAccount(id: String) {
        val ids = _accounts.value.map { it.id } - id
        acc.edit(commit = true) {
            listOf("type", "base", "user", "desc", "db", "token").forEach { remove("$id.$it") }
            putString("ids", ids.joinToString(","))
            if (acc.getString("last", null) == id) remove("last")
        }
        synchronized(cachedTokens) { cachedTokens.remove(id) }
        synchronized(accountPrefs) { accountPrefs.remove(id) }
        context.deleteSharedPreferences(AccountPrefs.fileName(id))
        _accounts.value = _accounts.value.filter { it.id != id }
    }

    /**
     * 账号的 token，第一次用时才解密（不放在启动路径上）。
     * 解不开（例如系统备份恢复到了新设备）返回空串，服务器会报认证失败，移除账号后重新登录即可。
     */
    fun authToken(id: String): String {
        synchronized(cachedTokens) { cachedTokens[id]?.let { return it } }
        val token = SecretBox.decrypt(acc.getString("$id.token", "") ?: "") ?: ""
        synchronized(cachedTokens) { cachedTokens[id] = token }
        return token
    }

    fun setAccountDescription(id: String, text: String) {
        acc.edit { putString("$id.desc", text.trim()) }
        _accounts.value = _accounts.value.map { if (it.id == id) it.copy(description = text.trim()) else it }
    }

    /**
     * 旧版把账号（含明文密码和 token）放在 `runnel` 文件里：搬到 `account` 文件、token 加密，
     * 删掉明文密码和 token。只在升级后第一次启动时做一次。
     */
    private fun migrateLegacyAccount() {
        if (!sp.contains("acc_type")) return
        val token = sp.getString("acc_token", "") ?: ""
        acc.edit(commit = true) {
            putString("type", sp.getString("acc_type", null))
            putString("base", sp.getString("acc_base", "") ?: "")
            putString("user", sp.getString("acc_user", "") ?: "")
            putString("desc", sp.getString("acc_desc", "") ?: "")
            // Keystore 出错也不能让启动崩掉：token 留空，同步会报认证失败，重新登录即可
            putString("token", runCatching { SecretBox.encrypt(token) }.getOrDefault(""))
        }
        sp.edit(commit = true) {
            listOf("acc_type", "acc_base", "acc_user", "acc_pass", "acc_token", "acc_desc").forEach { remove(it) }
        }
    }

    /**
     * 单账号版本（`account` 文件里直接是 type/base/user/desc/token）升级成多账号：
     * 这个账号拿一个 id，数据库沿用 runnel.db（不搬文件），token 原样保留（已经加密）；
     * 原来放在全局设置里的账号设置（同步方式、保留已读、Reader View、离线图片……）和同步进度搬进它自己的设置文件。
     */
    private fun migrateToMultiAccount() {
        if (acc.contains("ids")) return
        val type = acc.getString("type", null)?.let { runCatching { AccountType.valueOf(it) }.getOrNull() }
        if (type == null) {
            acc.edit(commit = true) { putString("ids", "") }
            return
        }
        val id = newId()
        // 先写账号设置，再写账号记录：中途被杀的话下次启动重新迁移，不会出现有账号没设置
        val target = context.getSharedPreferences(AccountPrefs.fileName(id), Context.MODE_PRIVATE)
        AccountPrefs.write(target, AccountPrefs.read(sp), commit = true)
        target.edit(commit = true) {
            putLong("lastSyncAt", sp.getLong("lastSyncAt", 0L))
            putLong("lastSyncServerSec", sp.getLong("lastSyncServerSec", 0L))
        }
        acc.edit(commit = true) {
            putString("$id.type", type.name)
            putString("$id.base", acc.getString("base", "") ?: "")
            putString("$id.user", acc.getString("user", "") ?: "")
            putString("$id.desc", acc.getString("desc", "") ?: "")
            putString("$id.db", LEGACY_DB)
            putString("$id.token", acc.getString("token", "") ?: "")
            listOf("type", "base", "user", "desc", "token").forEach { remove(it) }
            putString("ids", id)
            putString("last", id)
        }
        upgradedToMultiAccount = true
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        val s = transform(_settings.value)
        sp.edit {
            putString("theme", s.theme.name)
            putBoolean("autoHideBars", s.autoHideBars)
            putBoolean("coloredLinks", s.coloredLinks)
            putBoolean("highContrast", s.highContrast)
            putString("readerFont", s.readerFont.name)
            putBoolean("useDefaultFontSize", s.useDefaultFontSize)
            putInt("readerFontSize", s.readerFontSize)
            putFloat("readerLineSpacing", s.readerLineSpacing)
            putBoolean("titleSystemFont", s.titleSystemFont)
            putFloat("titleScale", s.titleScale)
            putBoolean("titleBold", s.titleBold)
            putBoolean("titleUppercase", s.titleUppercase)
            putBoolean("titleCenter", s.titleCenter)
            putString("openLinks", s.openLinks.name)
            putBoolean("oldestFirstUnread", s.oldestFirstUnread)
            putString("groupBy", s.groupBy.name)
            putBoolean("autoNextFeed", s.autoNextFeed)
            putBoolean("markAllAsk", s.markAllAsk)
            putBoolean("markAllOpenNext", s.markAllOpenNext)
            putBoolean("markAllButtonLeft", s.markAllButtonLeft)
            putBoolean("grayFavicons", s.grayFavicons)
            putString("appFont", s.appFont.name)
            putBoolean("useSystemFontSize", s.useSystemFontSize)
            putInt("appFontStep", s.appFontStep)
            putBoolean("countStarred", s.countStarred)
            putBoolean("countUnread", s.countUnread)
            putBoolean("countAll", s.countAll)
            putString("imagePreview", s.imagePreview.name)
            putBoolean("showFavicons", s.showFavicons)
            putBoolean("textPreview", s.textPreview)
            putBoolean("iosFling", s.iosFling)
            putBoolean("maxRefreshRate", s.maxRefreshRate)
            putString("swipeRight", s.swipeRight.name)
            putString("swipeLeft", s.swipeLeft.name)
            putString("pullBottom", s.pullBottom.name)
            putBoolean("pullToSwitch", s.pullToSwitch)
            putString("animSpeed", s.animSpeed.name)
            putBoolean("animArticleSwitch", s.animArticleSwitch)
            putBoolean("animListLoad", s.animListLoad)
        }
        _settings.value = s
    }

    private fun readAccounts(): List<Account> =
        (acc.getString("ids", "") ?: "").split(',').filter { it.isNotEmpty() }.mapNotNull { id ->
            val type = acc.getString("$id.type", null)?.let { runCatching { AccountType.valueOf(it) }.getOrNull() }
                ?: return@mapNotNull null
            Account(
                id = id,
                type = type,
                apiBase = acc.getString("$id.base", "") ?: "",
                username = acc.getString("$id.user", "") ?: "",
                description = acc.getString("$id.desc", "") ?: "",
                dbName = acc.getString("$id.db", null) ?: "account-$id.db",
            )
        }

    private inline fun <reified E : Enum<E>> enumOf(key: String, default: E): E =
        sp.getString(key, null)?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

    private fun readSettings(): Settings {
        val d = Settings()
        return Settings(
            theme = enumOf("theme", d.theme),
            autoHideBars = sp.getBoolean("autoHideBars", d.autoHideBars),
            coloredLinks = sp.getBoolean("coloredLinks", d.coloredLinks),
            highContrast = sp.getBoolean("highContrast", d.highContrast),
            // 旧版本只有“衬线”开关
            readerFont = if (sp.contains("readerFont")) enumOf("readerFont", d.readerFont)
            else if (sp.getBoolean("readerSerif", false)) ReaderFont.SERIF else d.readerFont,
            useDefaultFontSize = sp.getBoolean("useDefaultFontSize", d.useDefaultFontSize),
            readerFontSize = sp.getInt("readerFontSize", d.readerFontSize),
            readerLineSpacing = sp.getFloat("readerLineSpacing", d.readerLineSpacing),
            titleSystemFont = sp.getBoolean("titleSystemFont", d.titleSystemFont),
            titleScale = sp.getFloat("titleScale", d.titleScale),
            titleBold = sp.getBoolean("titleBold", d.titleBold),
            titleUppercase = sp.getBoolean("titleUppercase", d.titleUppercase),
            titleCenter = sp.getBoolean("titleCenter", d.titleCenter),
            openLinks = enumOf("openLinks", d.openLinks),
            oldestFirstUnread = sp.getBoolean("oldestFirstUnread", d.oldestFirstUnread),
            groupBy = enumOf("groupBy", d.groupBy),
            autoNextFeed = sp.getBoolean("autoNextFeed", d.autoNextFeed),
            markAllAsk = sp.getBoolean("markAllAsk", d.markAllAsk),
            markAllOpenNext = sp.getBoolean("markAllOpenNext", d.markAllOpenNext),
            markAllButtonLeft = sp.getBoolean("markAllButtonLeft", d.markAllButtonLeft),
            grayFavicons = sp.getBoolean("grayFavicons", d.grayFavicons),
            appFont = enumOf("appFont", d.appFont),
            useSystemFontSize = sp.getBoolean("useSystemFontSize", d.useSystemFontSize),
            appFontStep = sp.getInt("appFontStep", d.appFontStep),
            countStarred = sp.getBoolean("countStarred", d.countStarred),
            countUnread = sp.getBoolean("countUnread", d.countUnread),
            countAll = sp.getBoolean("countAll", d.countAll),
            imagePreview = enumOf("imagePreview", d.imagePreview),
            showFavicons = sp.getBoolean("showFavicons", d.showFavicons),
            textPreview = sp.getBoolean("textPreview", d.textPreview),
            iosFling = sp.getBoolean("iosFling", d.iosFling),
            maxRefreshRate = sp.getBoolean("maxRefreshRate", d.maxRefreshRate),
            swipeRight = enumOf("swipeRight", d.swipeRight),
            swipeLeft = enumOf("swipeLeft", d.swipeLeft),
            // 旧版是开关 pullBottomMarkAll（开 = 全部标为已读）
            pullBottom = if (sp.contains("pullBottom")) enumOf("pullBottom", d.pullBottom)
            else if (sp.getBoolean("pullBottomMarkAll", true)) PullBottom.MARK_ALL_READ else PullBottom.NONE,
            pullToSwitch = sp.getBoolean("pullToSwitch", d.pullToSwitch),
            animSpeed = enumOf("animSpeed", d.animSpeed),
            animArticleSwitch = sp.getBoolean("animArticleSwitch", d.animArticleSwitch),
            animListLoad = sp.getBoolean("animListLoad", d.animListLoad),
        )
    }

    companion object {
        /** 账号所在的 SharedPreferences 文件名；备份规则里按 `account.xml` 排除。 */
        const val ACCOUNT_FILE = "account"

        /** 升级到多账号前唯一那个账号的数据库。 */
        const val LEGACY_DB = "runnel.db"

        private fun newId(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(12)
    }
}
