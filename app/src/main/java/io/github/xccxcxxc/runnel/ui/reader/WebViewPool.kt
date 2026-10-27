package io.github.xccxcxxc.runnel.ui.reader

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.xccxcxxc.runnel.Graph
import io.github.xccxcxxc.runnel.content.ReaderImageCache
import io.github.xccxcxxc.runnel.data.OpenLinks

/**
 * WebView 池：第一次创建 WebView 要初始化 Chromium（100–300ms），放在启动后的空闲时刻提前做，
 * 打开文章时直接从池里拿。用 MutableContextWrapper 在 Activity 和 Application 之间切换，避免泄漏。
 */
object WebViewPool {
    private const val MAX_POOLED = 3
    private val pool = ArrayDeque<WebView>()

    /** 首帧之后、主线程空闲时预热 [count] 个。 */
    fun prewarm(context: Context, count: Int = 2) {
        val app = context.applicationContext
        Looper.myQueue().addIdleHandler {
            if (pool.size < count) pool.addLast(create(app))
            pool.size < count // 每次空闲只建一个，建够为止
        }
    }

    fun obtain(context: Context): WebView {
        val view = pool.removeFirstOrNull() ?: create(context.applicationContext)
        (view.context as MutableContextWrapper).baseContext = context
        return view
    }

    /**
     * 最近退出的文章的 WebView 不清空，正文和滚动位置原样留着（最多 [MAX_PARKED] 篇）：再打开同一篇直接拿回来用，
     * 不重新载入，也就不用再滚到上次的位置（2026-10-24 用户要求“直接回到原处，不要滑”）。
     */
    private const val MAX_PARKED = 2
    /** 键是 “账号 id:文章 id”（不同账号的文章 id 可能重复）。 */
    private val parked = LinkedHashMap<String, WebView>()

    fun park(id: String, view: WebView) {
        (view.webChromeClient as? FullscreenChrome)?.exit()
        (view.parent as? ViewGroup)?.removeView(view)
        view.setOnScrollChangeListener(null)
        view.setOnLongClickListener(null)
        view.evaluateJavascript(PAUSE_MEDIA, null)
        view.onPause()
        (view.context as MutableContextWrapper).baseContext = view.context.applicationContext
        parked.remove(id)?.takeIf { it !== view }?.let(::recycle)
        parked[id] = view
        while (parked.size > MAX_PARKED) recycle(parked.remove(parked.keys.first())!!)
    }

    /** 取回 [id] 留着的 WebView（没有就返回 null），交给 [context] 的页面。 */
    fun takeParked(id: String, context: Context): WebView? {
        val view = parked.remove(id) ?: return null
        (view.context as MutableContextWrapper).baseContext = context
        view.onResume()
        return view
    }

    private const val PAUSE_MEDIA = "document.querySelectorAll('video,audio').forEach(function(m){m.pause()})"

    fun recycle(view: WebView) {
        view.onResume() // 留着时暂停过；回到池里给别的文章用
        (view.webChromeClient as? FullscreenChrome)?.exit()
        (view.parent as? ViewGroup)?.removeView(view)
        view.stopLoading()
        view.setOnScrollChangeListener(null)
        view.setOnLongClickListener(null)
        view.tag = null
        view.scrollTo(0, 0)
        (view.context as MutableContextWrapper).baseContext = view.context.applicationContext
        if (pool.size < MAX_POOLED) {
            view.loadUrl("about:blank")
            view.clearHistory()
            pool.addLast(view)
        } else {
            view.destroy()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(app: Context): WebView = WebView(MutableContextWrapper(app)).apply {
        setBackgroundColor(Color.TRANSPARENT)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        // 不要系统的拉伸回弹：到边缘继续拉是“切换上一篇/下一篇”，由阅读页自己画
        overScrollMode = View.OVER_SCROLL_NEVER
        settings.apply {
            // 正文里的脚本在同步时已经清掉；开 JS 和 DOM 存储只为嵌入的播放器（微博、B 站的播放器是单页应用，没有 localStorage 就白屏）
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            loadsImagesAutomatically = true
        }
        webViewClient = ReaderClient
        webChromeClient = FullscreenChrome(this)
        // 嵌入的播放器和正文不同域，要能存自己的 Cookie（微博视频靠访客 Cookie 才给播放地址）
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
    }

    /**
     * 播放器点全屏：WebView 交出一个视频 View，盖在整个 Activity 上，隐藏状态栏和导航栏、横过来；
     * 返回键或播放器里再点一次退出全屏。
     */
    private class FullscreenChrome(private val web: WebView) : WebChromeClient() {
        private var overlay: View? = null
        private var callback: CustomViewCallback? = null
        private var back: OnBackPressedCallback? = null
        private var savedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            val activity = web.context.findActivity() as? ComponentActivity
            if (activity == null || overlay != null) {
                callback.onCustomViewHidden()
                return
            }
            this.callback = callback
            val decor = activity.window.decorView as ViewGroup
            overlay = FrameLayout(activity).apply {
                setBackgroundColor(Color.BLACK)
                isClickable = true // 别让点击漏到下面的阅读页
                tag = FULLSCREEN_TAG
                addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
            }.also { decor.addView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)) }
            WindowCompat.getInsetsController(activity.window, decor).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
            savedOrientation = activity.requestedOrientation
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            back = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = exit()
            }.also { activity.onBackPressedDispatcher.addCallback(it) }
        }

        override fun onHideCustomView() {
            val view = overlay ?: return
            overlay = null
            callback = null
            back?.remove()
            back = null
            (view.parent as? ViewGroup)?.removeView(view)
            val activity = view.context.findActivity() ?: return
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = savedOrientation
        }

        /** 退出全屏（返回键、文章页被回收时）。 */
        fun exit() {
            val cb = callback ?: return
            cb.onCustomViewHidden()
            onHideCustomView()
        }
    }

    /** 全屏视频盖在 decorView 上的那一层（测试用来找它）。 */
    const val FULLSCREEN_TAG = "reader_fullscreen_video"

    private object ReaderClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (!request.isForMainFrame) return false
            return handleLink(view.context, request.url)
        }

        // 图片走 App 自己的磁盘缓存（ReaderImageCache），没网时看过的图照样显示；在后台线程调用，可以阻塞
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            if (request.isForMainFrame) return null
            val url = request.url
            if (!ReaderImageCache.isImageRequest(request.method, url.scheme, url.path, request.requestHeaders)) return null
            return Graph.get().readerImages.fetch(url.toString(), request.requestHeaders)
        }
    }

    /** 阅读页里点了链接：标题（打开原文）和正文链接都按“打开链接”设置在浏览器里打开。返回 true 表示已处理。 */
    fun handleLink(context: Context, url: Uri): Boolean {
        if (url.scheme == "about" || url.scheme == "data") return false
        val target = if (url.scheme == ReaderHtml.ORIGINAL_SCHEME) url.getQueryParameter("u")?.let(Uri::parse) ?: return true else url
        openInBrowser(context, target)
        return true
    }

    /** 按设置里的“打开链接”：系统默认浏览器，或应用内的浏览器标签页（Custom Tabs）。 */
    fun openInBrowser(context: Context, uri: Uri) {
        runCatching {
            if (Graph.get().prefs.settings.value.openLinks == OpenLinks.IN_APP) {
                val tabs = CustomTabsIntent.Builder().setShowTitle(true).build()
                // 不是 Activity 的 context（比如回到池里的 WebView）启动不了新页面，系统会直接拒绝
                if (context.findActivity() == null) tabs.intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                tabs.launchUrl(context, uri)
            } else {
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}

private tailrec fun Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
