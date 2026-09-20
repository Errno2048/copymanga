package top.fumiama.copymangaweb.activity

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.WindowInsetsController
import android.webkit.ValueCallback
import android.webkit.WebView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.lifecycle.lifecycleScope
import top.fumiama.copymangaweb.tool.ComicMetaStore
import top.fumiama.copymangaweb.tool.NovelOpenRequest
import top.fumiama.copymangaweb.tool.NovelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.fumiama.copymangaweb.BuildConfig
import top.fumiama.copymangaweb.tool.PageStack
import top.fumiama.copymangaweb.R
import top.fumiama.copymangaweb.activity.DlActivity.Companion.json
import top.fumiama.copymangaweb.activity.template.ToolsBoxActivity
import top.fumiama.copymangaweb.activity.viewmodel.MainViewModel
import top.fumiama.copymangaweb.databinding.ActivityMainBinding
import top.fumiama.copymangaweb.handler.MainHandler
import top.fumiama.copymangaweb.tool.InsetsTools
import top.fumiama.copymangaweb.tool.MangaDlTools.Companion.wmdlt
import top.fumiama.copymangaweb.tool.SetDraggable
import top.fumiama.copymangaweb.tool.Updater
import top.fumiama.copymangaweb.web.JSHidden
import top.fumiama.copymangaweb.web.JS
import top.fumiama.copymangaweb.web.Mirrors
import top.fumiama.copymangaweb.web.WebChromeClient
import java.lang.ref.WeakReference

class MainActivity: ToolsBoxActivity() {
    var uploadMessageAboveL: ValueCallback<Array<Uri>>? = null
    var saveUrlsOnly = false
    lateinit var mBinding: ActivityMainBinding
    private val mViewModel = MainViewModel()
    private var backInvokedCallback: OnBackInvokedCallback? = null
    @Volatile
    private var requestedDetailsUrl: String? = null
    /** FAB 点开哪个页面：漫画章节下载页 / 小说下载页 / 我的下载 */
    private var fabKind = FAB_COMIC

    @SuppressLint("JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        origStatusBarColor = window.statusBarColor
        origNavigationBarColor = window.navigationBarColor
        origWindowBackground = window.decorView.background
        mBinding = ActivityMainBinding.inflate(layoutInflater)
        mBinding.mainViewModel = mViewModel
        mBinding.lifecycleOwner = this
        setContentView(mBinding.root)
        InsetsTools.applySafeContentInsets(this, mBinding.root)
        // 页面渲染前显示的是 WebView 自身底色（默认白）；夜间模式改成黑，
        // 否则每次加载或新建页面都会先闪一下白底
        applyPageBackground()
        registerBackCallback()

        wm = WeakReference(this)
        mh = MainHandler(Looper.myLooper()!!)
        scrollHint = intent.getIntExtra(EXTRA_SCROLL_HINT, -1)
        PageStack.register(pageKey, isHomeInstance)
        instances[pageKey] = WeakReference(this)
        evictLruIfNeeded()
        toolsBox.netInfo.let {
            if(it == "无网络" || it == "错误") {
                setFab2DlList()
                return@let
            }

            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    goCheckUpdate(false)
                }
            }

            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
            Mirrors.init(this@MainActivity)
            mBinding.w.apply { post {
                setWebViewClient("i.js")
                webChromeClient = WebChromeClient()
                loadJSInterface(JS())
            } }

            mBinding.wh.apply { post {
                settings.userAgentString = getString(R.string.pc_ua)
                webChromeClient = WebChromeClient()
                setWebViewClient("h.js")
                loadJSInterface(JSHidden())
            } }

