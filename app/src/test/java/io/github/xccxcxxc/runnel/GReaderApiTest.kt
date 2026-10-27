package io.github.xccxcxxc.runnel

import io.github.xccxcxxc.runnel.data.AccountType
import io.github.xccxcxxc.runnel.sync.greader.GReaderApi
import io.github.xccxcxxc.runnel.sync.greader.GReaderException
import io.github.xccxcxxc.runnel.sync.greader.ItemIds
import io.github.xccxcxxc.runnel.sync.greader.Streams
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class GReaderApiTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun api() = GReaderApi(OkHttpClient(), server.url("/api/greader.php").toString())

    @Test
    fun itemIdFormats() {
        assertEquals(31L, ItemIds.parse("tag:google.com,2005:reader/item/000000000000001f"))
        assertEquals(1700000000123456L, ItemIds.parse("1700000000123456"))
        assertEquals("tag:google.com,2005:reader/item/000000000000001f", ItemIds.toLongForm(31))
        // 最高位为 1 的 64 位 id 也能来回转换
        val big = ItemIds.parse("tag:google.com,2005:reader/item/f000000000000001")
        assertEquals("tag:google.com,2005:reader/item/f000000000000001", ItemIds.toLongForm(big))
    }

    @Test
    fun normalizeBase() {
        assertEquals("https://rss.example.com/api/greader.php", GReaderApi.normalizeBase(AccountType.FRESHRSS, "rss.example.com/"))
        assertEquals("https://rss.example.com/api/greader.php", GReaderApi.normalizeBase(AccountType.FRESHRSS, "https://rss.example.com/api/greader.php/"))
        assertEquals("http://h/i/api/greader.php", GReaderApi.normalizeBase(AccountType.FRESHRSS, "http://h/i/api"))
        assertEquals("https://mf.example.com", GReaderApi.normalizeBase(AccountType.MINIFLUX, "https://mf.example.com/"))
        assertEquals("https://mf.example.com", GReaderApi.normalizeBase(AccountType.MINIFLUX, "https://mf.example.com/reader/api/0"))
    }

    @Test
    fun loginAndContinuation() = runTest {
        server.enqueue(MockResponse(body = "SID=x\nLSID=null\nAuth=TOKEN123\n"))
        server.enqueue(MockResponse(body = """{"itemRefs":[{"id":"3"},{"id":"2"}],"continuation":"c1"}"""))
        server.enqueue(MockResponse(body = """{"itemRefs":[{"id":"1"}]}"""))
        val api = api()
        assertEquals("TOKEN123", api.login("u", "p"))
        val login = server.takeRequest()
        assertEquals("/api/greader.php/accounts/ClientLogin", login.url.encodedPath)

        val ids = api.itemIds(Streams.READING_LIST, exclude = Streams.READ)
        assertEquals(listOf(3L, 2L, 1L), ids)
        val first = server.takeRequest()
        assertEquals("GoogleLogin auth=TOKEN123", first.headers["Authorization"])
        assertEquals(Streams.READ, first.url.queryParameter("xt"))
        assertEquals("c1", server.takeRequest().url.queryParameter("c"))
    }

    @Test
    fun editTagRefetchesExpiredToken() = runTest {
        server.enqueue(MockResponse(body = "SID=x\nAuth=T\n"))
        server.enqueue(MockResponse(body = "wt1"))
        server.enqueue(MockResponse(code = 401, body = "Unauthorized"))
        server.enqueue(MockResponse(body = "wt2"))
        server.enqueue(MockResponse(body = "OK"))
        val api = api()
        api.login("u", "p")
        api.editTag(listOf(31L), add = Streams.READ)
        server.takeRequest() // login
        server.takeRequest() // token
        server.takeRequest() // 401
        server.takeRequest() // token again
        val ok = server.takeRequest()
        val body = ok.body!!.utf8()
        assert(body.contains("T=wt2")) { body }
        assert(body.contains("i=tag%3Agoogle.com%2C2005%3Areader%2Fitem%2F000000000000001f")) { body }
    }

    @Test
    fun quickAddThenMoveToFolder() = runTest {
        server.enqueue(MockResponse(body = "wt"))
        server.enqueue(MockResponse(body = """{"numResults":1,"query":"https://a.example/feed","streamId":"feed/42","streamName":"A"}"""))
        server.enqueue(MockResponse(body = "OK"))
        server.enqueue(MockResponse(body = """{"numResults":0}"""))
        val api = api()
        assertEquals("feed/42", api.quickAdd("https://a.example/"))
        api.editSubscription("feed/42", addLabel = "user/-/label/Tech")
        assertEquals(null, api.quickAdd("https://nothing.example/"))
        server.takeRequest() // token
        val add = server.takeRequest()
        assertEquals("/api/greader.php/reader/api/0/subscription/quickadd", add.url.encodedPath)
        assert(add.body!!.utf8().contains("quickadd=https%3A%2F%2Fa.example%2F")) { add.body!!.utf8() }
        val edit = server.takeRequest().body!!.utf8()
        assert(edit.contains("ac=edit") && edit.contains("s=feed%2F42") && edit.contains("a=user%2F-%2Flabel%2FTech")) { edit }
        assert(!edit.contains("t=") && !edit.contains("r=")) { edit }
    }

    @Test
    fun renameMoveAndUnsubscribe() = runTest {
        server.enqueue(MockResponse(body = "wt"))
        server.enqueue(MockResponse(body = "OK"))
        server.enqueue(MockResponse(body = "OK"))
        val api = api()
        api.editSubscription("feed/7", title = "新名字", addLabel = "user/-/label/B", removeLabel = "user/-/label/A")
        api.unsubscribe("feed/7")
        server.takeRequest() // token
        val edit = server.takeRequest()
        assertEquals("/api/greader.php/reader/api/0/subscription/edit", edit.url.encodedPath)
        val e = edit.body!!.utf8()
        assert(e.contains("ac=edit") && e.contains("s=feed%2F7") && e.contains("t=%E6%96%B0%E5%90%8D%E5%AD%97")) { e }
        assert(e.contains("a=user%2F-%2Flabel%2FB") && e.contains("r=user%2F-%2Flabel%2FA") && e.contains("T=wt")) { e }
        val un = server.takeRequest().body!!.utf8()
        assert(un.contains("ac=unsubscribe") && un.contains("s=feed%2F7")) { un }
    }

    @Test
    fun quickAddFetchFailureShowsServerReason() = runTest {
        server.enqueue(MockResponse(body = "wt"))
        server.enqueue(MockResponse(code = 500, body = """{"error_message":"fetcher: Get \"https://x.invalid/feed\": no such host"}"""))
        val e = runCatching { api().quickAdd("https://x.invalid/feed") }.exceptionOrNull() as GReaderException
        assertEquals(500, e.code)
        assertEquals("没有找到订阅源：fetcher: Get \"https://x.invalid/feed\": no such host", e.message)
    }

    @Test
    fun rebaseIcons() {
        val r = io.github.xccxcxxc.runnel.sync.greader.GReaderBackend.Companion::rebaseIcon
        assertEquals("https://mflux.example.xyz/feed-icon/abc", r("http://localhost/feed-icon/abc", "https://mflux.example.xyz"))
        assertEquals("https://h.example/rss/feed-icon/abc", r("http://localhost:8080/feed-icon/abc", "https://h.example/rss/"))
        assertEquals("https://f.example/f.php?h=1", r("http://localhost/f.php?h=1", "https://f.example/api/greader.php"))
        assertEquals("https://cdn.other/x.png", r("https://cdn.other/x.png", "https://f.example"))
        assertEquals(null, r("", "https://f.example"))
    }
}
