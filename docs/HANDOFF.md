# 交接

阅读顺序：`CLAUDE.md`（构建、测试、提交规矩）→ `docs/SPEC.md`（页面、架构、编码规则）→ 本文件（当前状态、下一步、坑）。

## 0. 开源（2026-10-21）

- 公开仓库：`xccxcxxc/runnel`（GPL-3.0），从一个初始提交开始，没有之前的历史。原来的私有仓库改名为 `xccxcxxc/brook-private`，保持私有；v1.0.0–v2.1 的 Release 和完整提交历史都在那里，下文 §1 里的提交号、Release 链接指的是那个仓库。
- **App 图标**（2026-10-23 重画）：默认“涟漪”（青绿渐变底，白色水滴 + 两圈涟漪 = RSS 符号），备选“页中溪”（文章卡片 + 溪水），设置 → 外观 → App 图标 里切换。桌面入口是 manifest 里两个 activity-alias（`.LauncherRipple` / `.LauncherPage`，同时只启用一个，`ui/settings/AppIcon.kt`），`MainActivity` 本身不再带 LAUNCHER，测试和 benchmark 照常显式启动它。加新图标：画 `ic_launcher_xxx_*` 三层、加 alias（默认 `enabled="false"`）、`AppIcon` 加一项。
- 开源前做过的：自己画的 App 图标（已被上一条替换）、强调色青绿（浅 #2F8F83 / 深 #4FB3A6）、订阅源无图标时显示名称首字、去掉第三方 App 的名称/截图/素材、清理本机路径、加 `LICENSE` 和设置 → 关于 → “开源许可”页（`ui/settings/LicensesPage.kt`，**新增依赖要在 `dependencies` 补一行**）。
- 演示数据里的订阅源名都是虚构的，不要换回真实网站。对外名称 2026-10-21 改为 **Runnel**（Brook 和 txthinking/brook 撞名；Rook 和 rook/rook 撞名，试过后放弃），内部名称仍是 Brook，见 `CLAUDE.md` 开头。上架 Google Play 前还要查商标。
- **账号凭据**（2026-10-21）：密码不再保存（登录后只用服务器发的 token）；token 用 Android Keystore AES-GCM 加密，账号单独放 `shared_prefs/account.xml` 并从自动备份/换机迁移里排除（`res/xml/backup_rules.xml`、`data_extraction_rules.xml`），恢复到新设备后回到登录页。旧版明文账号在升级后第一次启动时迁移，保持登录。Keystore 只在第一次同步时解密（`Prefs.authToken()`），不在启动路径上。仪器测试 `AccountStorageTest`（Robolectric 没有 Keystore）。
- **明文 http**：用户决定不限制（只允许局域网走 http 会让公网 http 的订阅源图片打不开）；登录页对公网 http 地址给提示，不拦截。
- **之后不要往仓库里放**：别的 App 的截图、图标、从安装包里提取的文件、品牌名；本机路径、账号、设备信息。
- versionCode = 200 + 提交数（`app/build.gradle.kts`）：公开仓库的历史压成过 1 个提交，加 200 保证比之前所有的包（私有仓库时期和公开仓库早期，最大 110）都大，手机上可以直接覆盖安装。
- 本地 `origin` = 公开仓库（推送用 gh 的凭据，token 需要 `workflow` 权限），`private` = 私有仓库。
- 签名：2026-10-23 起包名改为 `io.github.xccxcxxc.runnel`，换了新证书（CN=Runnel）；旧包 `io.github.xccxcxxc.brook` 不能覆盖升级，需手动卸载。公开仓库的 Secrets 是 `RUNNEL_KEYSTORE_B64` / `RUNNEL_KEYSTORE_PASSWORD`，旧的 `BROOK_*` 已弃用，可删。

## 1. 当前状态（2026-10-27）

