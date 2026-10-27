package io.github.xccxcxxc.runnel.ui.nav

import androidx.activity.BackEventCompat
import androidx.compose.animation.core.animate
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.util.VelocityTracker1D
import io.github.xccxcxxc.runnel.data.AccountType
import io.github.xccxcxxc.runnel.data.ArticleSource
import io.github.xccxcxxc.runnel.data.FilterMode
import io.github.xccxcxxc.runnel.perf.PerfMonitor
import io.github.xccxcxxc.runnel.ui.theme.Motion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

sealed interface Route {
    /** 是否允许在页面任意位置右滑返回（列表页的行要用横滑，所以不允许）。 */
    val fullScreenBack: Boolean get() = this is Reader || this is Search

    /** 推入时等首屏数据（[NavEntry.markContentReady]）再开始滑入：文章列表的数据要查库，一屏十几行一起排版是最重的一帧。 */
    val waitsForContent: Boolean get() = this is Articles

    /** 添加账号：选服务（没有账号时是根页面）。 */
    data object AddAccount : Route
    /** 服务器账号的登录表单。 */
    data class Login(val type: AccountType) : Route
    /** 账号列表（根页面，照 Reeder）。 */
    data object Accounts : Route
    data class Home(override val accountId: String) : Route
    data class Articles(override val accountId: String, val source: ArticleSource, val filter: FilterMode) : Route
    data class Reader(override val accountId: String, val session: ReaderSession) : Route
    data class Search(override val accountId: String) : Route

    /** 页面所属的账号；账号列表、登录页为 null。 */
    val accountId: String? get() = null

    /** 帧统计里的页面名（release 包混淆后 javaClass.simpleName 会变成 “Route$Articles”）。 */
    val perfName: String
        get() = when (this) {
            AddAccount, is Login -> "Login"
            Accounts -> "Accounts"
            is Home -> "Home"
            is Articles -> "Articles"
            is Reader -> "Reader"
            is Search -> "Search"
        }
}

/** 阅读页和来源列表共享：阅读页翻到哪篇，列表就把哪篇滚到可见位置。 */
@Stable
class ReaderSession(
    ids: List<Long>,
    startIndex: Int,
    source: ArticleSource = ArticleSource.All,
    val filter: FilterMode = FilterMode.ALL,
) {
    var ids by mutableStateOf(ids)
    var currentIndex by mutableIntStateOf(startIndex)
    /** 当前阅读的来源；“读完后进入下一个订阅源”会把它和 ids 一起换成下一个来源的。 */
    var source by mutableStateOf(source)
}

/**
 * 页面栈里的一项。页面状态（列表快照等）挂在这里，页面被盖住、离开组合后也不会丢；
 * 出栈时取消 [scope]。相当于一个极简的 ViewModel。
 */
class NavEntry(val route: Route, val id: Int) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 正在从下往上进场（[Navigator.replaceTop]，用于“下一个订阅源”）；进场结束后恢复横向，返回时照常向右滑走。 */
    var enterFromBottom by mutableStateOf(false)
        internal set

    /** 页面首屏内容已经排好（[Route.waitsForContent] 的页面调用 [markContentReady]）：推入动画等它再开始。 */
    internal val contentReady = CompletableDeferred<Unit>()

    fun markContentReady() {
        contentReady.complete(Unit)
    }
    /** 页面上开着的、要暂停全屏右滑返回的浮层数（见 [BlockSwipeBack]）。 */
    internal var swipeBackBlockers by mutableIntStateOf(0)
    private val retained = HashMap<String, Any>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> retain(key: String, factory: () -> T): T = retained.getOrPut(key, factory) as T

    internal fun dispose() = scope.cancel()
}

val LocalNavEntry = compositionLocalOf<NavEntry> { error("no NavEntry") }
val LocalNavigator = compositionLocalOf<Navigator> { error("no Navigator") }

/**
 * 按在滑块上的那根手指：全屏右滑返回（页面栈、表单二级页）看到它就放手，横着拖滑块不会变成返回。
 * 返回手势在 Initial 阶段先于子组件拿到事件，只能由子组件在按下时登记，手势结束时撤销。
 */
object SwipeBackGuard {
    private var claimed: PointerId? = null
    fun claim(id: PointerId) { claimed = id }
    fun release(id: PointerId) { if (claimed == id) claimed = null }
    fun isClaimed(id: PointerId) = claimed == id
}

/**
 * [block] 期间所在页面暂停全屏右滑返回（照 iOS：页面上弹出面板时不能侧滑返回）。
 * 阅读设置面板用：右滑返回在 Initial 阶段先于面板拿到触摸，不停掉的话往右拖滑块会变成返回。
 */
@Composable
fun BlockSwipeBack(block: Boolean) {
    if (!block) return
    val entry = LocalNavEntry.current
    DisposableEffect(entry) {
        entry.swipeBackBlockers++
        onDispose { entry.swipeBackBlockers-- }
    }
}

/**
 * 页面栈 + 转场进度。[progress] 表示栈顶页面盖住下层的程度（1 = 完全盖住）：
 * 推入时 0→1，返回时 1→0；侧滑返回时由手指位置直接驱动，松手后带速度弹到终点。
 */
@Stable
class Navigator(start: List<Route>, private val scope: CoroutineScope) {
    private var nextId = 0
    val stack = mutableStateListOf<NavEntry>().apply { start.forEach { add(NavEntry(it, nextId++)) } }
    var progress by mutableFloatStateOf(1f)
        private set
    var transitioning by mutableStateOf(false)
        private set

