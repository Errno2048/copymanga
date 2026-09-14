package top.fumiama.copymangaweb.activity

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomViewTarget
import com.bumptech.glide.request.transition.Transition
import top.fumiama.copymangaweb.R
import top.fumiama.copymangaweb.activity.reader.ContinuousMangaAdapter
import top.fumiama.copymangaweb.activity.reader.InvertTone
import top.fumiama.copymangaweb.activity.reader.PagedMangaAdapter
import top.fumiama.copymangaweb.activity.reader.ReaderOverlayController
import top.fumiama.copymangaweb.activity.MainActivity.Companion.wm
import top.fumiama.copymangaweb.activity.template.ToolsBoxActivity
import top.fumiama.copymangaweb.databinding.ActivityViewmangaBinding
import top.fumiama.copymangaweb.tool.PropertiesTools
import top.fumiama.copymangaweb.tool.ReadingProgress
import top.fumiama.copymangaweb.tool.PagesManager
import top.fumiama.copymangaweb.view.ScaleImageView
import top.fumiama.copymangaweb.web.JSHidden
import top.fumiama.copymangaweb.web.JS
import top.fumiama.copymangaweb.web.WebChromeClient
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import java.util.zip.ZipFile

class ViewMangaActivity : ToolsBoxActivity() {
    lateinit var mBinding: ActivityViewmangaBinding

    var count = 0
    var clicked = false
    var r2l = true
    var infoDrawerDelta = 0f