- **多账号 + 本机账号 + 本机导入 OPML**（2026-10-27，CI 测试包，用户还没真机测）：用户要求照 Reeder 做，以后还要接 Feedly 等，所以先把多账号底座做好。
  - 结构见 `docs/SPEC.md` §2“多账号”“本机账号”。三步：① 每个账号一个 `AccountSession`（数据库、账号设置、FlagStore、SyncEngine、仓库、离线图片），页面靠 `LocalAccount`、路由带 `accountId`；② `SyncEngine` 只剩通用流程，各服务实现 `AccountBackend`（`GReaderBackend` / `LocalBackend` / `DemoBackend`），以后加 Feedly 就是再写一个；③ 本机账号 `sync/local/`。
  - 页面：根页面是账号列表（`ui/home/AccountsScreen`），启动直接进上次打开的账号首页（栈 = 账号列表 + 首页）；首页左上从“设置”改成返回账号列表，设置只在账号列表左上角（照 Reeder）。添加账号是服务列表（`ui/login/LoginScreen.kt` 的 `AddAccountScreen`）。首页长按订阅源 → 编辑订阅源表单（改名、换文件夹、新建文件夹、取消订阅；服务器账号走 subscription/edit，也是新功能）。
  - 升级迁移（`Prefs.migrateToMultiAccount`）：单账号版本的账号拿一个 id，数据库沿用 `runnel.db`（不搬文件），token 原样，原来全局的账号设置（同步方式/间隔、保留已读、订阅源排序、Reader View、离线图片、标记已读方式）和同步进度搬进 `acct_<id>.xml`；旧的后台任务 `runnel-sync` 取消，换成每个账号一个 `runnel-sync-<id>`。数据库 v2 → v3（AutoMigration，只新增 `local_feed_state`、`local_seen` 两张表）。
  - 移除账号：先离开它的页面，再删账号记录和设置，1 秒后关数据库、删文件（等还在跑的查询结束）。
  - 本机环境：这次在 Linux arm64 机器上做的，`~/.local/android-build-tools/jdk/21` + `~/Android/Sdk`，x86 的 aapt2 靠 qemu-binfmt 能跑，能编译、跑单元测试；没有 KVM，**不能开模拟器，仪器测试一个都没跑**。`ReaderImageCacheTest` 用了 Robolectric 原生运行时，arm64 Linux 不支持，本机必失败（CI 上正常）。
  - 新测试：单元 `FeedParserTest`（RSS 2.0/1.0、Atom、JSON Feed、不规范写法、GBK、日期、文章 id）、`FeedFetcherTest`（从网页找订阅地址、304、错误）、`OpmlTest`（导入、导出再导入）、`GReaderApiTest.renameMoveAndUnsubscribe`；仪器 `AccountStorageTest`（重写：多账号、单账号版本迁移）、`LocalAccountTest`（添加、304、清理后不回来、编辑/取消订阅、导入 OPML、账号隔离）、`AccountsTest`（启动进上次账号、账号列表切换、设置里移除账号）。其余仪器测试改成用 `TestAccounts.fresh()` 建账号。**下次在 Windows 上先跑一遍全套仪器测试**。
  - 已知没做：文件夹改名/删除；跨账号合并显示（用户明确不要）；换机迁移/系统备份不带账号列表（`account.xml` 不备份），本机账号的订阅会丢，先导出 OPML。

- **Runnel 1.12 已发布**（2026-10-24）：正式 Release `v1.12`（https://github.com/xccxcxxc/runnel/releases/tag/v1.12，附 `Runnel-1.12.apk`，versionCode 215，正式签名 CN=Runnel，目标提交 `cf98b72`），可覆盖安装 1.11，用户还没真机测。相对 1.11 一项——再次打开刚读过的文章直接在原处，不重新载入、不滚动。用户嫌 1.11 的“盖住再露出”还是不够直接。
  - `WebViewPool.park/takeParked`：退出阅读页时，当前那篇（`ArticlePage` 释放时 `isCurrent` 为真且已恢复完）的 WebView 不清空，暂停媒体、`onPause` 后留在内存，最多 2 篇（用户定的），多了按最久的回收。翻页时滑出去的相邻页照常回收。
  - 再打开同一篇：`ArticlePage` 先 `takeParked`，有就直接用，`ScrollRestore.target` 为 0（不恢复、不遮盖）。如果期间改了字号、样式，新 HTML 和留着的不一样，只能重新载入，按它原来的 scrollY 走 1.11 的恢复流程。被挤出去的文章仍靠 `ReaderScrollMemory` + 恢复流程。
  - 已知：每次进阅读页 `ReaderState` 是新的，手动切过全文的文章再进是摘要（以前就这样），这时 HTML 对不上，也走重新载入恢复。
  - 测试：`ReaderTest.reopenShowsSameWebViewAtSamePosition`（同一个 WebView 实例、一出现 scrollY 就等于退出时，0.5 秒后也不变）。
