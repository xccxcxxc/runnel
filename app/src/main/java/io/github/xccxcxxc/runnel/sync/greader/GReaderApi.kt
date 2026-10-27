package io.github.xccxcxxc.runnel.sync.greader

import io.github.xccxcxxc.runnel.data.AccountType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/** [detail] 是服务器给的原因（Miniflux 的 `error_message`），没有时是响应正文开头。 */
class GReaderException(message: String, val code: Int = 0, val detail: String = "") : IOException(message)

/**
 * Google Reader 兼容 API 客户端，FreshRSS 和 Miniflux 共用。
 *
 * [apiBase] 是 API 根地址：FreshRSS 为 `https://host/api/greader.php`，Miniflux 为 `https://host`。
 * 接口路径都接在它后面：`/accounts/ClientLogin`、`/reader/api/0/...`。
 */
class GReaderApi(
    private val http: OkHttpClient,
    apiBase: String,
    @Volatile var authToken: String = "",
) {
    private val base: HttpUrl = apiBase.trimEnd('/').toHttpUrl()
    @Volatile private var writeToken: String? = null

    /** 登录，返回 Auth token（也会保存到 [authToken]）。 */
    suspend fun login(username: String, password: String): String {
        val body = FormBody.Builder().add("Email", username).add("Passwd", password).build()
        val text = call(Request.Builder().url(url("accounts/ClientLogin")).post(body).build(), auth = false)
        val token = text.lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith("Auth=") }?.removePrefix("Auth=")
            ?: throw GReaderException("登录失败：服务器没有返回 Auth")
        authToken = token
        writeToken = null
        return token
    }

    suspend fun subscriptions(): List<Subscription> {
        val text = call(get("reader/api/0/subscription/list") { addQueryParameter("output", "json") })
        return GReaderJson.decodeFromString<SubscriptionList>(text).subscriptions
    }

    /**
     * 流内的文章 id（新的在前），自动跟随 continuation，最多 [limit] 条。
     * [exclude] 例如 [Streams.READ] 表示只要未读；[newerThanSec] 对应 `ot` 参数。
     */
    suspend fun itemIds(
        stream: String,
        exclude: String? = null,
        limit: Int = 10_000,
        newerThanSec: Long? = null,
    ): List<Long> {
        val out = ArrayList<Long>()
        var continuation: String? = null
        do {
            val page = minOf(1000, limit - out.size)
            val text = call(get("reader/api/0/stream/items/ids") {
                addQueryParameter("output", "json")
                addQueryParameter("s", stream)
                addQueryParameter("n", page.toString())
                if (exclude != null) addQueryParameter("xt", exclude)
                if (newerThanSec != null && newerThanSec > 0) addQueryParameter("ot", newerThanSec.toString())
                if (continuation != null) addQueryParameter("c", continuation)
            })
            val refs = GReaderJson.decodeFromString<ItemRefs>(text)
            refs.itemRefs.mapTo(out) { ItemIds.parse(it.id) }
            continuation = refs.continuation?.takeIf { it.isNotBlank() && refs.itemRefs.isNotEmpty() }
        } while (continuation != null && out.size < limit)
        return out
    }

    /**
     * 按 id 批量取正文。调用方负责分块（每块建议 ≤ 200）。
     * Miniflux 要求所有 POST 都带写 token（T 参数），FreshRSS 带上也无妨。
     */
    suspend fun contents(ids: List<Long>): List<Item> {
        if (ids.isEmpty()) return emptyList()
        val text = postWithToken("reader/api/0/stream/items/contents?output=json") {
            ids.forEach { add("i", ItemIds.toLongForm(it)) }
        }
        return GReaderJson.decodeFromString<StreamContents>(text).items
    }

    /** 给文章加/去标签（已读、星标）。 */
    suspend fun editTag(ids: List<Long>, add: String? = null, remove: String? = null) {
        if (ids.isEmpty()) return
        postWithToken("reader/api/0/edit-tag") {
            ids.forEach { add("i", ItemIds.toLongForm(it)) }
            if (add != null) add("a", add)
            if (remove != null) add("r", remove)
        }
    }

    /**
     * 订阅一个新源，返回服务端的 feed 流 id；服务器没找到订阅源时返回 null。
     * 填网站首页地址也可以：FreshRSS 和 Miniflux 都会从网页里自动发现订阅地址。
     */
    suspend fun quickAdd(feedUrl: String): String? {
        val text = try {
            postWithToken("reader/api/0/subscription/quickadd") { add("quickadd", feedUrl) }
        } catch (e: GReaderException) {
            // Miniflux 抓不到地址时返回 400/500 + error_message，FreshRSS 则是 200 + numResults 0
            if (e.code == 400 || e.code == 500) throw GReaderException("没有找到订阅源：${e.detail}", e.code, e.detail) else throw e
        }
        return Regex("\"streamId\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)?.takeIf(String::isNotBlank)
    }

    /**
     * 改订阅：[title] 改名，[addLabel] 移进文件夹（文件夹的流 id，如 `user/-/label/科技`，服务端没有时会新建），
     * [removeLabel] 移出原来的文件夹。都为 null 时什么都不做。
     */
    suspend fun editSubscription(streamId: String, title: String? = null, addLabel: String? = null, removeLabel: String? = null) {
        if (title == null && addLabel == null && removeLabel == null) return
        postWithToken("reader/api/0/subscription/edit") {
            add("ac", "edit")
            add("s", streamId)
            if (title != null) add("t", title)
            if (addLabel != null) add("a", addLabel)
            if (removeLabel != null) add("r", removeLabel)
        }
    }

    suspend fun unsubscribe(streamId: String) {
        postWithToken("reader/api/0/subscription/edit") {
            add("ac", "unsubscribe")
            add("s", streamId)
        }
    }

    private suspend fun postWithToken(path: String, fill: FormBody.Builder.() -> Unit): String {
        repeat(2) { attempt ->
            val token = writeToken ?: fetchWriteToken()
            val body = FormBody.Builder().apply(fill).add("T", token).build()
            try {
                return call(Request.Builder().url(url(path)).post(body).build())
            } catch (e: GReaderException) {
                // 写 token 过期：服务端返回 401 + X-Reader-Google-Bad-Token，重取一次
                if (e.code == 401 && attempt == 0) writeToken = null else throw e
            }
        }
        throw GReaderException("写操作失败")
    }

    private suspend fun fetchWriteToken(): String =
        call(get("reader/api/0/token") {}).trim().also { writeToken = it }

    private fun url(path: String): HttpUrl {
        val query = path.substringAfter('?', "")
        val b = base.newBuilder().addPathSegments(path.substringBefore('?'))
        if (query.isNotEmpty()) b.encodedQuery(query)
        return b.build()
    }

    private fun get(path: String, params: HttpUrl.Builder.() -> Unit): Request =
        Request.Builder().url(url(path).newBuilder().apply(params).build()).get().build()

    private suspend fun call(request: Request, auth: Boolean = true): String = withContext(Dispatchers.IO) {
        val req = if (auth) request.newBuilder().header("Authorization", "GoogleLogin auth=$authToken").build() else request
        http.newCall(req).execute().use { resp -> resp.bodyOrThrow() }
    }

    private fun Response.bodyOrThrow(): String {
        val text = body.string()
        if (!isSuccessful) {
            val hint = when (code) {
                401, 403 -> "认证失败（检查用户名和 API 密码）"
                404 -> "找不到接口（检查服务器地址）"
                else -> "HTTP $code"
            }
            val detail = ERROR_MESSAGE.find(text)?.groupValues?.get(1)?.replace("\\\"", "\"") ?: text.take(200)
            throw GReaderException("$hint: $detail", code, detail)
        }
        return text
    }

    companion object {
        private val ERROR_MESSAGE = Regex("\"error_message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

        /** 把用户填的服务器地址规范成 API 根地址。 */
        fun normalizeBase(type: AccountType, input: String): String {
            var s = input.trim().trimEnd('/')
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
            return when (type) {
                AccountType.FRESHRSS ->
                    if (s.contains("greader.php")) s.substringBefore("greader.php") + "greader.php"
                    else if (s.endsWith("/api")) "$s/greader.php"
                    else "$s/api/greader.php"
                else -> s.removeSuffix("/reader/api/0")
            }
        }
    }
}
