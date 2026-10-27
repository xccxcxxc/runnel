package io.github.xccxcxxc.runnel

import android.content.Context
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xccxcxxc.runnel.data.AccountPrefs
import io.github.xccxcxxc.runnel.data.AccountType
import io.github.xccxcxxc.runnel.data.Prefs
import io.github.xccxcxxc.runnel.data.SecretBox
import io.github.xccxcxxc.runnel.data.SortSubs
import io.github.xccxcxxc.runnel.data.SyncMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 账号凭据和多账号在真机/模拟器上的存法（真实的 Android Keystore，Robolectric 没有）：
 * 磁盘上不留明文密码和 token；重启 App 后还能用；旧版明文账号、单账号版本升级后自动迁移、保持登录，
 * 原来的数据库（runnel.db）和账号设置都归到迁移出来的那个账号。
 * 每次 new 一个 [Prefs] = 模拟一次冷启动（重新从磁盘读）。
 */
@RunWith(AndroidJUnit4::class)
class AccountStorageTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefsDir get() = File(context.applicationInfo.dataDir, "shared_prefs")

    private fun diskText(): String = prefsDir.listFiles().orEmpty().filter { it.isFile }.joinToString("\n") { it.readText() }

    /** 账号文件、各账号的设置文件、全局设置里跟账号有关的键都清掉（App 单例里的账号先走正常的移除）。 */
    private fun wipe() {
        TestAccounts.removeAll()
        context.getSharedPreferences(Prefs.ACCOUNT_FILE, Context.MODE_PRIVATE).edit(commit = true) { clear() }
        prefsDir.listFiles().orEmpty().filter { it.name.startsWith("acct_") }.forEach { context.deleteSharedPreferences(it.name.removeSuffix(".xml")) }
        context.getSharedPreferences("runnel", Context.MODE_PRIVATE).edit(commit = true) {
            listOf("keepDays", "syncMode", "sortSubs", "readerViewFeeds", "lastSyncAt", "lastSyncServerSec").forEach { remove(it) }
        }
    }

    @Before
    fun setUp() = wipe()

    @After
    fun tearDown() = wipe()

    @Test
    fun tokenIsEncryptedOnDiskAndSurvivesRestart() {
        val added = Prefs(context).addAccount(AccountType.FRESHRSS, "https://rss.example.com/api/greader.php", "alice", "SECRET-TOKEN-123")

        assertFalse("磁盘上不能有明文 token", diskText().contains("SECRET-TOKEN-123"))

        val restarted = Prefs(context)
        val account = restarted.accounts.value.single()
        assertEquals(added.id, account.id)
        assertEquals("alice", account.username)
        assertEquals("https://rss.example.com/api/greader.php", account.apiBase)
        assertEquals("account-${added.id}.db", account.dbName)
        assertEquals("SECRET-TOKEN-123", restarted.authToken(account.id))
        assertEquals(added.id, restarted.lastAccountId)
    }

    /** 1.12 及以前的单账号存法：account.xml 里直接是 type/base/user/desc/token，账号设置在全局 runnel.xml 里。 */
    @Test
    fun singleAccountVersionIsMigrated() {
        context.getSharedPreferences(Prefs.ACCOUNT_FILE, Context.MODE_PRIVATE).edit(commit = true) {
            putString("type", AccountType.FRESHRSS.name)
            putString("base", "https://rss.example.com/api/greader.php")
            putString("user", "alice")
            putString("desc", "我的")
            putString("token", SecretBox.encrypt("TOKEN-1"))
        }
        context.getSharedPreferences("runnel", Context.MODE_PRIVATE).edit(commit = true) {
            putInt("keepDays", 7)
            putString("syncMode", SyncMode.MANUAL.name)
            putString("sortSubs", SortSubs.ALPHA.name)
            putStringSet("readerViewFeeds", setOf("feed/3"))
            putLong("lastSyncServerSec", 1_700_000_000L)
        }

        val upgraded = Prefs(context) // 升级后第一次启动
        assertTrue(upgraded.upgradedToMultiAccount)

        val restarted = Prefs(context)
        assertFalse(restarted.upgradedToMultiAccount)
        val account = restarted.accounts.value.single()
        assertEquals(AccountType.FRESHRSS, account.type)
        assertEquals("alice", account.username)
        assertEquals("我的", account.description)
        assertEquals("原来的数据库原样沿用", Prefs.LEGACY_DB, account.dbName)
        assertEquals("TOKEN-1", restarted.authToken(account.id))
        assertEquals(account.id, restarted.lastAccountId)
        val ap = restarted.accountPrefs(account.id)
        assertEquals(7, ap.settings.value.keepDays)
        assertEquals(SyncMode.MANUAL, ap.settings.value.syncMode)
        assertEquals(SortSubs.ALPHA, ap.settings.value.sortSubs)
        assertEquals(setOf("feed/3"), ap.settings.value.readerViewFeeds)
        assertEquals(1_700_000_000L, ap.lastSyncServerSec)
    }

    @Test
    fun legacyPlaintextAccountIsMigratedAndStaysLoggedIn() {
        // 更早的存法：账号、明文密码、明文 token 都在 runnel.xml 里
        context.getSharedPreferences("runnel", Context.MODE_PRIVATE).edit(commit = true) {
            putString("acc_type", AccountType.MINIFLUX.name)
            putString("acc_base", "http://192.168.1.10:8080")
            putString("acc_user", "bob")
            putString("acc_pass", "OLD-PASSWORD-456")
            putString("acc_token", "OLD-TOKEN-789")
            putString("acc_desc", "家里")
        }

        Prefs(context) // 升级后第一次启动

        val text = diskText()
        assertFalse("旧的明文密码要删掉", text.contains("OLD-PASSWORD-456"))
        assertFalse("旧的明文 token 要删掉", text.contains("OLD-TOKEN-789"))

        val restarted = Prefs(context) // 再启动一次：从迁移后的文件读
        val account = restarted.accounts.value.single()
        assertEquals(AccountType.MINIFLUX, account.type)
        assertEquals("bob", account.username)
        assertEquals("家里", account.description)
        assertEquals(Prefs.LEGACY_DB, account.dbName)
        assertEquals("OLD-TOKEN-789", restarted.authToken(account.id))
    }

    @Test
    fun accountsKeepSeparateSettingsAndRemoveCleanly() {
        val prefs = Prefs(context)
        val a = prefs.addAccount(AccountType.FRESHRSS, "https://a.example/api/greader.php", "alice", "TOKEN-A")
        val b = prefs.addAccount(AccountType.LOCAL, "", "")
        prefs.accountPrefs(a.id).update { it.copy(keepDays = 3) }
        prefs.accountPrefs(b.id).update { it.copy(keepDays = -1) }

        val restarted = Prefs(context)
        assertEquals(listOf(a.id, b.id), restarted.accounts.value.map { it.id })
        assertEquals(3, restarted.accountPrefs(a.id).settings.value.keepDays)
        assertEquals(-1, restarted.accountPrefs(b.id).settings.value.keepDays)
        assertEquals("", restarted.authToken(b.id))

        restarted.removeAccount(a.id)
        val again = Prefs(context)
        assertEquals(listOf(b.id), again.accounts.value.map { it.id })
        assertFalse(diskText().contains("alice"))
        assertFalse("设置文件也删掉", File(prefsDir, AccountPrefs.fileName(a.id) + ".xml").exists())
        assertEquals("", again.authToken(a.id))
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        val sealed = SecretBox.encrypt("hello")
        assertTrue(sealed.startsWith("v1:"))
        assertEquals("hello", SecretBox.decrypt(sealed))
        val chars = sealed.toCharArray()
        val i = chars.size / 2 // 改密文中间的一个字符
        chars[i] = if (chars[i] == 'A') 'B' else 'A'
        assertNull(SecretBox.decrypt(String(chars)))
        assertNull(SecretBox.decrypt("plain-text"))
    }
}
