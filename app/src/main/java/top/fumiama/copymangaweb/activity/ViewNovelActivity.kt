package top.fumiama.copymangaweb.activity

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import top.fumiama.copymangaweb.R
import top.fumiama.copymangaweb.databinding.ActivityViewnovelBinding
import top.fumiama.copymangaweb.tool.InsetsTools
import top.fumiama.copymangaweb.tool.NightTint
import top.fumiama.copymangaweb.tool.NovelApi
import top.fumiama.copymangaweb.tool.NovelBookMeta
import top.fumiama.copymangaweb.tool.NovelStore
import top.fumiama.copymangaweb.tool.NovelVolumeMeta
import top.fumiama.copymangaweb.tool.ReadingProgress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 原生小说阅读器，版式参照常见阅读器：
 * 常态下隐藏系统状态栏与上下栏，只留顶部小字章节名、左下时间电量、右下页码；
 * 点击正文中部悬浮显示系统栏与上下栏（上栏返回、下栏目录/字号/翻章），再点正文隐藏；
 * 目录以浮层出现：左侧条目、右侧黑色半透明遮罩，点遮罩返回阅读页。
 *
 * 正文按屏幕尺寸分页（StaticLayout 切行）；插图（content_type=2）作为整页图片展示。
 */
class ViewNovelActivity : Activity() {
    private lateinit var mBinding: ActivityViewnovelBinding
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private var meta: NovelBookMeta? = null
    private var vol: NovelVolumeMeta? = null
    private var fullText: String = ""
    private var chapterIndex = 0
    private var fontSize = 18f

    private var pages: List<Page> = emptyList()
    private var pagesKey = ""
    private var barsVisible = false
    private var pendingRestorePage = RESTORE_ANCHOR
    private var scrollMode = false
    private var pageOffsets: MutableList<Int> = mutableListOf()
    /** 每页页首在滚动内容里的 y（含 vnscroll 顶部内边距），用于两种模式精确对齐 */
    private var pageTops: MutableList<Int> = mutableListOf()
    /** 最近一次可用的章内字符偏移，布局未就绪时用它兜底 */
    private var lastAnchor = 0

    private val night get() = NightTint.on(this)

