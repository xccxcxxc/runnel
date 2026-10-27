package io.github.xccxcxxc.runnel

import io.github.xccxcxxc.runnel.data.AccountSettings
import io.github.xccxcxxc.runnel.data.ReaderView
import io.github.xccxcxxc.runnel.data.Settings
import io.github.xccxcxxc.runnel.data.db.ReaderArticle
import io.github.xccxcxxc.runnel.ui.reader.ReaderHtml
import io.github.xccxcxxc.runnel.ui.theme.LightColors
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderViewTest {
    @Test
    fun readerViewIsPerFeed() {
        val s = AccountSettings(readerViewFeeds = setOf("feed/1"))
        assertTrue(s.autoReaderView("feed/1"))
        assertFalse(s.autoReaderView("feed/2"))
        assertFalse(AccountSettings().autoReaderView("feed/1"))
    }

    @Test
    fun legacyGlobalReadabilityMeansAllFeeds() {
        assertTrue(AccountSettings(readerView = ReaderView.READABILITY).autoReaderView("anything"))
    }

    @Test
    fun titleLinksToOriginalArticle() {
        val url = "https://ex.com/a?b=1&c=2"
        val a = article(url)
        val html = ReaderHtml.build(a, "<p>x</p>", Settings(), LightColors, 0, 0)
        assertTrue(html, html.contains("""<a class="original" href="runnel://open?u=https%3A%2F%2Fex.com%2Fa%3Fb%3D1%26c%3D2"><h1 class="title">A</h1></a>"""))
        // 没有原文地址：标题不加链接
        val none = ReaderHtml.build(article(null), "<p>x</p>", Settings(), LightColors, 0, 0)
        assertTrue(none.contains("""<header><div class="date">""") && none.contains("""</div><h1 class="title">A</h1>"""))
        assertTrue(!none.contains("runnel://open"))
    }

    private fun article(url: String?) = ReaderArticle(
        id = 1, feedId = "f", feedTitle = "F", title = "A", author = null, url = url,
        publishedAt = 0, readingMinutes = 1, isRead = false, isStarred = false, html = "<p>x</p>", fullHtml = null,
    )

    @Test
    fun headerShowsFeedInAccentAndEscapes() {
        val a = ReaderArticle(
            id = 1, feedId = "f", feedTitle = "LINUX DO <最新>", title = "A & B", author = "hiraly", url = null,
            publishedAt = 0, readingMinutes = 1, isRead = false, isStarred = false, html = "<p>x</p>", fullHtml = null,
        )
        val html = ReaderHtml.build(a, "<p>x</p>", Settings(), LightColors, 0, 0)
        assertTrue(html, html.contains("""<span class="src">LINUX DO &lt;最新&gt;</span> · hiraly"""))
        assertTrue(html.contains("""<h1 class="title">A &amp; B</h1>"""))
        assertTrue(html.contains(".feed .src{color:var(--accent)}"))
    }

    /** 显示时去掉 srcset：WebView 只请求 src，和离线图片预下载的地址一致。 */
    @Test
    fun srcsetIsStrippedForDisplay() {
        val body = """<p><img src="https://ex.com/l.jpg" srcset="https://ex.com/m.jpg 800w, https://ex.com/l.jpg 1600w" alt="x"></p>""" +
            """<picture><source srcset="https://ex.com/a.webp" type="image/webp"><img src="https://ex.com/a.jpg"></picture>"""
        val html = ReaderHtml.build(article(null), body, Settings(), LightColors, 0, 0)
        assertTrue(html, !html.contains("srcset"))
        assertTrue(html.contains("""<img src="https://ex.com/l.jpg" alt="x">"""))
        assertTrue(html.contains("""<source type="image/webp">"""))
    }

    /** 以前同步的文章里微博视频已经被换成“嵌入内容 ↗”链接：显示时换回播放器，不用重新同步；别的链接不动。 */
    @Test
    fun oldEmbedLinksBecomePlayersAgain() {
        val body = """<p><a href="https://h5.video.weibo.com/show/1034:5356719388426257">嵌入内容 ↗</a></p>""" +
            """<p><a href="https://player.bilibili.com/player.html?aid=1&amp;p=1">嵌入内容 ↗</a></p>""" +
            """<p><a href="https://ads.example.com/frame">嵌入内容 ↗</a></p>"""
        val html = ReaderHtml.build(article("https://www.dapenti.com/blog/more.asp?id=1"), body, Settings(), LightColors, 0, 0)
        assertTrue(html, html.contains("""<p><iframe src="https://h5.video.weibo.com/show/1034:5356719388426257" allowfullscreen></iframe></p>"""))
        assertTrue(html, html.contains("""<iframe src="https://player.bilibili.com/player.html?aid=1&amp;p=1" allowfullscreen>"""))
        assertTrue(html, html.contains("""<a href="https://ads.example.com/frame">嵌入内容 ↗</a>"""))
    }
}