- **Runnel 1.11 已发布**（2026-10-24）：正式 Release `v1.11`（https://github.com/xccxcxxc/runnel/releases/tag/v1.11，附 `Runnel-1.11.apk`，versionCode 212，正式签名 CN=Runnel，目标提交 `813175a`），可覆盖安装 1.1。用户还没真机测，相对 1.1 两项。
  - 长按阅读页图片 → 底部菜单“保存到相册 / 分享图片”（`ui/reader/ImageActions.kt`）：`ArticlePage` 给 WebView 设 `OnLongClickListener`，`hitTestResult` 是 IMAGE / SRC_IMAGE_ANCHOR 才接管（长按文字仍系统选字）；图片经 `ReaderImageCache.download`（缓存优先，带文章 Referer）取字节，保存走 MediaStore（Pictures/Runnel，不需要权限），分享写到 `cache/shared-images` 再走 FileProvider（`${applicationId}.fileprovider`，`res/xml/file_paths.xml`）。`data:` 图片也支持。
  - 再次打开读过的文章回到原位置时不再“滑”：以前先显示顶部、再 `scrollTo` 跳过去。现在 `ScrollRestore.revealed` 为 false 时 `ArticlePage` 用背景色盖住 WebView，滚到位后（或最多 0.8 秒）再露出，重试间隔 50ms。
  - 测试：`ReaderTest.longPressImageOffersSaveAndShare`、`reopenRestoresScrollPosition`（1.12 改为 `reopenShowsSameWebViewAtSamePosition`）。注意 Windows 上没有 `pwsh`，`scripts/connected-test.ps1` 是无 BOM 的 UTF-8，PowerShell 5.1 直接跑会乱码，要先复制成带 BOM 的临时文件再用 `powershell -File` 跑。
- **Runnel 1.1 已发布**（2026-10-23）：正式 Release `v1.1`（https://github.com/xccxcxxc/runnel/releases/tag/v1.1，附 `Runnel-1.1.apk`，versionCode 209，正式签名 CN=Runnel，目标提交 `7e60ad6`），可覆盖安装 1.0。相对 1.0 只有下一条“阅读页视频”。
- **阅读页视频**（含在 1.1，2026-10-23，用户还没真机测）：喷嚏图卦、虎嗅的视频打不开。
  - 喷嚏：正文里是微博视频 / B 站 / 喷嚏自己的音频卡片 iframe，`ArticleProcessor` 以前只放行 YouTube 等，其余换成“嵌入内容 ↗”链接。现在放行国内视频站和与文章同主机的 iframe（`ArticleProcessor.embeddable`，按主机名后缀，不看整个地址），视频站的 http 改 https；旧文章里的链接由 `ReaderHtml.restoreEmbeds` 显示时换回播放器。iframe 按 width/height 定宽高比。
  - 虎嗅：视频文章 RSS 只有一句摘要，mp4 在原网页的 Nuxt 数据里。`content/PageVideo`：虎嗅文章正文很短、没有图片时自动抓原网页（`ReaderState.autoFullText`），全文 = `<video>`（720p + 封面）+ 网页简介，不走 Readability（会把 logo、“下载虎嗅APP”当正文）。
  - `WebViewPool`：开 DOM 存储（微博/B 站播放器没有 localStorage 就白屏）、允许第三方 Cookie、`FullscreenChrome`（全屏层盖在 decorView 上、隐藏系统栏、横屏，返回键退出）。`MainActivity` 加了 `configChanges`（转屏不重建）。正文上下留白改成 CSS 变量、变化时用脚本改（`ReaderHtml.paddingScript`），**不能放进 HTML 的 remember key**：全屏时系统栏隐藏、留白一变就重新载入，播放器立刻被踢出全屏。
  - 模拟器上实测：微博、B 站播放器和喷嚏音频卡片正常显示，虎嗅视频点了能播。系统第一次隐藏系统栏会弹“Viewing full screen”提示、抢焦点，测试里用 `settings put secure immersive_mode_confirmations confirmed` 跳过。

