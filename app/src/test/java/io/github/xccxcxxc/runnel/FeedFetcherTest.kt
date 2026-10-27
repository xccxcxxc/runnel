package io.github.xccxcxxc.runnel

import io.github.xccxcxxc.runnel.sync.FeedException
import io.github.xccxcxxc.runnel.sync.local.FeedFetcher
import io.github.xccxcxxc.runnel.sync.local.FetchResult
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 本机账号抓订阅源：从网页找订阅地址、条件请求（304）、失败原因。用 MockWebServer 当网站。 */
class FeedFetcherTest {
    private lateinit var server: MockWebServer
    private val fetcher = FeedFetcher(OkHttpClient())
    private val rss = """<?xml version="1.0"?><rss version="2.0"><channel><title>站点</title><link>https://ex.com/</link>
        <item><title>一</title><link>https://ex.com/1</link></item></channel></rss>"""

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun serve(routes: Map<String, (RecordedRequest) -> MockResponse>) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                routes[request.url.encodedPath]?.invoke(request) ?: MockResponse(code = 404, body = "nope")
        }
    }

    private fun ok(body: String, type: String) = MockResponse.Builder().addHeader("Content-Type", type).body(body).build()

    @Test
    fun feedUrlDirectly() = runTest {
        serve(mapOf("/feed.xml" to { ok(rss, "application/rss+xml") }))
        val (url, r) = fetcher.discover(server.url("/feed.xml").toString())
        assertEquals(server.url("/feed.xml").toString(), url)
        assertEquals("站点", r.feed.title)
    }

    @Test
    fun homepageWithAlternateLink() = runTest {
        serve(
            mapOf(
                "/" to { ok("""<html><head><link rel="alternate" type="application/atom+xml" href="/posts.atom"></head><body>hi</body></html>""", "text/html; charset=utf-8") },
                "/posts.atom" to { ok("""<feed xmlns="http://www.w3.org/2005/Atom"><title>Atom 站</title><entry><id>1</id><title>a</title></entry></feed>""", "application/atom+xml") },
            ),
        )
        val (url, r) = fetcher.discover(server.url("/").toString())
        assertEquals(server.url("/posts.atom").toString(), url)
        assertEquals("Atom 站", r.feed.title)
    }

    @Test
    fun homepageWithoutLinkTriesCommonPaths() = runTest {
        serve(
            mapOf(
                "/" to { ok("<html><body>没有声明订阅地址</body></html>", "text/html") },
                "/rss.xml" to { ok(rss, "text/xml") },
            ),
        )
        val (url, _) = fetcher.discover(server.url("/").toString())
        assertEquals(server.url("/rss.xml").toString(), url)
    }

    @Test
    fun noFeedFound() = runTest {
        serve(mapOf("/" to { ok("<html><body>普通网页</body></html>", "text/html") }))
        val e = runCatching { fetcher.discover(server.url("/").toString()) }.exceptionOrNull()
        assertTrue(e.toString(), e is FeedException && e.message == "这个地址没有找到订阅源")
    }

    @Test
    fun conditionalGetReturnsNotModified() = runTest {
        serve(
            mapOf(
                "/feed.xml" to { req ->
                    if (req.headers["If-None-Match"] == "\"v1\"") MockResponse(code = 304)
                    else MockResponse.Builder().addHeader("ETag", "\"v1\"").addHeader("Last-Modified", "Mon, 01 Jan 2024 00:00:00 GMT").body(rss).build()
                },
            ),
        )
        val url = server.url("/feed.xml").toString()
        val first = fetcher.fetch(url) as FetchResult.Ok
        assertEquals("\"v1\"", first.etag)
        assertEquals("Mon, 01 Jan 2024 00:00:00 GMT", first.lastModified)
        assertEquals(FetchResult.NotModified, fetcher.fetch(url, first.etag, first.lastModified))
    }

    @Test
    fun httpErrorIsReported() = runTest {
        serve(emptyMap())
        val e = runCatching { fetcher.fetch(server.url("/gone.xml").toString()) }.exceptionOrNull()
        assertEquals("HTTP 404", e?.message)
    }
}
