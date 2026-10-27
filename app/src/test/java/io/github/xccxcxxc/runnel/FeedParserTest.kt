package io.github.xccxcxxc.runnel

import io.github.xccxcxxc.runnel.sync.local.FeedDates
import io.github.xccxcxxc.runnel.sync.local.FeedParser
import io.github.xccxcxxc.runnel.sync.local.LocalBackend
import io.github.xccxcxxc.runnel.sync.local.NotAFeedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset
import java.time.Instant
import java.time.ZoneOffset

/** 本机账号的订阅源解析：几种格式、真实订阅源里常见的不规范写法、编码、日期。 */
class FeedParserTest {
    private fun parse(xml: String, url: String = "https://ex.com/feed.xml", charset: Charset? = null) =
        FeedParser.parse(xml.toByteArray(charset ?: Charsets.UTF_8), charset, url)

    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun rss2() {
        val feed = parse(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:content="http://purl.org/rss/1.0/modules/content/" xmlns:dc="http://purl.org/dc/elements/1.1/"
                 xmlns:atom="http://www.w3.org/2005/Atom" xmlns:media="http://search.yahoo.com/mrss/">
              <channel>
                <title>示例 &amp; 博客</title>
                <atom:link href="https://ex.com/feed.xml" rel="self" type="application/rss+xml"/>
                <link>https://ex.com/</link>
                <item>
                  <title>第一篇 &lt;新&gt;</title>
                  <link>https://ex.com/p/1</link>
                  <guid isPermaLink="false">post-1</guid>
                  <dc:creator><![CDATA[小明]]></dc:creator>
                  <pubDate>Tue, 02 Jan 2024 03:04:05 +0800</pubDate>
                  <description>摘要</description>
                  <content:encoded><![CDATA[<p>全文 <img src="/img/a.png"></p>]]></content:encoded>
                </item>
                <item>
                  <title>第二篇</title>
                  <guid>https://ex.com/p/2</guid>
                  <pubDate>Mon, 1 Jan 2024 00:00:00 GMT</pubDate>
                  <description>&lt;p&gt;转义过的 &lt;b&gt;HTML&lt;/b&gt;&lt;/p&gt;</description>
                  <enclosure url="https://ex.com/cover.jpg" type="image/jpeg" length="1"/>
                </item>
                <item>
                  <title>没有日期</title>
                  <link>/p/3</link>
                  <media:thumbnail url="https://ex.com/t3.jpg"/>
                </item>
              </channel>
            </rss>
            """.trimIndent(),
        )
        assertEquals("示例 & 博客", feed.title)
        assertEquals("网站地址不是 atom:link 的 self", "https://ex.com/", feed.siteUrl)
        assertEquals(3, feed.items.size)
        val (a, b, c) = feed.items
        assertEquals("第一篇 <新>", a.title)
        assertEquals("https://ex.com/p/1", a.url)
        assertEquals("post-1", a.guid)
        assertEquals("小明", a.author)
        assertEquals(ms("2024-01-01T19:04:05Z"), a.published)
        assertTrue("优先 content:encoded", a.html!!.contains("全文"))
        // 没有 link 时用 isPermaLink 的 guid
        assertEquals("https://ex.com/p/2", b.url)
        assertEquals(ms("2024-01-01T00:00:00Z"), b.published)
        assertEquals("<p>转义过的 <b>HTML</b></p>", b.html)
        assertEquals("https://ex.com/cover.jpg", b.imageUrl)
        assertEquals("相对链接按网站地址补全", "https://ex.com/p/3", c.url)
        assertNull(c.published)
        assertEquals("https://ex.com/t3.jpg", c.imageUrl)
    }

    /** 不规范但常见：正文里直接嵌着没转义的标签、裸 &、HTML 实体 &nbsp;。 */
    @Test
    fun sloppyRss() {
        val feed = parse(
            """
            <rss version="2.0"><channel><title>A & B</title><link>https://ex.com</link>
            <item><title>T&nbsp;1</title><link>https://ex.com/1?a=1&b=2</link>
            <description><p>段落<br>换行</p></description></item>
            </channel></rss>
            """.trimIndent(),
        )
        assertEquals("A & B", feed.title)
        val item = feed.items.single()
        assertEquals("https://ex.com/1?a=1&b=2", item.url)
        assertTrue(item.title, item.title.startsWith("T") && item.title.endsWith("1"))
        assertTrue(item.html, item.html!!.contains("<p>段落") && item.html!!.contains("换行"))
    }

    @Test
    fun rss1Rdf() {
        val feed = parse(
            """
            <?xml version="1.0"?>
            <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns="http://purl.org/rss/1.0/" xmlns:dc="http://purl.org/dc/elements/1.1/">
              <channel rdf:about="https://ex.com/"><title>RDF 源</title><link>https://ex.com/</link></channel>
              <item rdf:about="https://ex.com/a"><title>A</title><link>https://ex.com/a</link><dc:date>2024-03-04T05:06:07+09:00</dc:date><description>x</description></item>
            </rdf:RDF>
            """.trimIndent(),
        )
        assertEquals("RDF 源", feed.title)
        val item = feed.items.single()
        assertEquals("https://ex.com/a", item.url)
        assertEquals("https://ex.com/a", item.guid)
        assertEquals(ms("2024-03-03T20:06:07Z"), item.published)
    }

    @Test
    fun atom() {
        val feed = parse(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <feed xmlns="http://www.w3.org/2005/Atom" xml:base="https://ex.com/blog/">
              <title type="text">Atom 源</title>
              <link rel="self" href="https://ex.com/atom.xml"/>
              <link href="https://ex.com/blog/"/>
              <author><name>作者甲</name></author>
              <entry>
                <title type="html">&lt;em&gt;强调&lt;/em&gt; 标题</title>
                <id>tag:ex.com,2024:1</id>
                <link rel="alternate" type="text/html" href="posts/1.html"/>
                <link rel="enclosure" type="image/png" href="/i.png"/>
                <published>2024-05-06T07:08:09.123Z</published>
                <updated>2024-05-07T00:00:00Z</updated>
                <summary>短</summary>
                <content type="html">&lt;p&gt;长正文&lt;/p&gt;</content>
              </entry>
              <entry>
                <title>XHTML</title>
                <id>tag:ex.com,2024:2</id>
                <link href="https://ex.com/2"/>
                <updated>2024-05-08T00:00:00+02:00</updated>
                <content type="xhtml"><div xmlns="http://www.w3.org/1999/xhtml"><p>内嵌 <b>XHTML</b></p></div></content>
              </entry>
              <entry>
                <title>纯文本</title>
                <id>3</id>
                <summary>第一段 &lt;不是标签&gt;

            第二段</summary>
              </entry>
            </feed>
            """.trimIndent(),
            url = "https://ex.com/atom.xml",
        )
        assertEquals("Atom 源", feed.title)
        assertEquals("https://ex.com/blog/", feed.siteUrl)
        val (a, b, c) = feed.items
        assertEquals("<em>强调</em> 标题", a.title) // 由 ArticleProcessor.cleanTitle 去标签
        assertEquals("https://ex.com/blog/posts/1.html", a.url)
        assertEquals("作者甲", a.author)
        assertEquals("优先 published", ms("2024-05-06T07:08:09.123Z") / 1000, a.published!! / 1000)
        assertEquals("<p>长正文</p>", a.html)
        assertEquals("https://ex.com/i.png", a.imageUrl)
        assertTrue(b.html, b.html!!.contains("<p>内嵌 <b>XHTML</b></p>"))
        assertEquals(ms("2024-05-07T22:00:00Z"), b.published)
        assertEquals("<p>第一段 &lt;不是标签&gt;</p><p>第二段</p>", c.html)
        assertNull(c.url)
    }