- **Runnel 1.0 已发布**（2026-10-23）：公开仓库的第一个正式 Release `v1.0`（附 `Runnel-1.0.apk`，正式签名 CN=Runnel）。包名改为 `io.github.xccxcxxc.runnel`，不能覆盖升级旧的 Brook 包。下文的 1.0.0–2.1 是私有仓库 `brook-private` 的旧版本号，对应功能都已包含在 Runnel 1.0。
- **2.1 已发布**（2026-10-20）：GitHub 正式 Release `v2.1`（https://github.com/xccxcxxc/brook-private/releases/tag/v2.1，附 `Brook-2.1.apk`，versionCode 74，正式签名，目标提交 `00904de`）；发布时手机没连，没用 adb 装。相对 2.0：离线图片按订阅源、图片缓存 500MB、srcset 挑图、进列表首帧优化、演示数据 200 篇。用户已在真机验证离线图片和首帧优化。
- **2.0 之后（已含在 2.1，测试包 `Brook-0.1.68.apk` 已装到用户手机）**（2026-10-20）：离线图片——设置 → 账号 → 离线图片按订阅源勾选（`Settings.offlineImageFeeds`，默认空）；`content/OfflineImages` 在每次同步成功后（`SyncEngine` 的 `afterSync`）和改勾选时，把勾选订阅源里未读和星标文章（`ArticleDao.offlineCandidateIds`）正文 html/fullHtml 的 `<img src>` 用 `ReaderImageCache.prefetch` 下进同一个缓存（上限 200→500MB），4 并发，连续失败 8 次当没网停下；`SyncWorker` 会等它下完。不限 Wi-Fi、不预抓全文（用户的 RSS 本身是全文）都是用户的决定。为了预下载和显示用同一个地址：同步时 `ArticleProcessor.pickSrcset` 从 srcset 挑一张（宽度描述取 ≥1200w 的最小一张）写进 src，`ReaderHtml` 显示时去掉所有 srcset。测试：单元测试（srcset 解析、预下载后断网命中、地址补全）、仪器测试 `OfflineImagesTest`（只下勾选订阅源的未读/星标、设置页勾选）。用户还没真机测。下一步：测“点进订阅源第一帧偏重”。
- **Baseline Profile 不用再生成**（2026-10-20 试过）：在 brook36 上用 BaselineProfileGenerator 生成了 1 万条规则（benchmark 构建要 -dontobfuscate 才是原始名），但编进 APK 的二进制配置文件和手写版几乎一样大（5.0 vs 5.4KB）——库自带的配置 + 手写的 App 通配规则已经覆盖；真机上整个 App 全编译也只让进列表最重一帧从 29ms 降到约 18ms。用户决定不要。生成一次要 6.6 分钟（每轮“滚动列表”20 秒都在 waitForIdle 等空闲）。另外：演示数据默认 5000 → 200 篇，且不再逐篇走 ArticleProcessor；AGP 9 的 `-P...class=A,B` 只认第一个类，`connected-test.ps1` 会拆开逐个跑。
- **跑测试**（2026-10-20）：仪器测试只用 `pwsh scripts/connected-test.ps1`（超过 10 分钟直接杀模拟器，用户硬性要求）；全套 23 个约 2 分钟（以前在别的项目开的无界面模拟器上要 17 分钟以上，冷启动 12 秒对 1.2 秒）。`brook36` 的 Android 36 镜像曾被删，已用 sdkmanager 重装（`system-images;android-36;google_apis;x86_64` r07）；其他项目也改用它。数据库测试演示数据 1200→200 篇（跨 500 分块的测试自己插 600 条不带正文的轻量文章），列表测试 300→150。`DatabaseTest.readsDoNotWaitForWriteTransaction` 以前在写事务里 `await()` 挂起，恢复到别的线程后结束事务报“没有事务”、写锁不放，后面的测试全卡死——已改成阻塞等待 + finally 放行；`inTransaction` 的注释写明块里不能真正挂起。
- **2.0 已发布**（2026-10-20）：GitHub 正式 Release `v2.0`（https://github.com/xccxcxxc/brook-private/releases/tag/v2.0，附 `Brook-2.0.apk`，versionCode 67，正式签名，目标提交 `53b4323`），已用 adb 装到用户手机。用户真机确认跳篇已修好；滑块、设置页右滑返回让位、离线图片缓存还没逐项真机确认。相对 1.12 的改动是下面三条。
- **滑块和上拉阈值**（2.0）：设置滑块换成自写的 iOS 样式 `IosSlider`（`Grouped.kt`，按在圆点上立即接管手势、松手吸附）；全屏右滑返回（`NavHost`、表单二级页 `Sheet.kt`）都在 Initial 阶段抢事件，所以加了 `SwipeBackGuard`（滑块按下时登记手指，返回手势看到就放手）和 `BlockSwipeBack`（阅读设置面板打开时整页停掉右滑返回）；列表底部上拉阈值 150dp → 120dp（用户嫌单手够不着）。只在 Robolectric 测过，用户还没真机测。
- **阅读页图片缓存**（2.0）：`WebViewPool` 的 `shouldInterceptRequest` 把图片请求（Accept 以 image/ 开头，或无 Accept 时按扩展名）交给 `content/ReaderImageCache`：OkHttp + 200MB `Cache`（`cacheDir/reader-images`），网络拦截器把响应改成 `max-age` 一年，服务器不让缓存也存；没网又没缓存返回 null 交回 WebView。只缓存打开过的文章，同步时不预下载。仪器测试 `ReaderTest.articleImagesGoThroughOwnCache` 验证真实 WebView 的图片请求会进缓存。用户还没真机测。
- **阅读页一闪跳到后面的文章**（2.0）：真机（三星 S24 Ultra）诊断日志确认：正文滚到深处（scrollY=35912）后按住图片，Chromium 让 WebView 第一次获得焦点，随后有人直接调用父容器（AndroidViewHolder）的 `requestChildRectangleOnScreen`，传入 WebView 的 drawing rect（顶部 = scrollY，不经过 `WebView.requestRectangleOnScreen`），Compose 不换算就转成 bringIntoView，翻页器（`userScrollEnabled = false` 也照样响应）从第 1 篇跳到第 4 篇。修法：`ReaderScreen` 里给 VerticalPager 提供 `LocalBringIntoViewSpec = NoBringIntoView`（滚动距离恒为 0），翻页只由我们自己的代码做。上一版在 WebView 上重写 `requestRectangleOnScreen` 的修法不够，已删。模拟器（Google 原生系统）上获得焦点不会触发那次调用，仪器测试 `ReaderTest.focusedRectFromParentWhileScrolledDoesNotSwitch` 直接重放它，修复前失败、修复后通过。查这类问题的办法：临时加 `Log` + `Throwable` 调用栈装到真机（手机常用 USB 连着这台电脑，`adb devices` 可见），用户复现后看 logcat；跑仪器测试时设 `ANDROID_SERIAL=emulator-5554`，否则会往手机装 debug 包。
- **1.12 已发布**（2026-10-20）：GitHub 正式 Release `v1.12`（https://github.com/xccxcxxc/brook-private/releases/tag/v1.12，附 `Brook-1.12.apk`，versionCode 56，正式签名，目标提交 `d7884f2`）。相对 1.11：阅读页拉动切篇也更粘（`ReaderScreen.kt` 的 `pullToSwitch`：橡皮筋输入只取手指距离的一半，阈值按手指距离 220dp，`ReaderState.pullProgress` 给提示用），提示换成大“∧/∨”。只在模拟器上测过，用户还没真机测。
- **1.11 已发布**（2026-10-20）：GitHub 正式 Release `v1.11`（https://github.com/xccxcxxc/brook-private/releases/tag/v1.11，附 `Brook-1.11.apk`，versionCode 53，正式签名，目标提交 `5e289c9`）。相对 1.1：列表从底部上拉更粘（`PullFromBottom.kt` 的 `StickyBottomOverscroll` 包住 Miuix 橡皮筋，到底后上拉只传一半位移；阈值按手指距离 150dp），上拉时底栏上方出现随拉动渐显的大“∨”/“✓”。只在模拟器上测过，用户还没真机测。注意：不能在 NestedScrollConnection 里消费位移来做粘滞，Miuix 见到上层消费过就几乎不拉伸。
- **1.1 已发布**（2026-10-19）：GitHub 正式 Release `v1.1`（https://github.com/xccxcxxc/brook-private/releases/tag/v1.1，附 `Brook-1.1.apk`，versionCode 50，正式签名，目标提交 `3e6b2d7`）。相对 1.0.0：长按浮动菜单、上拉进入下一个订阅源先标已读、返回列表不再现组合首页、帧统计按操作细分。这三项只在模拟器上测过，用户还没真机测。
- 1.0.0：Release `v1.0.0`（versionCode 41）。
- 全部在 `main`，没有其他分支。