    private var dialog: Dialog? = null
    private lateinit var p: PropertiesTools
    private var isInSeek = false
    private var currentItem = 0
    private var notUseVP = true
    private var verticalReading = false
    private var mangaZip = zipFile
    val dlZip2View = mangaZip != null
    private var streamUrl = streamChapterUrl
    private var readerPrepared = false
    private var streamFinished = false
    private var streamDeclaredCount = 0
    private var userRequestedExit = false
    // 正在切换章节：重启 Activity 时不要把 skipBackOnExit 复位，交给新实例
    private var switchingChapter = false
    private var backInvokedCallback: OnBackInvokedCallback? = null
    private val streamSeenUrls = LinkedHashSet<String>()
    private var pagedAdapter: PagedMangaAdapter? = null
    private var continuousAdapter: ContinuousMangaAdapter? = null
    private val imageExecutor = Executors.newFixedThreadPool(2)
    private lateinit var overlayController: ReaderOverlayController
    private val pagesManager by lazy { PagesManager(WeakReference(this)) }
    private val volTurnPage get() = p["volturn"] == "true"
    private val readerMode: ReaderMode
        get() = when {
            verticalReading -> ReaderMode.CONTINUOUS
            notUseVP -> ReaderMode.SINGLE_PAGE
            else -> ReaderMode.PAGED
        }
    var pageNum: Int
        get() = getPageNumber()
        set(value) {
            setPageNumber(value, smoothScroll = true)
            if (readerMode == ReaderMode.SINGLE_PAGE) {
                try {
                    loadOneImg()
                } catch (e: java.lang.Exception) {
                    e.printStackTrace()
                    toolsBox.toastError("页数${currentItem}不合法")
                }
            }
        }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mBinding = ActivityViewmangaBinding.inflate(layoutInflater)
        setContentView(mBinding.root)
        registerBackCallback()
        applyNightMode()
        va = WeakReference(this)
        p = PropertiesTools(File("$filesDir/settings.properties"))
        r2l = p["r2l"] == "true"
        notUseVP = p["noAnimation"] == "true"
        verticalReading = p["vertical"] == "true"
        overlayController = ReaderOverlayController(
            activity = this,
            binding = mBinding,
            toolsBox = toolsBox,
            drawerOffset = { infoDrawerDelta }
        ).also { it.start() }
        dialog = Dialog(this)
        dialog?.apply {
            setContentView(R.layout.dialog_unzipping)
            show()
        }
        mBinding.oneinfo.inftitle.ttitle.apply { post { text = titleText } }
        Log.d("MyVM", "dlZip2View: $dlZip2View, mangaZip: $mangaZip, streamUrl: $streamUrl")
        restoreReadingProgress()
        if(dlZip2View && mangaZip?.exists() != true) toolsBox.toastError("已经到头了~")
        else if(!dlZip2View && !streamUrl.isNullOrBlank()) startStreamingCollector(streamUrl!!)
        else Thread {
            val initialCount = try {
                if (dlZip2View) countZipItems() else imgUrls.size
            } catch (e: Exception) {
                e.printStackTrace()
                0
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                count = initialCount
                if (initialCount == 0) toolsBox.toastError("分析图片url错误")
                prepareReaderIfNeeded()
            }
        }.start()
    }


    private fun startStreamingCollector(url: String) {
        Log.d("CopymangaDL", "reader collector load url=$url")
        mBinding.wcollector.apply { post {
            settings.userAgentString = getString(R.string.pc_ua)
            webChromeClient = WebChromeClient()
            setWebViewClient("h.js")
            loadJSInterface(
                JSHidden(
                    onLoadChapter = { onStreamingChapterFinished(it) },
                    onChapterMeta = { onStreamingMeta(it) },
                    onAppendImages = { appendStreamingImages(it) },
                    onFinishStreaming = { onStreamingFinished() },
                    onChapterCount = { onStreamingCount(it) },
                    enableLoadingDialog = false
                )
            )
            loadUrl(url)
        } }
    }

    private fun onStreamingMeta(headerString: String) {
        val lines = headerString.split('\n')
        if (lines.size < 3) return
        titleText = lines[0].substringBeforeLast(' ')
        nextChapterUrl = lines[1].let { if(it == "null") null else it }
        previousChapterUrl = lines[2].let { if(it == "null") null else it }
        runOnUiThread {
            mBinding.oneinfo.inftitle.ttitle.text = titleText
            updateChapterNavState()
        }
    }
    private fun setEarlyTapLayerEnabled(enabled: Boolean) {
        mBinding.onec.apply { post {
            isClickable = enabled
            isFocusable = false
            setOnClickListener(if (enabled) View.OnClickListener {
                if (clicked) hideSettings() else showSettings()
            } else null)
        } }
    }

    private fun seekProgressToPage(progress: Int): Int {
        if (count <= 1) return 1
        return ((progress.coerceIn(0, 100) * (count - 1) + 50) / 100 + 1).coerceIn(1, count)
    }

    private fun onStreamingCount(total: Int) {
        if (total <= 0) return
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            val oldCount = count
            streamDeclaredCount = max(streamDeclaredCount, total)
            count = max(count, streamDeclaredCount)
            if (!readerPrepared) {
                prepareReaderIfNeeded()
            } else {
                if (readerMode != ReaderMode.SINGLE_PAGE && count > oldCount) {
                    notifyPagesInserted(oldCount, count - oldCount)
                }
                syncSeekBarOnly()
            }
        }
    }

    private fun onStreamingChapterFinished(content: String) {
        val listChapter = content.split('\n')
        if (listChapter.size >= 3) onStreamingMeta(listChapter.take(3).joinToString("\n"))
        appendStreamingImages(listChapter.drop(3).filter { it.isNotBlank() }.toTypedArray())
        onStreamingFinished()
    }

    private fun onStreamingFinished() {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            streamFinished = true
            Log.d(
                "CopymangaDL",
                "reader stream finished declared=$streamDeclaredCount received=${imgUrls.size}"
            )
            prepareReaderIfNeeded()
            if (readerPrepared) {
                dialog?.dismiss()
                dialog = null
                syncSeekBarOnly()
            }
        }
    }

    private fun appendStreamingImages(urls: Array<String>) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            val oldCount = count
            val oldImageCount = imgUrls.size
            var added = 0
            for (url in urls) {
                if (streamSeenUrls.add(url)) {
                    imgUrls += url
                    added++
                }
            }
            if (added <= 0) return@runOnUiThread
            count = max(max(streamDeclaredCount, imgUrls.size), count)
            Log.d("CopymangaDL", "reader appended images added=$added total=$count images=${imgUrls.size}")
            if (!readerPrepared) prepareReaderIfNeeded()
            else {
                if (readerMode == ReaderMode.SINGLE_PAGE) {
                    if (currentItem in oldImageCount until imgUrls.size) loadOneImg(hidePanel = false)
                    else syncSeekBarOnly()
                } else {
                    if (count > oldCount) notifyPagesInserted(oldCount, count - oldCount)
                    notifyVisiblePagesIfArrived(oldImageCount, imgUrls.size)
                    continuousAdapter?.notifyItemRangeChanged(
                        oldImageCount,
                        imgUrls.size - oldImageCount
                    )
                    syncSeekBarOnly()
                }
                preloadAround(getLogicalCurrentItem())
            }
        }
    }

    private fun prepareReaderIfNeeded() {
        if (
            readerPrepared ||
            count <= 0 ||
            isFinishing ||
            isDestroyed ||
            shouldWaitForLastStreamingPage()
        ) return
        readerPrepared = true
        try {
            prepareItems()
            setPageNumber(consumeRequestedPage(), smoothScroll = false)
            if (readerMode == ReaderMode.SINGLE_PAGE) loadOneImg()
            else syncSeekBarOnly()
        } catch (e: Exception) {
            readerPrepared = false
            e.printStackTrace()
            toolsBox.toastError("准备控件错误")
        } finally {
            if (streamUrl == null || count > 0) {
                dialog?.dismiss()
                dialog = null
            }
        }
    }

    private fun shouldWaitForLastStreamingPage(): Boolean {
        if (streamUrl == null || pn != LAST_PAGE) return false
        val allDeclaredPagesReceived =
            streamDeclaredCount <= 0 || imgUrls.size >= streamDeclaredCount
        return !streamFinished || !allDeclaredPagesReceived
    }

    private fun consumeRequestedPage(): Int {
        val requestedPage = when {
            pn == LAST_PAGE -> count
            pn > 0 -> pn.coerceAtMost(count)
            else -> 1
        }
        pn = FIRST_PAGE
        return requestedPage
    }

    private fun preloadAround(position: Int) {
        if (dlZip2View || position < 0 || imgUrls.isEmpty()) return
        val end = min(imgUrls.size - 1, position + 4)
        for (i in position + 1..end) {
            imgUrls.getOrNull(i)?.let { url ->
                Glide.with(this@ViewMangaActivity)
                    .load(toolsBox.resolution.wrap(url))
                    .preload()
            }
        }
    }

    private fun getLogicalCurrentItem(): Int {
        return when (readerMode) {
            ReaderMode.SINGLE_PAGE, ReaderMode.CONTINUOUS -> currentItem
            ReaderMode.PAGED -> mBinding.vp.currentItem
        }
    }

    private fun notifyVisiblePagesIfArrived(oldImageCount: Int, newImageCount: Int) {
        if (readerMode != ReaderMode.PAGED || oldImageCount >= newImageCount || count <= 0) return
        val center = getLogicalCurrentItem().coerceIn(0, count - 1)
        val from = max(0, center - 1)
        val to = min(newImageCount - 1, center + 1)
        for (logicalPosition in from..to) {
            if (logicalPosition >= oldImageCount) {
                pagedAdapter?.notifyItemChanged(logicalPosition)
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) window.setDecorFitsSystemWindows(false)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (volTurnPage) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> pagesManager.goBackward()
                KeyEvent.KEYCODE_VOLUME_DOWN -> pagesManager.goForward()
                else -> return super.onKeyDown(keyCode, event)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        exitByUserRequest()
    }

    private fun registerBackCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        backInvokedCallback = OnBackInvokedCallback(::exitByUserRequest).also { callback ->
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                callback
            )
        }
    }

    private fun exitByUserRequest() {
        userRequestedExit = true
        finishAfterTransition()
    }

    /** 相邻章节是否可切换，用于按钮置灰。 */
    private fun hasAdjacentChapter(goNext: Boolean): Boolean = if (dlZip2View) {
        val list = zipList.orEmpty()
        list.getOrNull(zipPosition + if (goNext) 1 else -1) != null
    } else {
        (if (goNext) nextChapterUrl else previousChapterUrl) != null
    }

    /** 底部一行：上一章 / 下一章 / 反色。日漫模式(从右往左)下左右按钮的文案与行为互换。 */
    private fun prepareChapterNav() {
        mBinding.oneinfo.btprevchapter.setOnClickListener { gotoAdjacentChapter(r2l) }
        mBinding.oneinfo.btnextchapter.setOnClickListener { gotoAdjacentChapter(!r2l) }
        mBinding.oneinfo.btinvert.setOnClickListener { cycleInvertMode() }
        applyChapterNavOrder()
        updateInvertButton()
        updateChapterNavState()
    }

    private fun applyChapterNavOrder() {
        mBinding.oneinfo.btprevchapter.setText(if (r2l) R.string.next_chapter else R.string.prev_chapter)
        mBinding.oneinfo.btnextchapter.setText(if (r2l) R.string.prev_chapter else R.string.next_chapter)
    }

    private fun updateChapterNavState() {
        val hasPrev = hasAdjacentChapter(false)
        val hasNext = hasAdjacentChapter(true)
        // 右侧按钮在 LTR 下是「下一章」、在日漫模式下是「上一章」，可用性要跟着语义走
        mBinding.oneinfo.btprevchapter.isEnabled = if (r2l) hasNext else hasPrev
        mBinding.oneinfo.btnextchapter.isEnabled = if (r2l) hasPrev else hasNext
    }

    /** 阅读器内的反色快捷开关：与网页设置共用同一份存储。 */
    private fun cycleInvertMode() {
        val next = when (invertMode) {
            "off" -> "auto"
            "auto" -> "on"
            else -> "off"
        }
        getSharedPreferences(JS.NIGHT_PREF, MODE_PRIVATE).edit()
            .putString(JS.INVERT_KEY, next).apply()
        // 采集用 WebView 与主页面同源，可直接同步网页端的 localStorage
        mBinding.wcollector.post {
            runCatching {
                mBinding.wcollector.evaluateJavascript("localStorage.setItem('cm_invert','$next')", null)
            }
        }
        lutCache = null
        lutCacheKey = ""
        updateInvertButton()
        refreshPages()
    }

    private fun updateInvertButton() {
        mBinding.oneinfo.btinvert.setText(
            when (invertMode) {
                "on" -> R.string.reader_invert_on
                "auto" -> R.string.reader_invert_auto
                else -> R.string.reader_invert_off
            }
        )
    }

    /**
     * 直接切换到相邻章节：准备好该章的启动参数后重启本 Activity，
     * 复用既有的初始化/流式收集流程，无需退回详情页再进入。
     */
    fun gotoAdjacentChapter(goNext: Boolean) {
        if (dlZip2View) {
            val newPosition = zipPosition + if (goNext) 1 else -1
            val chapter = zipList.orEmpty().getOrNull(newPosition)
            if (chapter == null) {
                Toast.makeText(this, "已经到头了~", Toast.LENGTH_SHORT).show()
                return
            }
            zipPosition = newPosition
            titleText = chapter.nameWithoutExtension
            zipFile = chapter
        } else {
            val url = if (goNext) nextChapterUrl else previousChapterUrl
            if (url.isNullOrBlank()) {
                Toast.makeText(this, "已经到头了~", Toast.LENGTH_SHORT).show()
                return
            }
            streamChapterUrl = url
            titleText = "加载中..."
            nextChapterUrl = null
            previousChapterUrl = null
            imgUrls = arrayOf()
            zipFile = null
        }
        pn = if (goNext) FIRST_PAGE else LAST_PAGE
        switchingChapter = true
        startActivity(Intent(this, ViewMangaActivity::class.java))
        finish()
    }

    /** 夜间模式：由设置页开关经 JS 桥写入偏好，这里把阅读器底色改为黑色。 */
    private val isNightMode: Boolean
        get() = getSharedPreferences(JS.NIGHT_PREF, MODE_PRIVATE).getBoolean(JS.NIGHT_KEY, false)

    /** 反色模式：off / auto（自动识别黑白页）/ on。 */
    private val invertMode: String
        get() = getSharedPreferences(JS.NIGHT_PREF, MODE_PRIVATE).getString(JS.INVERT_KEY, "off") ?: "off"

    /** 反色线条补偿增益（1.0 = 不补偿）。 */
    private var invertGain: Float
        get() = getSharedPreferences(JS.NIGHT_PREF, MODE_PRIVATE).getFloat(JS.INVERT_GAIN_KEY, 1f)
        set(value) {
            getSharedPreferences(JS.NIGHT_PREF, MODE_PRIVATE).edit()
                .putFloat(JS.INVERT_GAIN_KEY, value).apply()
        }

    /** 反色补偿的黑场：压掉纸面残留，使背景保持纯黑。 */
    private var invertBlack: Float
        get() = getSharedPreferences(JS.NIGHT_PREF, MODE_PRIVATE).getFloat(JS.INVERT_BLACK_KEY, 0f)
        set(value) {
            getSharedPreferences(JS.NIGHT_PREF, MODE_PRIVATE).edit()
                .putFloat(JS.INVERT_BLACK_KEY, value).apply()
        }

    private var lutCache: IntArray? = null
    private var lutCacheKey = ""

    private fun toneLut(): IntArray {
        val g = invertGain
        val b = invertBlack
        val key = "$g/$b"
        lutCache?.let { if (lutCacheKey == key) return it }
        return InvertTone.buildLut(b, g).also {
            lutCache = it
            lutCacheKey = key
        }
    }

    /**
     * 抽样判断这一页是否偏彩色。黑白扫描页三通道差异很小，彩色页则有相当比例的像素存在明显色差。
     * 必须在**源位图**上判定：补偿曲线在墨量趋近 0 处斜率很大，会放大噪声导致误判。
     */
    private fun isColorful(bmp: Bitmap): Boolean {
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return true
        val step = maxOf(1, minOf(w, h) / 120)
        var total = 0
        var colorful = 0
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                val p = bmp.getPixel(x, y)
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (maxOf(r, g, b) - minOf(r, g, b) > 40) colorful++
                total++
                x += step
            }
            y += step
        }
        return total == 0 || colorful.toFloat() / total >= 0.02f
    }

    private fun shouldInvert(bmp: Bitmap): Boolean = when (invertMode) {
        "on" -> true
        "auto" -> !isColorful(bmp)
        else -> false
    }

    /** 把源位图按当前设置画到页面上；不修改源位图（避免污染 Glide 缓存）。 */
    private fun displayPage(view: ImageView, bmp: Bitmap?) {
        if (bmp == null) {
            view.setImageResource(R.drawable.ic_dl)
            return
        }
        if (shouldInvert(bmp)) view.setImageBitmap(InvertTone.apply(bmp, toneLut()))
        else view.setImageBitmap(bmp)
    }

    /** 补偿参数变化后重绘当前页面。 */
    private fun refreshPages() {
        lutCache = null
        lutCacheKey = ""
        when (readerMode) {
            ReaderMode.SINGLE_PAGE -> runCatching { loadOneImg() }
            ReaderMode.PAGED -> if (count > 0) pagedAdapter?.notifyItemRangeChanged(0, count)
            ReaderMode.CONTINUOUS -> if (count > 0) continuousAdapter?.notifyItemRangeChanged(0, count)
        }
    }

    /** 网络图片：手动接管 Glide 的交付，以便在设置前拿到源位图做检测与补偿。 */
    private fun loadNetworkInto(imageView: ImageView, url: String) {
        Glide.with(this)
            .asBitmap()
            .load(toolsBox.resolution.wrap(url))
            .placeholder(R.drawable.ic_dl)
            .dontAnimate()
            .into(object : CustomViewTarget<ImageView, Bitmap>(imageView) {
                override fun onResourceLoading(placeholder: Drawable?) {
                    imageView.setImageDrawable(placeholder)
                }

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    imageView.setImageResource(R.drawable.ic_dl)
                }

                override fun onResourceCleared(placeholder: Drawable?) {
                    imageView.setImageDrawable(placeholder)
                }

                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    displayPage(imageView, resource)
                }
            })
    }

    private fun applyNightMode() {
        if (!isNightMode) return
        val black = Color.BLACK
        val fg = Color.parseColor("#D8D8D8")
        mBinding.vcp.setBackgroundColor(black)
        mBinding.vone.root.setBackgroundColor(black)
        mBinding.vp.setBackgroundColor(black)
        mBinding.continuousPages.setBackgroundColor(black)
        mBinding.oneinfo.infseekrow.setBackgroundResource(R.drawable.rndbg_dark)
        mBinding.oneinfo.inftxtprogress.setTextColor(fg)
        mBinding.oneinfo.inftitle.titlecard.setCardBackgroundColor(Color.parseColor("#1C1C1C"))
        mBinding.oneinfo.inftitle.ttitle.setTextColor(fg)
        mBinding.infcard.idc.setCardBackgroundColor(Color.parseColor("#1C1C1C"))
    }

    /** 上一次读到的位置：同一章直接回到该页。 */
    private fun restoreReadingProgress() {
        val pathWord = chapterPathWord() ?: return
        val chapterId = chapterId() ?: return
        if (pn != FIRST_PAGE) return          // 调用方已指定起始页（如切章）
        val saved = ReadingProgress.comic(this, pathWord) ?: return
        if (saved.chapterId != chapterId || saved.page <= 0) return
        pn = saved.page
        Log.d("MyVM", "resume at page ${saved.page} of ${saved.chapterName}")
    }

    private fun saveReadingProgress() {
        val pathWord = chapterPathWord() ?: return
        val chapterId = chapterId() ?: return
        val page = runCatching { getPageNumber() }.getOrDefault(0)
        if (page <= 0) return
        ReadingProgress.saveComic(this, pathWord, chapterId, titleText, page, count)
        Log.d("MyVM", "save progress $pathWord/$chapterId page=$page/$count")
    }

    private fun chapterPathWord(): String? =
        streamUrl?.let { Regex("/comic/([^/]+)/chapter/").find(it)?.groupValues?.getOrNull(1) }

    private fun chapterId(): String? =
        streamUrl?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

    override fun onPause() {
        super.onPause()
        if (!dlZip2View) saveReadingProgress()
    }

    private fun getPageNumber(): Int {
        return when (readerMode) {
            ReaderMode.SINGLE_PAGE, ReaderMode.CONTINUOUS -> currentItem + 1
            ReaderMode.PAGED -> mBinding.vp.currentItem + 1
        }
    }

    private fun setPageNumber(num: Int, smoothScroll: Boolean) {
        val target = (num - 1).coerceIn(0, (count - 1).coerceAtLeast(0))
        when (readerMode) {
            ReaderMode.SINGLE_PAGE -> currentItem = target
            ReaderMode.CONTINUOUS -> {
                currentItem = target
                mBinding.continuousPages.scrollToPosition(target)
            }
            ReaderMode.PAGED -> mBinding.vp.setCurrentItem(
                target,
                smoothScroll
            )
        }
    }

    private fun getImgBitmap(position: Int): Bitmap? {
        if (position >= count || position < 0) return null
        val zipPath = mangaZip ?: return null
        return try {
            ZipFile(zipPath).use { zip ->
                // Older downloaded zips use 0.webp, 1.webp...
                // The large-chapter fix may use 000.webp, 001.webp... for stable sorting.
                // Support both formats and fall back to the sorted entry list.
                val entry = zip.getEntry("${position}.webp")
                    ?: zip.getEntry("%03d.webp".format(position))
                    ?: zip.entries().asSequence()
                        .filter { !it.isDirectory }
                        .sortedBy { it.name }
                        .elementAtOrNull(position)
                    ?: run {
                        Log.e("CopymangaDL", "zip entry missing position=$position file=$zipPath")
                        return null
                    }
                zip.getInputStream(entry).use { input ->
                    BitmapFactory.decodeStream(input)
                }
            }
        } catch (e: Exception) {
            Log.e("CopymangaDL", "decode zip image failed position=$position file=$zipPath", e)
            null
        }
    }

    private fun loadOneImg(hidePanel: Boolean = true) {
        mBinding.vone.onei.resetImageTransform()
        if(dlZip2View) mBinding.vone.onei.apply { post {
            displayPage(this, getImgBitmap(currentItem))
        } }
        else {
            val url = imgUrls.getOrNull(currentItem)
            if (url.isNullOrBlank()) {
                mBinding.vone.onei.apply { post { setImageResource(R.drawable.ic_dl) } }
            } else {
                loadNetworkInto(mBinding.vone.onei, url)
            }
        }
        updateSeekBar(hidePanel)
    }

    private fun setIdPosition(position: Int) {
        infoDrawerDelta = position.toFloat()
        mBinding.infcard.root.apply { post { translationY = infoDrawerDelta } }
    }

    @SuppressLint("SetTextI18n")
    private fun prepareItems() {
        if (count <= 0) return
        mBinding.vone.onei.setOnTapRegionListener(::onImageTapped)
        prepareVP()
        prepareInfoBar(count)
        toolsBox.dp2px(67)?.let { setIdPosition(it) }
        prepareIdBtVolTurn()
        prepareIdBtVH()
        prepareIdBtVP()
        prepareIdBtLR()
    }

    private fun prepareIdBtLR() {
        mBinding.infcard.idtblr.apply { post {
            isChecked = r2l
            setOnClickListener {
                if (mBinding.infcard.idtblr.isChecked) p["r2l"] = "true"
                else p["r2l"] = "false"
                // 翻页方向下次浏览生效；但章节按钮的左右语义立即跟随，避免误操作
                r2l = mBinding.infcard.idtblr.isChecked
                applyChapterNavOrder()
                updateChapterNavState()
                Toast.makeText(this@ViewMangaActivity, "翻页方向下次浏览生效", Toast.LENGTH_SHORT).show()
            }
        } }
    }

    private fun prepareIdBtVP() {
        mBinding.infcard.idtbvp.apply { post {
            isChecked = notUseVP
            isEnabled = !verticalReading
            setOnClickListener {
                if (mBinding.infcard.idtbvp.isChecked) p["noAnimation"] = "true"
                else p["noAnimation"] = "false"
                Toast.makeText(this@ViewMangaActivity, "下次浏览生效", Toast.LENGTH_SHORT).show()
            }
        } }
    }

    private fun prepareVP() {
        mBinding.vone.root.visibility = if (readerMode == ReaderMode.SINGLE_PAGE) View.VISIBLE else View.GONE
        mBinding.vp.visibility = if (readerMode == ReaderMode.PAGED) View.VISIBLE else View.GONE
        mBinding.continuousPages.visibility = if (readerMode == ReaderMode.CONTINUOUS) View.VISIBLE else View.GONE

        when (readerMode) {
            ReaderMode.SINGLE_PAGE -> Unit
            ReaderMode.PAGED -> mBinding.vp.apply {
                layoutDirection = if (r2l) {
                    View.LAYOUT_DIRECTION_RTL
                } else {
                    View.LAYOUT_DIRECTION_LTR
                }
                pagedAdapter = PagedMangaAdapter(
                    itemCountProvider = { count },
                    bindImage = ::bindPageImage
                )
                adapter = pagedAdapter
                registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageSelected(position: Int) {
                        preloadAround(position)
                        updateSeekBar()
                        super.onPageSelected(position)
                    }
                })
            }
            ReaderMode.CONTINUOUS -> prepareContinuousReader()
        }
    }

    private fun prepareContinuousReader() {
        continuousAdapter = ContinuousMangaAdapter(
            itemCountProvider = { count },
            bindImage = ::bindPageImage
        )
        mBinding.continuousPages.apply {
            adapter = continuousAdapter
            itemAnimator = null
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    val manager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                    val firstVisible = manager.findFirstVisibleItemPosition()
                    if (firstVisible != RecyclerView.NO_POSITION) {
                        currentItem = firstVisible
                        syncSeekBarOnly()
                        preloadAround(firstVisible)
                    }
                }
            })
        }
    }

    private fun notifyPagesInserted(positionStart: Int, itemCount: Int) {
        if (itemCount <= 0) return
        pagedAdapter?.notifyItemRangeInserted(positionStart, itemCount)
        continuousAdapter?.notifyItemRangeInserted(positionStart, itemCount)
    }

    private fun bindPageImage(imageView: ScaleImageView, position: Int) {
        imageView.tag = position
        imageView.setOnTapRegionListener(::onImageTapped)
        if (dlZip2View) loadZipImage(imageView, position)
        else loadNetworkImage(imageView, position)
    }

    private fun onImageTapped(region: ScaleImageView.TapRegion) {
        when (region) {
            ScaleImageView.TapRegion.PREVIOUS -> {
                if (readerMode == ReaderMode.CONTINUOUS) pagesManager.goBackward()
                else pagesManager.toPreviousPage()
            }
            ScaleImageView.TapRegion.CENTER -> pagesManager.manageInfo()
            ScaleImageView.TapRegion.NEXT -> {
                if (readerMode == ReaderMode.CONTINUOUS) pagesManager.goForward()
                else pagesManager.toNextPage()
            }
        }
    }

    private fun loadNetworkImage(imageView: ScaleImageView, position: Int) {
        val url = imgUrls.getOrNull(position)
        if (url.isNullOrBlank()) {
            imageView.setImageResource(R.drawable.ic_dl)
            return
        }
        loadNetworkInto(imageView, url)
        preloadAround(position)
    }

    private fun loadZipImage(imageView: ScaleImageView, position: Int) {
        imageView.setImageResource(R.drawable.ic_dl)
        imageExecutor.execute {
            val bitmap = getImgBitmap(position)
            imageView.post {
                if (!isFinishing && !isDestroyed && imageView.tag == position) {
                    displayPage(imageView, bitmap)
                }
            }
        }
    }

    private fun updateSeekBar(hidePanel: Boolean = true) {
        if (hidePanel && !isInSeek) hideSettings()
        syncSeekBarOnly()
    }

    private fun syncSeekBarOnly() {
        updateSeekText()
        updateSeekProgress()
    }

    @SuppressLint("SetTextI18n")
    private fun prepareInfoBar(size: Int) {
        mBinding.oneinfo.root.apply { post { alpha = 0F } }
        mBinding.oneinfo.infseek.apply { post {
            visibility = View.INVISIBLE
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(p0: SeekBar?, p1: Int, isHuman: Boolean) {
                    if (isHuman && count > 0) {
                        val targetPage = seekProgressToPage(p1)
                        if (targetPage != pageNum) pageNum = targetPage
                        updateSeekText()
                    }
                }

                override fun onStartTrackingTouch(p0: SeekBar?) {
                    isInSeek = true
                }

                override fun onStopTrackingTouch(p0: SeekBar?) {
                    p0?.let {
                        val targetPage = seekProgressToPage(it.progress)
                        if (targetPage != pageNum) pageNum = targetPage
                    }
                    isInSeek = false
                    updateSeekBar()
                }
            })
        } }
        mBinding.oneinfo.inftitle.isearch.apply { post {
            visibility = View.INVISIBLE
            setOnClickListener { overlayController.toggleDrawer() }
        } }
        mBinding.oneinfo.inftxtprogress.apply { post { text = "$pageNum/$size" } }
        prepareChapterNav()
    }

    private fun prepareIdBtVH() {
        mBinding.infcard.idtbvh.apply { post {
            isChecked = verticalReading
            setOnClickListener {
                p["vertical"] = isChecked.toString()
                Toast.makeText(this@ViewMangaActivity, "下次浏览生效", Toast.LENGTH_SHORT).show()
            }
        } }
    }

    /**
     * 反色补偿的两个旋钮（Levels 中间段模型，参数相互独立）：
     * - 线条增益 gain：1.00 = 不补偿，向右把「半墨」提成线，等效加粗；
     * - 背景黑度 black：压掉补偿把纸面抬起来造成的灰雾，默认 0。
     */
    private fun prepareIdBtTone() {
        mBinding.infcard.idtoneseek.apply { post {
            max = ((InvertTone.MAX_GAIN - InvertTone.MIN_GAIN) * 100).toInt()
            progress = ((invertGain - InvertTone.MIN_GAIN) * 100).toInt().coerceIn(0, max)
            mBinding.infcard.idtonevalue.text = "%.2f".format(invertGain)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    mBinding.infcard.idtonevalue.text = "%.2f".format(InvertTone.MIN_GAIN + p / 100f)
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}

                override fun onStopTrackingTouch(sb: SeekBar?) {
                    invertGain = InvertTone.MIN_GAIN + (sb?.progress ?: 0) / 100f
                    mBinding.infcard.idtonevalue.text = "%.2f".format(invertGain)
                    refreshPages()
                }
            })
        } }

        mBinding.infcard.idblackseek.apply { post {
            max = (InvertTone.MAX_BLACK * 100).toInt()
            progress = (invertBlack * 100).toInt().coerceIn(0, max)
            mBinding.infcard.idblackvalue.text = "%.2f".format(invertBlack)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    mBinding.infcard.idblackvalue.text = "%.2f".format(p / 100f)
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}

                override fun onStopTrackingTouch(sb: SeekBar?) {
                    invertBlack = (sb?.progress ?: 0) / 100f
                    mBinding.infcard.idblackvalue.text = "%.2f".format(invertBlack)
                    refreshPages()
                }
            })
        } }
    }

    private fun prepareIdBtVolTurn() {
        prepareIdBtTone()
        mBinding.infcard.idtbvolturn.apply { post {
            isChecked = volTurnPage
            setOnClickListener {
                if (mBinding.infcard.idtbvolturn.isChecked) p["volturn"] = "true"
                else p["volturn"] = "false"
            }
        } }
    }

    private fun countZipItems(): Int {
        var c = 0
        try {
            val exist = mangaZip?.exists() == true
            if (!exist) return 0
            else {
                Log.d("Myvm", "zipf: $mangaZip")
                ZipFile(mangaZip).use { zip ->
                    c = zip.size()
                }
            }
        } catch (e: Exception) {
            runOnUiThread { toolsBox.toastError("读取zip错误!") }
        }
        return c
    }

    fun scrollBack() {
        if (pageNum > 1) pageNum--
    }

    fun scrollForward() {
        if (pageNum < count) pageNum++
        else if (!streamFinished && streamUrl != null) Toast.makeText(this, "后续页面仍在后台加载", Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("SetTextI18n")
    private fun updateSeekText() {
        mBinding.oneinfo.inftxtprogress.apply { post { text = "$pageNum/$count" } }
    }

    private fun updateSeekProgress() {
        if (isInSeek || count <= 0) return
        mBinding.oneinfo.infseek.apply { post {
            progress = if (count <= 1) 0 else ((pageNum - 1) * 100 / (count - 1)).coerceIn(0, 100)
        } }
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backInvokedCallback?.let(onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
        }
        backInvokedCallback = null
        overlayController.close()
        imageExecutor.shutdownNow()
        pagedAdapter = null
        continuousAdapter = null
        mBinding.vp.adapter = null
        mBinding.continuousPages.adapter = null
        dialog?.dismiss()
        dialog = null
        mBinding.wcollector.destroy()
        val skipBack = skipBackOnExit
        if (!switchingChapter) skipBackOnExit = false
        if (userRequestedExit && !dlZip2View && !skipBack) wm?.get()?.mBinding?.w?.goBack()
        if (streamUrl != null) streamChapterUrl = null
        if (va?.get() === this) va = null
        super.onDestroy()
    }

    fun showSettings() {
        mBinding.oneinfo.infseek.visibility = View.VISIBLE
        mBinding.oneinfo.inftitle.isearch.visibility = View.VISIBLE
        val v = mBinding.oneinfo.root
        ObjectAnimator.ofFloat(
            v,
            "alpha",
            v.alpha,
            1F
        ).setDuration(233).start()
        clicked = true
    }

    fun hideSettings() {
        val v = mBinding.oneinfo.root
        ObjectAnimator.ofFloat(
            v,
            "alpha",
            v.alpha,
            0F
        ).setDuration(233).start()
        clicked = false
        mBinding.oneinfo.infseek.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            mBinding.oneinfo.infseek.visibility = View.INVISIBLE
            mBinding.oneinfo.inftitle.isearch.visibility = View.INVISIBLE
        }, 300)
        overlayController.hideDrawer()
    }

    companion object {
        const val FIRST_PAGE = -1
        const val LAST_PAGE = -2

        var va: WeakReference<ViewMangaActivity>? = null
        var imgUrls = arrayOf<String>()
        var zipFile: File? = null
        get() {
            val re = field
            if(field != null) field = null
            return re
        }
        var titleText = "Null"
        var nextChapterUrl: String? = null
        var previousChapterUrl: String? = null
        var zipPosition = 0
        var zipList: Array<File>? = null
        var cd: File? = null
        var pn = FIRST_PAGE
        var streamChapterUrl: String? = null
        // 由 JS.loadComicDirect 设置：跳过中间页直接进入阅读器时，退出不需要 goBack
        var skipBackOnExit = false

    }

    private enum class ReaderMode {
        SINGLE_PAGE,
        PAGED,
        CONTINUOUS,
    }
}