    /** 正文点击：分页模式左/中/右分别是 上一页/显隐栏/下一页；滚动模式任意点击显隐栏。 */
    private val tapDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (scrollMode) {
                    toggleBars()
                } else {
                    val w = mBinding.vnvp.width.coerceAtLeast(1)
                    when {
                        e.x < w * 0.33f -> turnPage(-1)
                        e.x > w * 0.67f -> turnPage(1)
                        else -> toggleBars()
                    }
                }
                return true
            }
        })
    }
    private val barOrigin by lazy { NightTint.captureBars(this) }

    /** 一页：正文页持有整章 Layout 与该页首行的 y，插图页持有图片 */
    private class Page {
        var layout: Layout? = null
        var topY = 0
        var image: Any? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 边到边：系统栏显示与否都不改变内容区尺寸，只是悬浮覆盖在正文上，
        // 页面尺寸因此恒定（正文框固定，安全区由上下栏自己让开）。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        mBinding = ActivityViewnovelBinding.inflate(layoutInflater)
        setContentView(mBinding.root)
        // 正文框固定：不把安全区做成根布局内边距，否则系统栏一显隐正文区尺寸就变，
        // 分页跟着重排、阅读位置被顶掉。安全区只加在上下栏自己身上。
        applyBarInsets()
        fontSize = getSharedPreferences(PREF, MODE_PRIVATE).getFloat(KEY_FONT, 18f)
        applyNight()
        wireUi()
        mBinding.vnvp.adapter = PageAdapter()
        mBinding.vnvp.offscreenPageLimit = 1
        mBinding.vnvp.registerOnPageChangeCallback(
            object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    updateStatusLine()
                    saveProgress()
                }
            }
        )
        // 系统栏显隐会改变正文区高度，两种模式都得跟着重排；两个视图都挂监听，
        // 否则当前隐藏的那个会漏掉尺寸变化，留下另一套分页。
        val relayout = View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            if (r - l != orr - ol || b - t != ob - ot) rebuildPages()
        }
        mBinding.vnvp.addOnLayoutChangeListener(relayout)
        mBinding.vnscroll.addOnLayoutChangeListener(relayout)
        ui.post(statusTicker)

        val book = intent.getStringExtra(EXTRA_BOOK)
        if (book.isNullOrBlank()) {
            finish()
            return
        }
        io.execute { openBook(book, intent.getStringExtra(EXTRA_VOLUME)) }
    }

    override fun onResume() {
        super.onResume()
        NightTint.applyBars(this, barOrigin)
        if (!barsVisible) hideSystemBars()
    }

    override fun onPause() {
        super.onPause()
        saveProgress()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        io.shutdownNow()
        super.onDestroy()
    }

    // ---------------- UI 装配 ----------------

    private fun wireUi() {
        mBinding.vntopchapter.isClickable = false
        mBinding.vnstatusline.isClickable = false
        mBinding.vnback.setOnClickListener { finish() }
        mBinding.vntoc.setOnClickListener { showToc() }
        mBinding.vntocmask.setOnClickListener { hideToc() }
        mBinding.vnfontdec.setOnClickListener { changeFont(-1f) }
        mBinding.vnfontinc.setOnClickListener { changeFont(1f) }
        mBinding.vnprev.setOnClickListener { stepChapter(-1) }
        mBinding.vnnext.setOnClickListener { stepChapter(1) }
        mBinding.vnmode.setOnClickListener { toggleMode() }
        mBinding.vnscroll.setOnScrollChangeListener { _, _, _, _, _ ->
            if (scrollMode) updateStatusLine()
        }
        // 只把手势喂给检测器、不消费事件：返回 true 会让 ScrollView 自己的滚动逻辑失效
        mBinding.vnscroll.setOnTouchListener { _, ev ->
            tapDetector.onTouchEvent(ev)
            false
        }
        mBinding.vnvp.post { hideSystemBars() }
        updateModeButton()
    }

    /** 上下栏各自让开状态栏/导航栏；正文区不受影响 */
    private fun applyBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(mBinding.vnroot) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            mBinding.vntopbar.setPadding(
                mBinding.vntopbar.paddingLeft, dp(4) + bars.top,
                mBinding.vntopbar.paddingRight, mBinding.vntopbar.paddingBottom
            )
            mBinding.vnbottombar.setPadding(
                mBinding.vnbottombar.paddingLeft, mBinding.vnbottombar.paddingTop,
                mBinding.vnbottombar.paddingRight, dp(5) + bars.bottom
            )
            mBinding.vntopchapter.setPadding(0, bars.top, 0, 0)
            mBinding.vnstatusline.setPadding(0, 0, 0, bars.bottom)
            mBinding.vntocoverlay.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(mBinding.vnroot)
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, mBinding.vnroot).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun showSystemBars() {
        WindowInsetsControllerCompat(window, mBinding.vnroot)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    private fun setBarsVisible(visible: Boolean) {
        barsVisible = visible
        mBinding.vntopbar.visibility = if (visible) View.VISIBLE else View.GONE
        mBinding.vnbottombar.visibility = if (visible) View.VISIBLE else View.GONE
        mBinding.vntopchapter.visibility = if (visible) View.GONE else View.VISIBLE
        mBinding.vnstatusline.visibility = if (visible) View.GONE else View.VISIBLE
        if (visible) showSystemBars() else hideSystemBars()
    }

    private fun toggleBars() {
        if (mBinding.vntocoverlay.visibility == View.VISIBLE) {
            hideToc()
            return
        }
        setBarsVisible(!barsVisible)
    }

    // ---------------- 阅读方式（翻页 / 滚动） ----------------

    private fun toggleMode() {
        val ch = vol?.chapters?.getOrNull(chapterIndex)
        if (ch != null && ch.isImage) {
            Toast.makeText(this, "插图页只支持翻页", Toast.LENGTH_SHORT).show()
            return
        }
        scrollMode = !scrollMode
        applyMode()
    }

    /** 切换阅读方式：翻页<->滚动 时按当前阅读位置对齐。 */
    private fun applyMode() {
        if (scrollMode) {
            // 翻页 -> 滚动：直接对齐到当前页首（同一张页顶表，像素级一致）
            val page = mBinding.vnvp.currentItem
            mBinding.vnvp.visibility = View.GONE
            mBinding.vnscroll.visibility = View.VISIBLE
            mBinding.vnfadetop.visibility = View.VISIBLE
            mBinding.vnfadebottom.visibility = View.VISIBLE
            loadScrollText()
            scrollToPage(page)
        } else {
            // 滚动 -> 翻页：对齐到视口顶端所在的那一页
            val page = scrollTopPage()
            mBinding.vnvp.visibility = View.VISIBLE
            mBinding.vnscroll.visibility = View.GONE
            mBinding.vnfadetop.visibility = View.GONE
            mBinding.vnfadebottom.visibility = View.GONE
            mBinding.vnvp.setCurrentItem(
                page.coerceIn(0, (pages.size - 1).coerceAtLeast(0)), false
            )
        }
        updateModeButton()
        updateStatusLine()
        saveProgress()
    }

    private fun updateModeButton() {
        mBinding.vnmode.setText(if (scrollMode) R.string.reader_mode_scroll else R.string.reader_mode_paged)
    }

    private fun loadScrollText() {
        val v = vol ?: return
        val ch = v.chapters.getOrNull(chapterIndex) ?: return
        mBinding.vnscrolltext.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize)
        mBinding.vnscrolltext.setTextColor(if (night) NightTint.FG else 0xFF333333.toInt())
        mBinding.vnscrolltext.text = NovelStore.chapterText(fullText, ch)
    }

    /** 当前读到哪：翻页模式取当前页首字符，滚动模式取视口顶端所在的页首字符 */
    private fun currentAnchorOffset(): Int {
        val off = if (scrollMode) scrollTopOffsetOrNull() else null
        if (off != null) lastAnchor = off
        return off ?: pageOffsets.getOrElse(mBinding.vnvp.currentItem) { lastAnchor }
    }

    /** 滚动位置 -> 章内字符偏移；页表/布局还没跟上时返回 null */
    private fun scrollTopOffsetOrNull(): Int? {
        if (!scrollLayoutReady()) return null
        return pageOffsets.getOrElse(scrollTopPage()) { lastAnchor }
    }

    /**
     * 滚动正文布局是否可用：文本要一致，宽度也要和分页时相同。
     * 切到滚动模式的一瞬间 ScrollView 还是 GONE，TextView 可能只按未测量的宽度
     * 建过一份布局，此时按页顶像素滚动会落错位置，必须等它按真实宽度重排。
     */
    private fun scrollLayoutReady(): Boolean {
        val tv = mBinding.vnscrolltext
        val layout = tv.layout ?: return false
        if (pageTops.isEmpty() || layout.text !== tv.text) return false
        val inner = tv.width - tv.paddingLeft - tv.paddingRight
        return inner > 0 && layout.width == inner
    }

    /** 视口顶端算第几页：按页顶像素边界取最近的一页，比「行 -> 字符」反查精确 */
    private fun scrollTopPage(): Int {
        if (pageTops.isEmpty()) return 0
        val y = mBinding.vnscroll.scrollY
        var idx = 0
        for (i in pageTops.indices) {
            if (pageTops[i] <= y + 2) idx = i else break
        }
        // 越过一半就算下一页，页号显示与切回翻页时的落点因此始终一致
        val next = idx + 1
        if (next < pageTops.size && (pageTops[next] - y) < (y - pageTops[idx])) return next
        return idx
    }

    /** 滚动到某页页首；布局未就绪时等下一帧重试 */
    private fun scrollToPage(index: Int) {
        val apply = object : Runnable {
            override fun run() {
                if (!scrollLayoutReady()) {
                    if (mBinding.vnscroll.isAttachedToWindow) mBinding.vnscroll.postDelayed(this, 16)
                    return
                }
                val y = pageTops.getOrElse(index) { 0 }
                mBinding.vnscroll.scrollTo(0, y)
            }
        }
        mBinding.vnscroll.post(apply)
    }

    /** 字符偏移落在第几页（按页首偏移判断） */
    private fun pageIndexForOffset(offset: Int): Int {
        if (pageOffsets.isEmpty()) return 0
        var idx = 0
        for (i in pageOffsets.indices) {
            if (pageOffsets[i] <= offset) idx = i else break
        }
        return idx
    }

    // ---------------- 目录 ----------------

    private fun showToc() {
        val v = vol ?: return
        mBinding.vntoclist.adapter = TocAdapter(v)
        mBinding.vntoclist.setOnItemClickListener { _, _, position, _ ->
            hideToc()
            selectChapter(position, 0)
        }
        mBinding.vntocoverlay.visibility = View.VISIBLE
    }

    private fun hideToc() {
        mBinding.vntocoverlay.visibility = View.GONE
    }

    private inner class TocAdapter(private val volume: NovelVolumeMeta) : BaseAdapter() {
        override fun getCount() = volume.chapters.size
        override fun getItem(position: Int) = volume.chapters[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val tv = (convertView as? TextView) ?: TextView(this@ViewNovelActivity).apply {
                setPadding(dp(16), dp(14), dp(16), dp(14))
                textSize = 15f
                maxLines = 2
            }
            val ch = volume.chapters[position]
            tv.text = ch.name
            val active = position == chapterIndex
            tv.setTextColor(
                when {
                    night && active -> 0xFF7FB6FF.toInt()
                    night -> NightTint.FG
                    active -> 0xFF1890E5.toInt()
                    else -> 0xFF333333.toInt()
                }
            )
            tv.setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
            return tv
        }
    }

    // ---------------- 翻页 / 翻章 ----------------

    private fun turnPage(delta: Int) {
        val target = mBinding.vnvp.currentItem + delta
        if (target in pages.indices) {
            mBinding.vnvp.setCurrentItem(target, true)
        } else {
            stepChapter(delta)
        }
    }

    private fun stepChapter(delta: Int) {
        val v = vol ?: return
        val target = chapterIndex + delta
        if (target in v.chapters.indices) {
            selectChapter(target, if (delta > 0) 0 else Int.MAX_VALUE)
            return
        }
        val adjacentId = if (delta > 0) v.next else v.prev
        if (adjacentId.isNullOrBlank()) {
            Toast.makeText(this, if (delta > 0) "已经是最后一卷" else "已经是第一卷", Toast.LENGTH_SHORT).show()
            return
        }
        io.execute {
            val m = meta ?: return@execute
            val adjacent = m.details[adjacentId] ?: fetchVolume(m, adjacentId)
            if (adjacent == null) {
                toastOnUi("切换卷失败")
                return@execute
            }
            loadVolume(adjacent, if (delta > 0) 0 else (adjacent.chapters.size - 1).coerceAtLeast(0), 0)
        }
    }

    private fun changeFont(delta: Float) {
        fontSize = (fontSize + delta).coerceIn(MIN_FONT, MAX_FONT)
        getSharedPreferences(PREF, MODE_PRIVATE).edit().putFloat(KEY_FONT, fontSize).apply()
        rebuildPages()
    }

    // ---------------- 数据 ----------------

    private fun openBook(book: String, volumeId: String?) {
        val m = NovelStore.load(this, book)
        if (m == null) {
            toastOnUi("未找到本地小说数据")
            finishOnUi()
            return
        }
        meta = m
        val saved = ReadingProgress.novel(this, book)
        val targetId = volumeId ?: saved?.volumeId
        var v = targetId?.let { id -> m.details[id] ?: fetchVolume(m, id) }
        if (v == null) {
            val firstId = m.volumes.firstOrNull()?.id
            v = firstId?.let { fetchVolume(m, it) }
        }
        if (v == null) {
            toastOnUi("无法获取卷信息")
            finishOnUi()
            return
        }
        val sameVolume = saved != null && saved.volumeId == v.id
        val startChapter = if (sameVolume) saved!!.chapterIndex else 0
        val startPage = if (sameVolume) saved!!.page else 0
        loadVolume(v, startChapter, startPage)
    }

    private fun fetchVolume(m: NovelBookMeta, volumeId: String): NovelVolumeMeta? =
        NovelApi.volumeDetail(m.apiBase, m.pathWord, volumeId)?.also {
            m.details[it.id] = it
            NovelStore.save(this, m)
        }

    private fun loadVolume(v: NovelVolumeMeta, startChapter: Int, startPage: Int) {
        val m = meta ?: return
        vol = v
        chapterIndex = startChapter.coerceIn(0, (v.chapters.size - 1).coerceAtLeast(0))
        pendingRestorePage = startPage
        lastAnchor = 0
        val ch = v.chapters.getOrNull(chapterIndex)
        var text = ""
        if (ch != null && !ch.isImage) {
            text = NovelStore.volumeText(this, m, v).orEmpty()
            if (text.isEmpty()) {
                if (v.txtAddr.isBlank()) {
                    toastOnUi("该卷缺少正文地址")
                    return
                }
                toastOnUi("正在获取正文…")
                val dest = NovelStore.txtFile(this, m.name, v.name)
                if (NovelApi.downloadBinary(v.txtAddr, dest)) {
                    text = NovelStore.volumeText(this, m, v).orEmpty()
                }
            }
        }
        fullText = text
        runOnUiThread {
            pagesKey = ""
            rebuildPages()
            updateTitles()
        }
    }

    private fun selectChapter(index: Int, page: Int) {
        val v = vol ?: return
        if (index !in v.chapters.indices) return
        chapterIndex = index
        pendingRestorePage = if (page == Int.MAX_VALUE) RESTORE_LAST else page
        lastAnchor = 0
        pagesKey = ""
        rebuildPages()
        updateTitles()
    }

    // ---------------- 分页 ----------------

    private fun rebuildPages() {
        val v = vol ?: return
        val ch = v.chapters.getOrNull(chapterIndex) ?: return
        // 正文框以根布局内容区为基准：与当前显示分页还是滚动无关，两种模式的
        // 分页与页首偏移因此完全一致（页数不会因为切换阅读方式而变）。
        val cw = mBinding.vnroot.width - mBinding.vnroot.paddingLeft - mBinding.vnroot.paddingRight
        val chh = mBinding.vnroot.height - mBinding.vnroot.paddingTop - mBinding.vnroot.paddingBottom
        val w = cw - dp(40)
        val h = chh - dp(84)
        if (w <= 0 || h <= 0) {
            Log.d("NovelReader", "skip rebuild, box=$w x $h")
            return
        }
        val key = "${v.id}|${chapterIndex}|${fontSize}|$night|${w}x${h}"
        if (key == pagesKey && pages.isNotEmpty()) {
            Log.d("NovelReader", "rebuild skipped, same key=$key")
            return
        }
        // 字号或安全区变化会重排分页，先记下当前读到的字符位置，重排后回到同一处文字
        val anchor = currentAnchorOffset()
        val restore = pendingRestorePage
        pendingRestorePage = RESTORE_ANCHOR

        val built = ArrayList<Page>()
        val offsets = ArrayList<Int>()
        val tops = ArrayList<Int>()
        val padTop = mBinding.vnscroll.paddingTop + mBinding.vnscrolltext.paddingTop
        if (ch.isImage) {
            val local = NovelStore.imageFile(this, meta?.name.orEmpty(), v.name, ch)
            built.add(Page().also { it.image = if (local.exists()) local else ch.imageUrl })
            offsets.add(0)
            tops.add(padTop)
        } else {
            val text = NovelStore.chapterText(fullText, ch)
            val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
                textSize = sp(fontSize)
                color = if (night) NightTint.FG else 0xFF333333.toInt()
            }
            // 断行策略与滚动模式的 TextView 保持一致，两种模式的换行位置才相同
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, w)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(dp(6).toFloat(), 1f)
                .setIncludePad(false)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build()
            var line = 0
            while (line < layout.lineCount) {
                val top = layout.getLineTop(line)
                var last = layout.getLineForVertical(top + h)
                if (last < line) last = line
                if (last >= layout.lineCount) last = layout.lineCount - 1
                // 整行放不下就不算进本页，避免页底出现被裁掉半行
                while (last > line && layout.getLineBottom(last) > top + h) last--
                built.add(Page().also { it.layout = layout; it.topY = top })
                offsets.add(layout.getLineStart(line))
                tops.add(top + padTop)
                line = last + 1
            }
            if (built.isEmpty()) built.add(Page().also { it.layout = layout })
        }
        Log.d("NovelReader", "rebuild chapter=$chapterIndex name=${ch.name} isImage=${ch.isImage} textLen=${if (ch.isImage) -1 else NovelStore.chapterText(fullText, ch).length} box=$w x $h pages=${built.size}")
        pages = built
        pageOffsets = offsets
        pageTops = tops
        pagesKey = key
        mBinding.vnvp.adapter?.notifyDataSetChanged()
        val last = (pages.size - 1).coerceAtLeast(0)
        val target = when {
            restore == RESTORE_LAST -> last
            restore >= 0 -> restore.coerceIn(0, last)
            else -> pageIndexForOffset(anchor)
        }
        mBinding.vnvp.setCurrentItem(target, false)
        if (scrollMode) {
            loadScrollText()
            scrollToPage(if (restore == RESTORE_ANCHOR) pageIndexForOffset(anchor) else target)
        }
        updateStatusLine()
        saveProgress()
    }

    private fun updateTitles() {
        val ch = vol?.chapters?.getOrNull(chapterIndex) ?: return
        mBinding.vntopchapter.text = ch.name
        mBinding.vntopbartitle.text = ch.name
    }

    private fun updateStatusLine() {
        val level = runCatching {
            (getSystemService(Context.BATTERY_SERVICE) as BatteryManager)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrDefault(0)
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        mBinding.vntimeleft.text = "$level%  $time"
        val pageIndex = if (scrollMode) scrollTopPage() else mBinding.vnvp.currentItem
        mBinding.vnposright.text = "${pageIndex + 1} / ${pages.size.coerceAtLeast(1)}"
    }

    private val statusTicker = object : Runnable {
        override fun run() {
            updateStatusLine()
            ui.postDelayed(this, 15000)
        }
    }

    private fun saveProgress() {
        val v = vol ?: return
        val ch = v.chapters.getOrNull(chapterIndex) ?: return
        val page = if (scrollMode) {
            // 布局还没跟上文本时读不到真实位置，宁可不写也不要覆盖成第 0 页
            if (!scrollLayoutReady()) return
            val idx = scrollTopPage()
            lastAnchor = pageOffsets.getOrElse(idx) { lastAnchor }
            idx
        } else {
            lastAnchor = pageOffsets.getOrElse(mBinding.vnvp.currentItem) { lastAnchor }
            mBinding.vnvp.currentItem
        }
        ReadingProgress.saveNovel(
            this, meta?.name.orEmpty(), v.id, v.name, chapterIndex, ch.name, page
        )
    }

    // ---------------- 页面适配 ----------------

    /** 分页正文直接绘制分页用的那份 Layout，显示与分页永远一致 */
    private inner class PageView(ctx: Context) : View(ctx) {
        var page: Page? = null

        override fun onDraw(canvas: Canvas) {
            val p = page ?: return
            val l = p.layout ?: return
            canvas.save()
            canvas.clipRect(paddingLeft, paddingTop, width - paddingRight, height - paddingBottom)
            canvas.translate(paddingLeft.toFloat(), (paddingTop - p.topY).toFloat())
            l.draw(canvas)
            canvas.restore()
        }
    }

    private inner class PageAdapter : RecyclerView.Adapter<PageAdapter.Holder>() {
        inner class Holder(root: FrameLayout, val pageView: PageView, val image: ImageView) :
            RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val ctx = this@ViewNovelActivity
            val frame = FrameLayout(ctx).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            // 与 vnscroll 的 padding 一致，两种模式下正文起始位置相同
            val pv = PageView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setPadding(dp(20), dp(44), dp(20), dp(40))
            }
            val iv = ImageView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
                setPadding(dp(20), dp(44), dp(20), dp(40))
                scaleType = ImageView.ScaleType.FIT_CENTER
                visibility = View.GONE
            }
            frame.addView(pv)
            frame.addView(iv)
            frame.setOnTouchListener { _, ev -> tapDetector.onTouchEvent(ev) }
            return Holder(frame, pv, iv)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val page = pages.getOrNull(position) ?: return
            if (page.layout != null) {
                holder.pageView.visibility = View.VISIBLE
                holder.image.visibility = View.GONE
                holder.pageView.page = page
                holder.pageView.invalidate()
            } else {
                holder.pageView.visibility = View.GONE
                holder.image.visibility = View.VISIBLE
                Glide.with(this@ViewNovelActivity)
                    .load(page.image)
                    .fitCenter()
                    .into(holder.image)
            }
        }

        override fun getItemCount() = pages.size
    }

    // ---------------- 着色与小工具 ----------------

    private fun applyNight() {
        if (!night) return
        mBinding.vnroot.setBackgroundColor(NightTint.BG)
        mBinding.vnvp.setBackgroundColor(NightTint.BG)
        mBinding.vntopbar.setBackgroundColor(NightTint.SURFACE)
        mBinding.vnbottombar.setBackgroundColor(NightTint.SURFACE)
        mBinding.vntoclist.setBackgroundColor(NightTint.SURFACE)
        mBinding.vntopchapter.setTextColor(NightTint.FG)
        mBinding.vntimeleft.setTextColor(NightTint.FG)
        mBinding.vnposright.setTextColor(NightTint.FG)
        mBinding.vntopbartitle.setTextColor(NightTint.FG)
        mBinding.vnfadetop.setBackgroundResource(R.drawable.fade_top_dark)
        mBinding.vnfadebottom.setBackgroundResource(R.drawable.fade_bottom_dark)
        for (b in listOf(
            mBinding.vnback, mBinding.vntoc, mBinding.vnfontdec,
            mBinding.vnfontinc, mBinding.vnprev, mBinding.vnnext, mBinding.vnmode
        )) {
            b.setTextColor(NightTint.FG)
            b.setBackgroundResource(R.drawable.reader_btn_dark)
        }
    }

    private fun dp(v: Int): Int = (resources.displayMetrics.density * v).toInt()

    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private fun toastOnUi(msg: String) {
        runOnUiThread { runCatching { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() } }
    }

    private fun finishOnUi() {
        runOnUiThread { if (!isFinishing) finish() }
    }

    companion object {
        const val EXTRA_BOOK = "book"
        const val EXTRA_VOLUME = "volume"
        private const val PREF = "novel_reader"
        private const val KEY_FONT = "font_size"
        private const val MIN_FONT = 12f
        private const val MAX_FONT = 32f
        /** 重排后保持当前阅读位置 */
        private const val RESTORE_ANCHOR = Int.MIN_VALUE
        /** 跳到本章末页 */
        private const val RESTORE_LAST = -1
    }
}