## 2. 用户和工作方式（必须遵守）

- 用户用中文，要**明确的结论和进度**；拿不准时说概率/风险，出错直接认。
- 每个任务开始前先建议模型和思考强度（`CLAUDE.md` 模型策略）。不要为省额度把紧接着要用的活拆给便宜模型或别的会话（3.1 交给 Sonnet 建的模拟器不能用，返工更费）。
- **额度到 95% 才停**（不是 92%）：停之前写好本文件、全部推送。
- **自测通过后先出测试包**（`assembleRelease`）让用户测，再提交代码、写文档。
- 测试必须和生产一致，测到用户看到的最终结果；改交互的 bug 先写测试复现（修前失败）。
- 不做服务器联调；和服务器交互的功能给用户真机测试步骤。
- 交互继续追求 iOS 手感；新页面按用户给的描述或截图做（见 §0 不要放进仓库的东西）。不要 Bionic Reading。
- 用户的决定（2026-10-19）：从底部上拉 = “进入下一个订阅源”时**先把当前列表标为已读**（不询问；Filter 筛选中只标筛选结果）——这是用户的主意；GitHub CI **不跑模拟器**，仪器测试只在本地跑。

## 3. 发版本

- **只有用户明确说发布并给出版本号时才发**，版本号以用户给的为准，不自己推断或递增。
- `app/build.gradle.kts` 的 `versionName` 现在是 `1.12`（2026-10-24 发布；之后 CI 的 `latest` 测试包也显示 1.12，靠 versionCode = 200 + 提交数区分）；发下一版时改成用户给的版本号。
- 发法：改版本号提交推送 → 本地 `assembleRelease`、验签 → `gh release create v<版本> Runnel-<版本>.apk --title "Runnel <版本>" --notes-file <说明> --target <完整 SHA>`（短 SHA 会报 422）（正式版，不是 prerelease）。CI 只在推 `main` 时跑，不会覆盖 tag 的 Release。
- 更新说明写功能总览 + 相对上一版的改动 + 已知问题（1.0.0 的说明在 Release 页面）。

