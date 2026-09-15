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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.fumiama.copymangaweb.BuildConfig
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
        registerBackCallback()

        wm = WeakReference(this)
        mh = MainHandler(Looper.myLooper()!!)
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

            // 先短超时探测可用线路，再加载首页；否则会卡在失效线路上等 WebView 网络超时
            lifecycleScope.launch {
                val target = withContext(Dispatchers.IO) { Mirrors.probe() }
                mBinding.w.post { mBinding.w.loadUrl(target) }
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

    private fun navigateBack() {
        if (mBinding.w.canGoBack()) mBinding.w.goBack()
        else finishAfterTransition()
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
        // 记下 pathWord -> 漫画名：之后「續看」解析本地进度用它，不必再请求漫画接口
        runCatching {
            android.net.Uri.parse(sourceUrl).pathSegments
                .lastOrNull { it.isNotBlank() }
                ?.let { JS.rememberComicName(this, it, comicTitle) }
        }
        json = content
        lifecycleScope.launch {
            withContext(Dispatchers.Main) {
                mViewModel.showDlList.value = false
                mViewModel.setFabVisibility(true)
            }
        }
    }

    fun setFab2DlList() {
        requestedDetailsUrl = null
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
        startActivity(
            Intent(this, (if(mViewModel.showDlList.value == true) DlListActivity::class else DlActivity::class).java)
                .putExtra("title", "我的下载")
        )
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

    private var origStatusBarColor: Int = 0
    private var origWindowBackground: Drawable? = null
    private var origNavigationBarColor: Int = 0

    override fun onResume() {
        super.onResume()
        applyNightBars()
    }

    /** 夜间模式下把系统状态栏/导航栏也改成黑色，避免顶部与底部出现白边。 */
    private fun applyNightBars() {
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
        super.onDestroy()
    }

    companion object {
        private const val FILE_CHOOSER_RESULT_CODE = 1
        private const val CHAPTER_METADATA_LINE_COUNT = 3
        var wm: WeakReference<MainActivity>? = null
        var mh: MainHandler? = null
    }
}