    /** 转场开始（[kind] = 推入 / 返回 / 下一个订阅源）或结束（null）；同时告诉帧统计。 */
    private fun transition(kind: String?) {
        transitioning = kind != null
        PerfMonitor.setTransition(kind)
    }
    var widthPx = 1f
    val canPop by derivedStateOf { stack.size > 1 }
    internal var onEntryRemoved: (NavEntry) -> Unit = {}
    private var job: Job? = null

    val top: NavEntry get() = stack.last()

    fun push(route: Route) {
        if (transitioning) return
        transition("推入")
        PerfMonitor.setScreen(route.perfName)
        progress = 0f
        val entry = NavEntry(route, nextId++)
        stack += entry
        job = scope.launch {
            // 新页面的首次组合/布局很重，先让它在屏幕外完成，动画再开始，
            // 否则动画的第一帧会被卡住，肉眼看到的就是“点了没反应一下才滑进来”。
            awaitOffscreenReady(entry)
            animate(0f, 1f, animationSpec = Motion.navigation) { v, _ -> progress = v }
            transition(null)
        }
    }

    /**
     * 从下往上推入 [route]（下一个订阅源从底部升上来，当前页被顶上去），
     * 动画结束后把被盖住的当前页移出栈：返回时直接回到更下面一层（“打开下一个订阅源”用）。
     */
    fun replaceTop(route: Route) {
        if (transitioning) return
        val old = stack.last()
        transition("下一个订阅源")
        PerfMonitor.setScreen(route.perfName)
        progress = 0f
        val entry = NavEntry(route, nextId++).also { it.enterFromBottom = true }
        stack += entry
        job = scope.launch {
            awaitOffscreenReady(entry)
            animate(0f, 1f, animationSpec = Motion.navigation) { v, _ -> progress = v }
            entry.enterFromBottom = false
            if (stack.remove(old)) {
                old.dispose()
                onEntryRemoved(old)
            }
            transition(null)
        }
    }

    /**
     * 等新页面在屏幕外组合、排好首屏：至少两帧；文章列表还要等数据到了、首屏行排好（最多 [MAX_CONTENT_WAIT_MS]），
     * 否则 120Hz 下两帧只有 17ms，查库慢一点首屏行就会在滑动的第一帧里排版（真机 15–29ms，2026-10-20）。
     */
    private suspend fun awaitOffscreenReady(entry: NavEntry) {
        withFrameNanos { }
        if (entry.route.waitsForContent) withTimeoutOrNull(MAX_CONTENT_WAIT_MS) { entry.contentReady.await() }
        withFrameNanos { }
    }

    fun pop() {
        if (stack.size <= 1 || transitioning) return
        transition("返回")
        job = scope.launch {
            animate(progress, 0f, animationSpec = Motion.navigation) { v, _ -> progress = v }
            removeTop()
        }
    }

    /** 清空栈换成 [routes]（最后一个在顶上；添加、移除账号后用）。 */
    fun replaceAll(vararg routes: Route) {
        job?.cancel()
        stack.toList().forEach { it.dispose(); onEntryRemoved(it) }
        stack.clear()
        routes.forEach { stack += NavEntry(it, nextId++) }
        progress = 1f
        transition(null)
        PerfMonitor.setScreen(routes.last().perfName)
    }

    /** 系统预测性返回手势：页面跟手平移；取消则弹回，确认则带手指速度完成返回。 */
    suspend fun handleBackGesture(events: Flow<BackEventCompat>) {
        if (stack.size <= 1) return
        job?.cancel()
        transition("返回")
        val tracker = VelocityTracker1D(isDataDifferential = false)
        var startX = Float.NaN
        try {
            events.collect { e ->
                if (startX.isNaN()) startX = e.touchX
                val p = 1f - (abs(e.touchX - startX) / widthPx).coerceIn(0f, 1f)
                progress = p
                tracker.addDataPoint(System.nanoTime() / 1_000_000, p)
            }
            val velocity = runCatching { tracker.calculateVelocity() }.getOrDefault(0f).coerceAtMost(0f)
            animate(progress, 0f, initialVelocity = velocity, animationSpec = Motion.navigationRelease) { v, _ -> progress = v }
            removeTop()
        } catch (e: CancellationException) {
            job = scope.launch {
                animate(progress, 1f, animationSpec = Motion.navigationRelease) { v, _ -> progress = v }
                transition(null)
            }
            throw e
        }
    }

    /**
     * 页面内的全屏右滑返回（阅读页用）：[beginDragBack] 成功后用 [dragBack] 按手指位移更新，
     * 松手调用 [endDragBack]（速度单位：屏宽/秒，向右为正）。
     */
    fun beginDragBack(): Boolean {
        if (stack.size <= 1 || transitioning) return false
        job?.cancel()
        transition("返回")
        return true
    }

    fun dragBack(fraction: Float) {
        progress = 1f - fraction.coerceIn(0f, 1f)
    }

    fun endDragBack(velocity: Float) {
        val commit = velocity > 0.8f || (progress < 0.55f && velocity > -0.3f)
        job = scope.launch {
            if (commit) {
                animate(progress, 0f, initialVelocity = -velocity.coerceAtLeast(0f), animationSpec = Motion.navigationRelease) { v, _ -> progress = v }
                removeTop()
            } else {
                animate(progress, 1f, animationSpec = Motion.navigationRelease) { v, _ -> progress = v }
                transition(null)
            }
        }
    }

    private companion object {
        /** 等文章列表首屏最多这么久，超时照常滑入（查库异常慢时不能让点按没反应）。 */
        const val MAX_CONTENT_WAIT_MS = 150L
    }

    private fun removeTop() {
        val entry = stack.removeAt(stack.lastIndex)
        PerfMonitor.setScreen(stack.last().route.perfName)
        progress = 1f
        transition(null)
        entry.dispose()
        onEntryRemoved(entry)
    }
}