## 4. 构建、发布、测试（命令见 `CLAUDE.md`）

- 推 `main` → GitHub Actions（`.github/workflows/build.yml`）：单元测试 + release APK，发布到滚动的 `latest` prerelease；Release 说明 = 自上一个包以来的提交标题和正文。连续推送时旧构建被取消（“已取消”不是故障）。
- 签名：CI 用 Secrets（`RUNNEL_KEYSTORE_B64` / `RUNNEL_KEYSTORE_PASSWORD`），本地用 `%USERPROFILE%\.android\runnel-release.properties`，同一把证书（CN=Runnel）。**不要换证书、不要改包名**（上架 Play 后也改不了）；debug 签名的包不能给用户。
- 单元测试（`app/src/test`，Robolectric + 纯逻辑）：`ListFilterTest`、`ReaderViewTest`、`ReaderLinkTest`、`PullFromBottomTest`、`GReaderApiTest`（MockWebServer）、`OpmlTest`、`ArticleProcessorTest`、`FeedParserTest`、`FeedFetcherTest`（MockWebServer）等。
- 仪器测试建账号一律用 `TestAccounts.fresh(type)`（androidTest）：移除所有账号、加一个空账号、同步方式设为手动；数据库用 `account.db`，不要再找全局的。
- 模拟器仪器测试（`app/src/androidTest`，21 个）：`ListGesturesTest`（上拉/按钮标已读并离开、全已读直接离开、短列表回弹、下一个订阅源从下往上进场、上拉进入下一个订阅源时标已读、长按菜单位置和以上/以下标已读、菜单边界和点外面收起）、`ReaderTest`（快速一划不切篇、拉够才切、真实 WebView 点标题打开原文、表情尺寸、图片下不显示域名、“…”面板改设置即时生效、图片走自己的缓存、WebView 焦点/区域请求不翻页）、`DatabaseTest`（事务回滚、分块标已读、离线队列、WAL 并发读）。只跑一个类：加 `"-Pandroid.testInstrumentationRunnerArguments.class=io.github.xccxcxxc.brook.ReaderTest"`；结果在 `app\build\outputs\androidTest-results\connected\debug\`。
- 性能只用 release 包判断：真机见 `docs/DEVICE.md`，脚本 `scripts/perf.sh`。

## 5. 代码地图

- `data/Prefs.kt`：全 App 的 `Settings`、所有设置枚举、账号列表（`account.xml`）。**新增设置改四处**：`Settings` 字段、`updateSettings` 的 put、`readSettings` 的 get、设置页。只对某个账号有意义的设置（尤其按订阅源 id 存的）放 `data/Accounts.kt` 的 `AccountSettings`（`AccountPrefs.read/write`）。
- `AccountSession.kt`：一个账号的运行时；`Graph.session(id)` 取，`LocalAccount` 在页面里取。
- `data/db/RunnelDatabase.kt`：Room + 系统 SQLite；事务一律 `db.inTransaction { }`，块里只调 suspend 的 DAO。
- `ui/nav/`：自写页面栈（组合栈顶三层）。`push` 横向推入；`replaceTop`（下一个订阅源）从下往上进场（`NavEntry.enterFromBottom`），结束后移除旧页。
- `ui/components/LargeTitleList.kt`：大标题列表；内容不满一屏时末尾垫高 1px，让短列表也有回弹。
- `ui/list/ArticleListScreen.kt`：分组、缩略图、侧滑、长按菜单（`ui/components/ContextMenu.kt`，行的位置取 `layoutInfo` 的 `offset - viewportStartOffset`）、Filter、从底部上拉、全部标为已读（`leave()`：先导航再写库）。
- `ui/reader/`：`ReaderScreen`（自动隐藏栏、Reader View、拉动切篇）、`ReaderHtml`（CSS；标题链到 `runnel://open?u=`；表情图片按 src/alt 识别成行内；包着图片的链接不显示域名）、`WebViewPool`、`ReaderStylePanel`（“…”面板）。
- `ui/settings/`：`SettingsSheet`（全部设置页）、`ReaderStyle`（阅读样式控件，设置页和“…”面板共用）；组件在 `ui/components/Grouped.kt`。
- 设置项按用户给的截图；**猜的选项**（用户给图就改）：动画速度 0.7×/1×/1.5×/2.2×、阅读字体默认/衬线/等宽、图片预览关闭/小/中/大。**有意没做**：Unread count badge、List selection style、Landscape、Dim archived、Services/Support/About、Add to Reading List、Bionic Reading。

