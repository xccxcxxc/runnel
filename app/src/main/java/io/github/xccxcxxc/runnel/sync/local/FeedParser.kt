package io.github.xccxcxxc.runnel.sync.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.charset.Charset
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** 解析出来的订阅源。 */
data class ParsedFeed(
    val title: String,
    /** 网站首页。 */
    val siteUrl: String?,
    val items: List<ParsedItem>,
)

data class ParsedItem(
    /** RSS guid / Atom id / JSON Feed id；没有时用链接。 */
    val guid: String?,
    val url: String?,
    val title: String,
    val author: String?,
    /** 正文 HTML（还没清洗）。 */
    val html: String?,
    /** 发布时间（毫秒）；订阅源没写或写得认不出时为 null。 */
    val published: Long?,
    /** enclosure / media:content 里的图片（正文没有图片时当缩略图）。 */
    val imageUrl: String?,
)

/** 不是订阅源（例如是网页），[FeedFetcher] 会接着在网页里找订阅地址。 */
class NotAFeedException(message: String = "不是订阅源") : Exception(message)

/**
 * RSS 2.0 / 0.9x、RSS 1.0（RDF）、Atom 1.0 / 0.3、JSON Feed 1.x。
 * XML 用 jsoup 的 XML 解析器：容错（真实订阅源里常有没转义的 &、HTML 实体、没闭合的标签），
 * 也会按 `<?xml encoding?>` 识别 GBK 等编码。
 */
object FeedParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** [charset] 是 HTTP 头里的编码（有就以它为准）；[feedUrl] 用来补全相对链接。 */
    fun parse(bytes: ByteArray, charset: Charset?, feedUrl: String): ParsedFeed {
        val start = firstSignificantChar(bytes)
        if (start == '{') return parseJson(String(bytes, charset ?: Charsets.UTF_8), feedUrl)
        if (start != '<') throw NotAFeedException()
        val doc = Jsoup.parse(ByteArrayInputStream(bytes), charset?.name(), feedUrl, Parser.xmlParser())
        val root = doc.children().firstOrNull { it.normalName() != "#declaration" } ?: throw NotAFeedException()
        return when (root.local()) {
            "rss" -> parseRss(root, root.child("channel") ?: throw NotAFeedException(), feedUrl)
            "rdf" -> parseRss(root, root.child("channel") ?: root, feedUrl)
            "feed" -> parseAtom(root, feedUrl)
            else -> throw NotAFeedException()
        }
    }

    private fun parseRss(root: Element, channel: Element, feedUrl: String): ParsedFeed {
        val site = rssLink(channel)?.let { resolve(feedUrl, it) }
        // RSS 1.0 的 item 和 channel 平级，RSS 2.0 在 channel 里面
        val items = (channel.children() + if (channel !== root) root.children() else emptyList())
            .filter { it.local() == "item" }
            .map { item ->
                val link = rssLink(item)?.let { resolve(site ?: feedUrl, it) }
                val guidEl = item.child("guid")
                val guid = guidEl?.wholeText()?.trim()?.takeIf(String::isNotEmpty)
                val permalink = guid?.takeIf { guidEl.attr("isPermaLink") != "false" && (it.startsWith("http://") || it.startsWith("https://")) }
                val url = link ?: permalink
                val html = item.childContent("encoded") ?: item.childContent("description")
                ParsedItem(
                    guid = guid ?: item.attr("rdf:about").takeIf(String::isNotBlank),
                    url = url,
                    title = item.childText("title").orEmpty(),
                    author = item.childText("creator") ?: item.childText("author"),
                    html = html,
                    published = (item.childText("pubDate") ?: item.childText("date") ?: item.childText("published") ?: item.childText("updated"))
                        ?.let(FeedDates::parse),
                    imageUrl = imageOf(item, url ?: feedUrl),
                )
            }
        return ParsedFeed(channel.childText("title").orEmpty(), site, items)
    }

    private fun parseAtom(feed: Element, feedUrl: String): ParsedFeed {
        val base = feed.attr("xml:base").takeIf(String::isNotBlank)?.let { resolve(feedUrl, it) } ?: feedUrl
        val site = alternateLink(feed, base)
        val feedAuthor = feed.child("author")?.childText("name")
        val items = feed.children().filter { it.local() == "entry" }.map { entry ->
            val entryBase = entry.attr("xml:base").takeIf(String::isNotBlank)?.let { resolve(base, it) } ?: base
            val url = alternateLink(entry, entryBase)
            ParsedItem(
                guid = entry.childText("id"),
                url = url,
                title = atomText(entry.child("title")).orEmpty(),
                author = entry.child("author")?.childText("name") ?: feedAuthor,
                html = atomHtml(entry.child("content")) ?: atomHtml(entry.child("summary")),
                published = (entry.childText("published") ?: entry.childText("issued") ?: entry.childText("updated") ?: entry.childText("modified"))
                    ?.let(FeedDates::parse),
                imageUrl = imageOf(entry, url ?: entryBase),
            )
        }
        return ParsedFeed(atomText(feed.child("title")).orEmpty(), site, items)
    }

    private fun parseJson(text: String, feedUrl: String): ParsedFeed {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: throw NotAFeedException()
        if (root.str("version")?.contains("jsonfeed.org") != true && root["items"] !is JsonArray) throw NotAFeedException()
        val site = root.str("home_page_url")?.let { resolve(feedUrl, it) }
        val feedAuthor = authorOf(root)
        val items = (root["items"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { item ->
            val url = (item.str("url") ?: item.str("external_url"))?.let { resolve(site ?: feedUrl, it) }
            val html = item.str("content_html") ?: item.str("content_text")?.let(::textToHtml) ?: item.str("summary")?.let(::textToHtml)
            ParsedItem(
                guid = item["id"]?.let { (it as? JsonPrimitive)?.contentOrNull },
                url = url,
                title = item.str("title").orEmpty(),
                author = authorOf(item) ?: feedAuthor,
                html = html,
                published = (item.str("date_published") ?: item.str("date_modified"))?.let(FeedDates::parse),
                imageUrl = (item.str("image") ?: item.str("banner_image"))?.let { resolve(url ?: feedUrl, it) },
            )
        }
        return ParsedFeed(root.str("title").orEmpty(), site, items)
    }

    private fun authorOf(o: JsonObject): String? =
        ((o["authors"] as? JsonArray)?.firstOrNull() as? JsonObject ?: o["author"] as? JsonObject)?.str("name")

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)

    /** RSS 的 `<link>地址</link>`（不是同名的 `<atom:link href rel="self">`）。 */
    private fun rssLink(el: Element): String? =
        el.children().firstOrNull { it.tagName().equals("link", ignoreCase = true) && it.wholeText().isNotBlank() }?.wholeText()?.trim()

    /** Atom 的 link：rel 缺省或为 alternate 的那个，优先 text/html。 */
    private fun alternateLink(el: Element, base: String): String? {
        val links = el.children().filter { it.local() == "link" && it.attr("href").isNotBlank() }
        val alt = links.filter { it.attr("rel").let { r -> r.isEmpty() || r == "alternate" } }
        val pick = alt.firstOrNull { it.attr("type").let { t -> t.isEmpty() || t.contains("html") } } ?: alt.firstOrNull()
        // Atom 0.3 / 不规范的订阅源：<link>地址</link>
            ?: return el.children().firstOrNull { it.local() == "link" && it.attr("href").isBlank() }?.wholeText()?.trim()
                ?.takeIf(String::isNotEmpty)?.let { resolve(base, it) }
        return resolve(base, pick.attr("href"))
    }

    /** Atom 文本构造：type="html" 是转义过的 HTML，"xhtml" 是内嵌的 div，"text"（缺省）是纯文本。 */
    private fun atomHtml(el: Element?): String? {
        el ?: return null
        val type = el.attr("type").lowercase()
        val html = when {
            type == "xhtml" -> (el.children().firstOrNull { it.local() == "div" } ?: el).html()
            type == "html" || type.contains("html") -> contentOf(el)
            type.isEmpty() || type == "text" -> if (el.children().isNotEmpty()) el.html() else textToHtml(el.wholeText())
            else -> null // 二进制等：不显示
        }
        return html?.trim()?.takeIf(String::isNotEmpty)
    }

    private fun atomText(el: Element?): String? = el?.let { if (it.attr("type") == "xhtml") it.text() else it.wholeText() }?.trim()

    /** RSS 正文：通常是转义过的 HTML 或 CDATA；也有直接嵌着没转义的标签的，那就取里面的 HTML。 */
    private fun contentOf(el: Element): String? =
        (if (el.children().isNotEmpty()) el.html() else el.wholeText()).trim().takeIf(String::isNotEmpty)

    private fun imageOf(item: Element, base: String): String? {
        val candidates = item.children().flatMap { c ->
            when (c.local()) {
                "enclosure" -> listOf(c).filter { it.attr("type").startsWith("image") }.map { it.attr("url") }
                "content" -> if (c.tagName().startsWith("media:") && (c.attr("medium") == "image" || c.attr("type").startsWith("image"))) listOf(c.attr("url")) else emptyList()
                "thumbnail" -> listOf(c.attr("url"))
                "group" -> c.children().filter { it.local() == "content" && (it.attr("medium") == "image" || it.attr("type").startsWith("image")) }.map { it.attr("url") } +
                    c.children().filter { it.local() == "thumbnail" }.map { it.attr("url") }
                "link" -> listOf(c).filter { it.attr("rel") == "enclosure" && it.attr("type").startsWith("image") }.map { it.attr("href") }
                else -> emptyList()
            }
        }
        return candidates.firstOrNull { it.isNotBlank() }?.let { resolve(base, it) }
    }

    /** 标签的本地名（去掉命名空间前缀，小写）。 */
    private fun Element.local(): String = tagName().substringAfter(':').lowercase()

    /** 子元素：先找不带前缀的（`description`），没有再找带前缀的（`media:description`、`dc:date`）。 */
    private fun Element.child(name: String): Element? =
        children().firstOrNull { it.tagName().equals(name, ignoreCase = true) } ?: children().firstOrNull { it.local() == name }

    private fun Element.childText(name: String): String? = child(name)?.let { contentOf(it) }?.let { if ('<' in it) Jsoup.parse(it).text() else it }?.trim()?.takeIf(String::isNotEmpty)

    private fun Element.childContent(name: String): String? = child(name)?.let { contentOf(it) }

    private fun firstSignificantChar(bytes: ByteArray): Char? {
        var i = 0
        // UTF-8 BOM
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) i = 3
        // UTF-16 BOM：交给 jsoup 识别
        if (bytes.size >= 2 && (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() || bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())) return '<'
        while (i < bytes.size) {
            val c = bytes[i].toInt().toChar()
            if (!c.isWhitespace()) return c
            i++
        }
        return null
    }

    private fun textToHtml(text: String): String =
        text.trim().split(Regex("\\n\\s*\\n")).joinToString("") { "<p>" + Parser.unescapeEntities(it, false).let(::escape).replace("\n", "<br>") + "</p>" }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun resolve(base: String, href: String): String {
        val h = href.trim()
        return runCatching { URI(base).resolve(URI(h.replace(" ", "%20"))).toString() }.getOrDefault(h)
    }

    /** 网页里声明的订阅地址（`<link rel="alternate" type="application/rss+xml">` 等），按出现顺序。 */
    fun discoverLinks(doc: Document): List<String> =
        doc.select("link[rel~=(?i)alternate][href]").filter { el ->
            val type = el.attr("type").lowercase()
            type.contains("rss") || type.contains("atom") || type.contains("feed+json") || type == "application/json" || type.endsWith("/xml")
        }.map { it.absUrl("href").ifBlank { it.attr("href") } }.filter { it.startsWith("http") }.distinct()
}