            // 指定了起始 URL（详情页等独立页面）：直接加载，不必再探测线路
            val start = intent.getStringExtra(EXTRA_START_URL)
            if (!start.isNullOrBlank()) {
                mBinding.w.post { mBinding.w.loadUrl(start) }
            } else {
                // 先短超时探测可用线路，再加载首页；否则会卡在失效线路上等 WebView 网络超时
                lifecycleScope.launch {
                    val target = withContext(Dispatchers.IO) { Mirrors.probe() }
                    mBinding.w.post { mBinding.w.loadUrl(target) }
                }
            }
        }
        SetDraggable().with(this).onto(mBinding.fab)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        navigateBack()
    }

    private fun registerBackCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        backInvokedCallback = OnBackInvokedCallback(::navigateBack).also {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                it
            )
        }
    }

    /**
     * 返回：优先按页面栈弹回上一个页面。
     * - 上个页面在本实例内：让页面原地跳转并恢复滚动位置；
     * - 上个页面在别的存活实例里：关掉当前实例，露出那一个（它的状态原样保留）；
     * - 那个实例已被 LRU 淘汰：带滚动位置重建该页面；
     * - 栈里没有上一页：不跳转，交回系统（退出）。
     */
    private fun navigateBack() {
        val now = System.currentTimeMillis()
        if (now - lastBackAt < 400) return          // 防抖，避免连按两次直接把页面弹掉
        lastBackAt = now
        val target = PageStack.popBack()
        if (target == null) {
            finishAfterTransition()
            return
        }
        if (target.key == pageKey) {
            runCatching {
                mBinding.w.evaluateJavascript(
                    "window.__cmNavigateTo&&window.__cmNavigateTo('" + target.route + "'," + target.scrollY + ")",
                    null
                )
            }
            return
        }
        val other = instances[target.key]?.get()
        if (other != null && other !== this) {
            other.scrollHint = target.scrollY       // 它自己还在，只需恢复滚动位置
            finishAfterTransition()
            return
        }
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(EXTRA_START_URL, target.route)
                .putExtra(EXTRA_SCROLL_HINT, target.scrollY)
        )
    }

    /** 实例数超过上限时淘汰最久未使用的非首页实例（它的栈条目会一并清掉）。 */
    private fun evictLruIfNeeded() {
        val victim = PageStack.lruVictim() ?: return
        if (victim == pageKey) return
        instances[victim]?.get()?.finishAfterTransition()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != FILE_CHOOSER_RESULT_CODE) return

        val callback = uploadMessageAboveL ?: return
        uploadMessageAboveL = null
        callback.onReceiveValue(if (resultCode == RESULT_OK) data?.selectedUris() else null)
    }

    private fun Intent.selectedUris(): Array<Uri>? {
        val uris = clipData?.let { clips ->
            Array(clips.itemCount) { index -> clips.getItemAt(index).uri }
        } ?: data?.let { arrayOf(it) }
        return uris?.takeIf { it.isNotEmpty() }
    }

    private suspend fun goCheckUpdate(ignoreSkip: Boolean) {
        Updater(
            WeakReference(this),
            toolsBox,
            ignoreSkip,
            getPreferences(MODE_PRIVATE).getInt("skipVersion", 0)
        ).check(BuildConfig.VERSION_CODE)
    }

    fun loadHiddenUrl(u: String) {
        requestedDetailsUrl = u
        mBinding.wh.apply { post { loadUrl(u) } }
    }

    fun updateLoadProgress(p: Int) {
        lifecycleScope.launch { mViewModel.updateLoadProgress(p) }
    }

    fun openStreamingManga(url: String) {
        ViewMangaActivity.streamChapterUrl = url
        ViewMangaActivity.titleText = "加载中..."
        ViewMangaActivity.nextChapterUrl = null
        ViewMangaActivity.previousChapterUrl = null
        ViewMangaActivity.imgUrls = arrayOf()
        ViewMangaActivity.zipFile = null
        startActivity(Intent(this, ViewMangaActivity::class.java))
    }

    fun setFab(content: String, sourceUrl: String, comicTitle: String) {
        if (content.isBlank() || content == "[]" || !isRequestedDetailsPage(sourceUrl)) return
        DlActivity.comicName = comicTitle
        // 详情页抓到的封面/作者（i.js 先经 rememberComicMeta 存好），按 pathWord 取出来给下载页
        runCatching {
            val pathWord = Uri.parse(sourceUrl).pathSegments.lastOrNull { it.isNotBlank() }
            DlActivity.comicMetaJson = pathWord?.let { ComicMetaStore.get(this, it) }
                ?.let { top.fumiama.copymangaweb.tool.NovelStore.gson().toJson(it) }
                ?: ""
        }
        // 记下 pathWord -> 漫画名：之后「續看」解析本地进度用它，不必再请求漫画接口
        runCatching {
            android.net.Uri.parse(sourceUrl).pathSegments
                .lastOrNull { it.isNotBlank() }
                ?.let { JS.rememberComicName(this, it, comicTitle) }
        }
        json = content
        fabKind = FAB_COMIC
        lifecycleScope.launch {
            withContext(Dispatchers.Main) {
                mViewModel.showDlList.value = false
                mViewModel.setFabVisibility(true)
            }
        }
    }

    fun setFab2DlList() {
        requestedDetailsUrl = null
        fabKind = FAB_DLLIST
        lifecycleScope.launch {
            withContext(Dispatchers.Main) {
                mViewModel.showDlList.value = true
                mViewModel.setFabVisibility(true)
            }
        }
    }

    fun hideFab() {
        requestedDetailsUrl = null
        lifecycleScope.launch { mViewModel.setFabVisibility(false) }
    }

    private fun isRequestedDetailsPage(sourceUrl: String): Boolean {
        val requestedPath = requestedDetailsUrl
            ?.let(Uri::parse)
            ?.path
            ?.trimEnd('/')
            ?: return false
        val sourcePath = Uri.parse(sourceUrl).path?.trimEnd('/') ?: return false
        return requestedPath == sourcePath
    }

    fun onFabClicked(v: View) {
        DlListActivity.currentDir = getExternalFilesDir("")
        val target = when (fabKind) {
            FAB_NOVEL -> NovelDlActivity::class.java
            FAB_DLLIST -> DlListActivity::class.java
            else -> DlActivity::class.java
        }
        startActivity(Intent(this, target).putExtra("title", "我的下载"))
    }

    /**
     * 小说详情页的下载按钮（i.js 回传元信息）：与漫画完全一致 ——
     * 侧边同一个 FAB，点进去是原生下载页（可选择下载/删除若干卷）。
     */
    fun setNovelFab(metaJson: String) {
        val req = runCatching {
            NovelStore.gson().fromJson(metaJson, NovelOpenRequest::class.java)
        }.getOrNull() ?: return
        if (req.name.isBlank()) return
        NovelStore.merge(this, req)
        NovelDlActivity.bookNameArg = req.name
        fabKind = FAB_NOVEL
        lifecycleScope.launch {
            withContext(Dispatchers.Main) {
                mViewModel.showDlList.value = false
                mViewModel.setFabVisibility(true)
            }
        }
    }

    fun openImageChooserActivity(callback: ValueCallback<Array<Uri>>) {
        uploadMessageAboveL?.onReceiveValue(null)
        uploadMessageAboveL = callback
        startActivityForResult(
            Intent.createChooser(
                Intent(Intent.ACTION_GET_CONTENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("image/*")
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false),
                "Image Chooser"
            ), FILE_CHOOSER_RESULT_CODE
        )
    }

    fun callViewManga(content: String) {
        lifecycleScope.launch { withContext(Dispatchers.IO) {
            val listChapter = content.split('\n')
            if (listChapter.size < CHAPTER_METADATA_LINE_COUNT) return@withContext
            val images = listChapter.drop(CHAPTER_METADATA_LINE_COUNT).toTypedArray()
            if(!saveUrlsOnly) {
                ViewMangaActivity.titleText = listChapter[0].substringBeforeLast(' ')
                ViewMangaActivity.nextChapterUrl = listChapter[1].let { if(it == "null") null else it }
                ViewMangaActivity.previousChapterUrl = listChapter[2].let { if(it == "null") null else it }
                ViewMangaActivity.imgUrls = images
                withContext(Dispatchers.Main) {
                    startActivity(Intent(this@MainActivity, ViewMangaActivity::class.java))
                }
            } else {
                wmdlt?.get()?.setChapterImages(listChapter[0].substringAfterLast(' '), images)
            }
        } }
    }

    /** 本实例在页面栈里的标识；无 EXTRA_START_URL 的实例是首页实例（不参与淘汰）。 */
    /** 页面栈用的实例标识（JS 桥上报时要用）。 */
    val pageKey = "page-" + (++pageSeq)
    private val isHomeInstance: Boolean
        get() = intent.getStringExtra(EXTRA_START_URL).isNullOrBlank()
    /** 被淘汰后重建时要恢复的滚动位置（-1 表示不恢复） */
    private var scrollHint = -1
    private var lastBackAt = 0L

    private var origStatusBarColor: Int = 0
    private var origWindowBackground: Drawable? = null
    private var origNavigationBarColor: Int = 0

    override fun onResume() {
        super.onResume()
        // 详情页等独立页面会叠在列表页之上，返回后这里重新登记，
        // 保证原生桥（打开阅读器/下载）始终指向当前可见的那个页面。
        wm = WeakReference(this)
        PageStack.touch(pageKey)
        applyNightBars()
        notifyPageShown()
    }

    /** 页面重新可见：让页面检查 dirty 是否需要刷新，并恢复被重建时记录的位置。 */
    private fun notifyPageShown() {
        val hint = scrollHint
        scrollHint = -1
        mBinding.w.postDelayed({
            runCatching {
                mBinding.w.evaluateJavascript(
                    "window.__cmOnShow&&window.__cmOnShow(" + hint + ")", null
                )
            }
        }, 600)
    }

    /** WebView 与窗口底色跟随夜间模式，避免加载期间露出默认白底。 */
    private fun applyPageBackground() {
        val night = getSharedPreferences(JS.NIGHT_PREF, MODE_PRIVATE)
            .getBoolean(JS.NIGHT_KEY, false)
        val bg = if (night) Color.BLACK else Color.WHITE
        runCatching {
            mBinding.w.setBackgroundColor(bg)
            mBinding.wh.setBackgroundColor(bg)
            window.setBackgroundDrawable(ColorDrawable(bg))
        }
    }

    /** 夜间模式下把系统状态栏/导航栏也改成黑色，避免顶部与底部出现白边。 */
    private fun applyNightBars() {
        applyPageBackground()
        val on = getSharedPreferences(JS.NIGHT_PREF, MODE_PRIVATE).getBoolean(JS.NIGHT_KEY, false)
        window.statusBarColor = if (on) Color.BLACK else origStatusBarColor
        // 窗口底色也要跟着变：页面若有透明缝隙（如滚动条区域），否则会露出主题的白色
        window.decorView.background = if (on) ColorDrawable(Color.BLACK) else origWindowBackground
        window.navigationBarColor = if (on) Color.BLACK else origNavigationBarColor
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (on) 0 else mask, mask)
        } else {
            @Suppress("DEPRECATION")
            var flags = window.decorView.systemUiVisibility
            flags = if (on) {
                flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            } else {
                flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = flags
        }
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backInvokedCallback?.let(onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
        }
        backInvokedCallback = null
        uploadMessageAboveL?.onReceiveValue(null)
        uploadMessageAboveL = null
        mBinding.w.destroy()
        mBinding.wh.destroy()
        mh?.dispose()
        mh = null
        if (wm?.get() === this) wm = null
        PageStack.unregister(pageKey)
        instances.remove(pageKey)
        super.onDestroy()
    }

    companion object {
        /** 由 JS 桥传入：新实例直接加载这个 URL（用于「详情页另开一页」）。 */
        const val EXTRA_START_URL = "start_url"
        /** FAB 点开哪个页面 */
        const val FAB_COMIC = 0
        const val FAB_NOVEL = 1
        const val FAB_DLLIST = 2
        /** 被淘汰后重建时传入要恢复的滚动位置。 */
        const val EXTRA_SCROLL_HINT = "scroll_hint"
        private const val FILE_CHOOSER_RESULT_CODE = 1
        private var pageSeq = 0
        /** 存活实例表：页面栈据此判断目标页面是否还活着 */
        val instances = LinkedHashMap<String, WeakReference<MainActivity>>()
        private const val CHAPTER_METADATA_LINE_COUNT = 3
        var wm: WeakReference<MainActivity>? = null
        var mh: MainHandler? = null
    }
}