## 6. 待办

1. **偶发顿卡**：用户 2026-10-19 真机截图（0.1.42 之前的包）：总 0.10%，文章列表 15/14975 最慢 91 ms，首页 8/864 最慢 71 ms，阅读页 0/7068。当时页面名在新页面组合后才设置，进列表那一帧被算到“首页”。
   - 模拟器量法（JankStats 在模拟器上判不出卡顿，GPU 太慢；只看 UI 线程）：`adb shell dumpsys gfxinfo <包> reset` → 做一个操作 → `dumpsys gfxinfo <包> framestats`，每帧 UI 线程 = SyncQueued − AnimationStart（组合在 AnimationStart..PerformTraversalsStart，列表行的子组合/布局在 PerformTraversalsStart..SyncQueued）。
   - 量到的（release 包，模拟器 = 桌面 CPU，真机约 2–4 倍）：首页→列表推入，第 1–2 帧（滑入开始前的屏幕外组合）15–48 ms，文件夹最重；列表→阅读推入，前 3 帧 10–33 ms；阅读→列表返回，返回结束那一帧 14–20 ms（首页现组合）→ 已改成页面栈保留三层，降到 ≤ 8 ms；滚动、列表→首页都 < 4 ms。
   - 下一步：等用户发新的“按操作 / 最近卡顿”截图。如果主要是“推入”，就是进列表时首批行的组合/排版太重（每行约 2 ms 桌面 CPU），再查 `ArticleRow`（中文 3 行标题 + 2 行摘要的文字排版、缩略图/图标请求）。滚动帧 < 4 ms，列表的 `NestedScrollConnection` 不是热点；`Motion` getter、`LocalDensity` 没单独量，优先级低。