    @Test
    fun jsonFeed() {
        val feed = parse(
            """
            {"version":"https://jsonfeed.org/version/1.1","title":"JSON 源","home_page_url":"https://ex.com/",
             "authors":[{"name":"乙"}],
             "items":[
               {"id":"1","url":"https://ex.com/1","title":"一","content_html":"<p>一</p>","date_published":"2024-01-02T03:04:05-05:00","image":"https://ex.com/1.png"},
               {"id":2,"content_text":"纯文本\n第二行","date_modified":"2024-01-03T00:00:00Z"}
             ]}
            """.trimIndent(),
            url = "https://ex.com/feed.json",
        )
        assertEquals("JSON 源", feed.title)
        assertEquals("https://ex.com/", feed.siteUrl)
        val (a, b) = feed.items
        assertEquals("一", a.title)
        assertEquals("乙", a.author)
        assertEquals(ms("2024-01-02T08:04:05Z"), a.published)
        assertEquals("https://ex.com/1.png", a.imageUrl)
        assertEquals("2", b.guid)
        assertEquals("<p>纯文本<br>第二行</p>", b.html)
    }

    /** 国内老站常见：GBK 编码，HTTP 头里没写 charset，只在 XML 声明里写。 */
    @Test
    fun gbkFromXmlDeclaration() {
        val gbk = Charset.forName("GBK")
        val xml = "<?xml version=\"1.0\" encoding=\"gbk\"?><rss version=\"2.0\"><channel><title>中文标题</title>" +
            "<item><title>文章一</title><link>https://ex.com/1</link></item></channel></rss>"
        val feed = FeedParser.parse(xml.toByteArray(gbk), null, "https://ex.com/rss")
        assertEquals("中文标题", feed.title)
        assertEquals("文章一", feed.items.single().title)
        // HTTP 头写了编码时以它为准
        val viaHeader = FeedParser.parse(xml.replace("gbk", "utf-8").toByteArray(gbk), gbk, "https://ex.com/rss")
        assertEquals("中文标题", viaHeader.title)
    }

