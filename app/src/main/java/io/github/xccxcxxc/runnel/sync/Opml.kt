package io.github.xccxcxxc.runnel.sync

import io.github.xccxcxxc.runnel.data.db.FeedEntity
import io.github.xccxcxxc.runnel.data.db.FolderEntity
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream

/** OPML 里的一个订阅源；[folder] 是它所在文件夹（外层 outline）的名字。 */
data class OpmlEntry(val title: String, val xmlUrl: String, val htmlUrl: String?, val folder: String?)

/** OPML 2.0 导入导出（和别的阅读器互通）。 */
object Opml {
    /**
     * 读出所有带 xmlUrl 的 outline。文件夹取最近一层不带 xmlUrl 的外层 outline（多层嵌套只保留最里面那层）。
     * 用 jsoup 的 XML 解析器：容错、按 `<?xml encoding?>` 识别编码。同一个地址只取第一次出现的。
     */
    fun parse(bytes: ByteArray): List<OpmlEntry> {
        val doc = Jsoup.parse(ByteArrayInputStream(bytes), null, "", Parser.xmlParser())
        val out = LinkedHashMap<String, OpmlEntry>()
        fun walk(el: Element, folder: String?) {
            for (child in el.children()) {
                if (!child.tagName().equals("outline", ignoreCase = true)) {
                    walk(child, folder)
                    continue
                }
                val name = child.attr("title").ifBlank { child.attr("text") }.trim()
                val xmlUrl = child.attr("xmlUrl").ifBlank { child.attr("xmlurl") }.trim()
                if (xmlUrl.startsWith("http://") || xmlUrl.startsWith("https://")) {
                    out.getOrPut(xmlUrl) {
                        OpmlEntry(name, xmlUrl, child.attr("htmlUrl").ifBlank { child.attr("htmlurl") }.trim().ifBlank { null }, folder)
                    }
                } else {
                    walk(child, name.ifBlank { folder })
                }
            }
        }
        walk(doc, null)
        return out.values.toList()
    }

    fun build(title: String, folders: List<FolderEntity>, feeds: List<FeedEntity>): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<opml version=\"2.0\">\n")
        sb.append("  <head><title>").append(esc(title)).append("</title></head>\n  <body>\n")
        val folderIds = folders.mapTo(HashSet()) { it.id }
        for (f in folders.sortedBy { it.sortIndex }) {
            val inside = feeds.filter { it.folderId == f.id }
            if (inside.isEmpty()) continue
            sb.append("    <outline text=\"").append(esc(f.title)).append("\" title=\"").append(esc(f.title)).append("\">\n")
            inside.forEach { sb.append("      ").append(outline(it)).append('\n') }
            sb.append("    </outline>\n")
        }
        feeds.filter { it.folderId == null || it.folderId !in folderIds }.forEach { sb.append("    ").append(outline(it)).append('\n') }
        sb.append("  </body>\n</opml>\n")
        return sb.toString()
    }

    private fun outline(f: FeedEntity): String = buildString {
        append("<outline type=\"rss\" text=\"").append(esc(f.title)).append("\" title=\"").append(esc(f.title)).append('"')
        f.feedUrl?.let { append(" xmlUrl=\"").append(esc(it)).append('"') }
        f.siteUrl?.let { append(" htmlUrl=\"").append(esc(it)).append('"') }
        append("/>")
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