2. 首页右上角全局搜索还是旧页面（`ui/search/SearchScreen`），等用户给截图。
3. 用户没测过：表单手感、订阅源排序、OPML 导出、Reader View 自动全文、侧滑 6 种动作、Filter、退出登录、后台定时同步。
4. 手机的系统 SQLite 版本没核对（模拟器 3.44.3）。

## 7. 坑

- **Compose 回调不要读组合时的局部变量**（`val built = state.built`）：局部函数引用会被记住、不随重组更新（“上拉不标已读”的来历，SPEC 编码规则第 8 条）。
- Compose 列表只有能滚动时才回弹（`ScrollingLogic.shouldDispatchOverscroll`），所以有 `LargeTitleList` 的垫高。
- **SDK 位置**：2026-10-19 发现 SDK 所在目录和用户级环境变量 `ANDROID_HOME` 不一致过（会话里先核对并按实际位置设 `$env:ANDROID_HOME`）；当时 android-36 镜像缺失，用 sdkmanager 补装后 `brook36` 正常。
- **模拟器**：API 36.1 镜像在本机模拟器 37.2.12 上 SurfaceFlinger 反复崩溃（`hasReadColorBufferDma`），只用 API 36 的 `brook36`。别把模拟器挂成后台任务长期跑（用户会看到），用 `Start-Process` 起、用完 `adb emu kill`。刚开机 `boot_completed=1` 还不够，要等 `dumpsys user` 是 `RUNNING_UNLOCKED`。
- 测试里找阅读页 WebView 用 `isAttachedToWindow`，不要用 `isShown`；点面板里的控件前先 `waitForIdle()`（等升起动画结束）。
- Miniflux 添加订阅失败返回 HTTP 500 + `error_message`；默认拒绝订阅内网地址。
- “保留已读文章 = 不保留”会在下次同步删掉所有已读且未加星的文章。
- 列表“按订阅源分组”时，阅读页翻页顺序跟随分组后的顺序。
- `Navigator` 转场期间 `transitioning=true`，`push/pop/replaceTop` 会被忽略。
