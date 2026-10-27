package io.github.xccxcxxc.runnel.sync.local

import io.github.xccxcxxc.runnel.sync.FeedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

/** 一次抓取的结果。 */
sealed interface FetchResult {
    /** 服务器说没变（304）。 */
    data object NotModified : FetchResult

    data class Ok(val feed: ParsedFeed, val etag: String?, val lastModified: String?) : FetchResult
}

/** 本机账号抓订阅源：条件请求、大小上限、按 HTTP 头和 XML 声明识别编码；添加订阅时从网页里找订阅地址。 */
class FeedFetcher(http: OkHttpClient) {
    private val http = http.newBuilder().callTimeout(45, TimeUnit.SECONDS).build()

    suspend fun fetch(url: String, etag: String? = null, lastModified: String? = null): FetchResult = withContext(Dispatchers.IO) {
        val (bytes, resp) = get(url, etag, lastModified) ?: return@withContext FetchResult.NotModified
        val feed = withContext(Dispatchers.Default) { FeedParser.parse(bytes, resp.charset, resp.finalUrl) }
        FetchResult.Ok(feed, resp.etag, resp.lastModified)
    }

    /**
     * 用户填的地址 → 真正的订阅地址和解析结果。填的就是订阅源最好；是网页就找网页里声明的订阅地址，
     * 都没有再试常见路径（/feed、/rss.xml、/atom.xml ……）。
     */
    suspend fun discover(input: String): Pair<String, FetchResult.Ok> = withContext(Dispatchers.IO) {
        val first = try {
            get(input, null, null)
        } catch (e: IOException) {
            throw FeedException("打不开这个地址：${e.message ?: e.javaClass.simpleName}")
        } ?: throw FeedException("打不开这个地址")
        val (bytes, resp) = first
        val direct = runCatching { FeedParser.parse(bytes, resp.charset, resp.finalUrl) }
        direct.getOrNull()?.let { return@withContext resp.finalUrl to FetchResult.Ok(it, resp.etag, resp.lastModified) }
        if (direct.exceptionOrNull() !is NotAFeedException) direct.exceptionOrNull()?.let { throw FeedException("订阅源格式不对：${it.message}") }

        val page = Jsoup.parse(String(bytes, resp.charset ?: Charsets.UTF_8), resp.finalUrl)
        val origin = URI(resp.finalUrl).let { "${it.scheme}://${it.rawAuthority}" }
        val candidates = (FeedParser.discoverLinks(page) + COMMON_PATHS.map { origin + it }).distinct().take(MAX_CANDIDATES)
        for (candidate in candidates) {
            val r = runCatching { fetch(candidate) }.getOrNull() as? FetchResult.Ok ?: continue
            return@withContext candidate to r
        }
        throw FeedException("这个地址没有找到订阅源")
    }

    private class Meta(val finalUrl: String, val charset: java.nio.charset.Charset?, val etag: String?, val lastModified: String?)

    /** null = 304。 */
    private fun get(url: String, etag: String?, lastModified: String?): Pair<ByteArray, Meta>? {
        val request = Request.Builder().url(url)
            .header("Accept", ACCEPT)
            .apply {
                if (etag != null) header("If-None-Match", etag)
                if (lastModified != null) header("If-Modified-Since", lastModified)
            }
            .build()
        http.newCall(request).execute().use { resp ->
            if (resp.code == 304) return null
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body
            if (body.contentLength() > MAX_BYTES) throw IOException("订阅源太大")
            val source = body.source()
            source.request(MAX_BYTES + 1)
            if (source.buffer.size > MAX_BYTES) throw IOException("订阅源太大")
            val bytes = source.buffer.readByteArray()
            return bytes to Meta(
                finalUrl = resp.request.url.toString(),
                charset = body.contentType()?.charset(),
                etag = resp.header("ETag"),
                lastModified = resp.header("Last-Modified"),
            )
        }
    }

    companion object {
        private const val MAX_BYTES = 10L * 1024 * 1024
        private const val MAX_CANDIDATES = 10
        private const val ACCEPT =
            "application/rss+xml, application/atom+xml, application/feed+json, application/xml;q=0.9, text/xml;q=0.9, */*;q=0.8"
        private val COMMON_PATHS = listOf("/feed", "/rss", "/feed.xml", "/rss.xml", "/atom.xml", "/index.xml", "/feed/")
    }
}