/** 订阅源里的各种日期写法：RFC 822（RSS）、ISO 8601（Atom、JSON Feed），以及国内站点常见的 “2024-01-02 03:04:05”。 */
object FeedDates {
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val zones = mapOf(
        "UT" to 0, "UTC" to 0, "GMT" to 0, "Z" to 0,
        "EST" to -5, "EDT" to -4, "CST" to -6, "CDT" to -5, "MST" to -7, "MDT" to -6, "PST" to -8, "PDT" to -7,
    )
    private val rfc822 = Regex("""(\d{1,2})\s+([A-Za-z]{3})[A-Za-z]*\.?,?\s+(\d{2,4})(?:\s+|,\s*)(\d{1,2}):(\d{2})(?::(\d{2}))?(?:\.\d+)?\s*(.*)""")
    private val iso = Regex("""(\d{4})-(\d{1,2})-(\d{1,2})(?:[T\s]+(\d{1,2}):(\d{2})(?::(\d{2}))?(?:[.,]\d+)?)?\s*(Z|[+-]\d{2}(?::?\d{2})?)?.*""", RegexOption.IGNORE_CASE)

    /** 认不出返回 null。没写时区的按手机所在时区。 */
    fun parse(raw: String, defaultZone: ZoneId = ZoneId.systemDefault()): Long? {
        val s = raw.trim().replace(Regex("\\s+"), " ")
        if (s.isEmpty()) return null
        iso.matchEntire(s)?.let { m ->
            val (y, mo, d, h, mi, sec, z) = m.destructured
            val date = runCatching { LocalDate.of(y.toInt(), mo.toInt(), d.toInt()) }.getOrNull() ?: return null
            val time = if (h.isEmpty()) date.atStartOfDay() else runCatching {
                LocalDateTime.of(date.year, date.month, date.dayOfMonth, h.toInt(), mi.toInt(), sec.ifEmpty { "0" }.toInt())
            }.getOrNull() ?: return null
            val zone = if (z.isEmpty()) defaultZone else offset(z) ?: return null
            return time.atZone(zone).toInstant().toEpochMilli()
        }
        // 去掉星期：“Mon, ” “Tuesday, ”
        val body = s.replace(Regex("^[A-Za-z]+,?\\s+(?=\\d)"), "")
        rfc822.find(body)?.let { m ->
            val (d, monName, y, h, mi, sec, rest) = m.destructured
            val month = months.indexOf(monName.lowercase()) + 1
            if (month == 0) return null
            val year = y.toInt().let { if (y.length == 2) (if (it < 70) 2000 + it else 1900 + it) else it }
            val time = runCatching { LocalDateTime.of(year, month, d.toInt(), h.toInt(), mi.toInt(), sec.ifEmpty { "0" }.toInt()) }.getOrNull() ?: return null
            val zoneText = rest.trim().substringBefore(' ').removeSurrounding("(", ")")
            val zone = when {
                zoneText.isEmpty() -> ZoneOffset.UTC
                else -> offset(zoneText) ?: zones[zoneText.uppercase()]?.let { ZoneOffset.ofHours(it) } ?: ZoneOffset.UTC
            }
            return time.atZone(zone).toInstant().toEpochMilli()
        }
        return null
    }

    private fun offset(z: String): ZoneOffset? {
        if (z.equals("Z", ignoreCase = true)) return ZoneOffset.UTC
        val m = Regex("""([+-])(\d{2}):?(\d{2})?""").matchEntire(z) ?: return null
        val sign = if (m.groupValues[1] == "-") -1 else 1
        return runCatching { ZoneOffset.ofHoursMinutes(sign * m.groupValues[2].toInt(), sign * (m.groupValues[3].ifEmpty { "0" }.toInt())) }.getOrNull()
    }
}