    @Test
    fun htmlIsNotAFeed() {
        val e = runCatching { parse("<!doctype html><html><head><title>x</title></head><body>hi</body></html>") }.exceptionOrNull()
        assertTrue(e.toString(), e is NotAFeedException)
        assertTrue(runCatching { parse("""{"hello":1}""") }.exceptionOrNull() is NotAFeedException)
        assertTrue(runCatching { parse("plain text") }.exceptionOrNull() is NotAFeedException)
    }

    @Test
    fun discoversFeedLinksInPage() {
        val doc = org.jsoup.Jsoup.parse(
            """<html><head>
            <link rel="stylesheet" href="/s.css">
            <link rel="alternate" type="application/rss+xml" title="RSS" href="/feed.xml">
            <link rel="Alternate" type="application/atom+xml" href="https://ex.com/atom.xml">
            <link rel="alternate" hreflang="en" href="/en/">
            </head></html>""",
            "https://ex.com/blog/",
        )
        assertEquals(listOf("https://ex.com/feed.xml", "https://ex.com/atom.xml"), FeedParser.discoverLinks(doc))
    }

    @Test
    fun dates() {
        val utc = ZoneOffset.UTC
        assertEquals(ms("2024-01-02T03:04:05Z"), FeedDates.parse("Tue, 02 Jan 2024 03:04:05 GMT", utc))
        assertEquals(ms("2024-01-02T03:04:00Z"), FeedDates.parse("2 Jan 2024 03:04 Z", utc))
        assertEquals(ms("2024-01-02T08:04:05Z"), FeedDates.parse("Tue, 02 Jan 2024 03:04:05 EST", utc))
        assertEquals(ms("2024-01-02T03:04:05Z"), FeedDates.parse("Tuesday, 02-Jan-24 03:04:05 +0000".replace('-', ' '), utc))
        assertEquals(ms("2024-09-02T03:04:05Z"), FeedDates.parse("Mon, 02 Sept 2024 03:04:05 +0000", utc))
        assertEquals(ms("2024-01-02T03:04:05Z"), FeedDates.parse("2024-01-02T03:04:05Z", utc))
        assertEquals(ms("2024-01-01T19:04:05Z"), FeedDates.parse("2024-01-02T03:04:05+08:00", utc))
        assertEquals(ms("2024-01-01T19:04:05Z"), FeedDates.parse("2024-01-02T03:04:05+0800", utc))
        assertEquals(ms("2024-01-02T03:04:05Z"), FeedDates.parse("2024-01-02T03:04:05.123456Z", utc).let { it!! / 1000 * 1000 })
        // 没写时区：按手机所在时区（这里传东八区）
        assertEquals(ms("2024-01-01T19:04:05Z"), FeedDates.parse("2024-01-02 03:04:05", ZoneOffset.ofHours(8)))
        assertEquals(ms("2024-01-02T00:00:00Z"), FeedDates.parse("2024-01-02", utc))
        assertNull(FeedDates.parse("昨天", utc))
        assertNull(FeedDates.parse("", utc))
        assertNull(FeedDates.parse("2024-13-40", utc))
    }

    /** 文章 id：同一订阅源里同一篇（guid / 链接）每次算出来一样；不同订阅源的同一 guid 不一样；总是正数。 */
    @Test
    fun stableArticleIds() {
        val item = parse("<rss><channel><item><guid>g1</guid><title>t</title></item></channel></rss>").items.single()
        val again = parse("<rss><channel><item><guid>g1</guid><title>改过的标题</title></item></channel></rss>").items.single()
        val id = LocalBackend.articleId("feed/a", item)
        assertEquals(id, LocalBackend.articleId("feed/a", again))
        assertNotEquals(id, LocalBackend.articleId("feed/b", item))
        assertTrue(id > 0)
        val noGuid = parse("<rss><channel><item><title>t</title><link>https://ex.com/x</link></item></channel></rss>").items.single()
        assertEquals(LocalBackend.articleId("feed/a", noGuid), LocalBackend.articleId("feed/a", noGuid.copy(title = "别的")))
    }
}
