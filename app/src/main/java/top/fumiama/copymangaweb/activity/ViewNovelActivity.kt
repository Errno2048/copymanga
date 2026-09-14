package top.fumiama.copymangaweb.activity

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
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
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
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
    /** >=0 时按章内字符偏移恢复（不随字号漂移） */
    private var pendingRestoreOffset = -1
    /** 重排期间抑制进度写入：notifyDataSetChanged 会先回调一次 onPageSelected(0) */
    private var suppressProgressSave = false
    /** 换章过渡用的截图缓冲（复用，避免每次换章都重新分配） */
    private var slideBitmap: Bitmap? = null
    /** 换章过渡进行中 */
    private var sliding = false
    private var slideAnim: ValueAnimator? = null
    // 边界滑动换章的按下起点
    private var swipeDownX = 0f
    private var swipeDownY = 0f
    private var swipeDownItem = 0
    private var scrollMode = false
    private var pageOffsets: MutableList<Int> = mutableListOf()
    /**
     * 每页对应的滚动偏移（scrollY）。取该值时页首行正好落在正文区顶部，
     * 与翻页模式下页首行的位置一致（滚动内容自身已含 vnscroll 的顶部内边距）。
     */
    private var pageTops: MutableList<Int> = mutableListOf()
    /**
     * 当前阅读位置的章内字符偏移。只由「用户翻页/滚动」和「恢复进度」更新；
     * 重排（换字号、安全区变化）不改写它，否则每换一次字号都会退到当时页首，逐次往回漂。
     */
    private var lastAnchor = -1

    private val night get() = NightTint.on(this)

    /**
     * 翻页模式下的点击手势：左/中/右 = 上一页/显隐栏/下一页；滚动模式任意点击显隐栏。
     * （边界处的左右滑动换章由 installBoundarySwipe 在 ViewPager2 层面观察，
     *   这里收不到整段滑动——ViewPager2 一旦开始拖动就会拦截事件流。）
     */
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
        installBoundarySwipe()
        mBinding.vnvp.offscreenPageLimit = 1
        // 翻页模式左右滑动用于换章（翻页靠点击左右区域）；滚动模式滑动仍是滚动
        mBinding.vnvp.registerOnPageChangeCallback(
            object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    updateStatusLine()
                    if (!suppressProgressSave) {
                        lastAnchor = pageOffsets.getOrElse(position) { lastAnchor }
                        saveProgress()
                    }
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
        slideAnim?.cancel()
        slideAnim = null
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
            if (scrollMode) {
                updateStatusLine()
                if (!suppressProgressSave && scrollLayoutReady()) {
                    lastAnchor = pageOffsets.getOrElse(scrollTopPage()) { lastAnchor }
                }
            }
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
            // 翻页 -> 滚动：先把正文与滚动位置都摆好再让它可见。
            // 反过来的话，ScrollView 会带着上一次的 scrollY/旧正文先渲染一帧，
            // 看起来就是「先闪一下上次滚动到的画面，再跳到对齐位置」。
            // 注意：这时 TextView 的布局还没跟上新设置的文本，直接 scrollTo 只是白做，
            // 显示出来的一帧仍是上一次滚动模式停留的位置——也就是会「闪一下」。
            // 所以先让滚动视图保持不可见（INVISIBLE 仍参与布局），滚到位后再换过来。
            val page = mBinding.vnvp.currentItem
            mBinding.vnscroll.visibility = View.INVISIBLE
            loadScrollText()
            scrollToPage(page) {
                if (!scrollMode) return@scrollToPage   // 期间又切回翻页了，别再显示
                mBinding.vnvp.visibility = View.GONE
                mBinding.vnfadetop.visibility = View.VISIBLE
                mBinding.vnfadebottom.visibility = View.VISIBLE
                mBinding.vnscroll.visibility = View.VISIBLE
                updateStatusLine()
            }
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

    /**
     * 翻页模式下，已经在本章第一页/最后一页还继续左右滑动时，切换到上一章/下一章；
     * 滚动模式不装这套逻辑（保持现状）。
     */
    private fun installBoundarySwipe() {
        val rv = mBinding.vnvp.getChildAt(0) as? RecyclerView ?: return
        rv.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            private var claimed = false

            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        swipeDownX = e.x
                        swipeDownY = e.y
                        swipeDownItem = mBinding.vnvp.currentItem
                        claimed = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        // 只在「已经在本章首/末页、还继续往外滑」时接管这段手势。
                        // 平时返回 false，左右滑动翻页仍由 ViewPager2 正常处理。
                        if (!claimed && !scrollMode) {
                            val dx = e.x - swipeDownX
                            val dy = e.y - swipeDownY
                            val out = when {
                                dx < 0 -> swipeDownItem >= pages.size - 1
                                dx > 0 -> swipeDownItem <= 0
                                else -> false
                            }
                            if (out && abs(dx) > abs(dy) && abs(dx) > dp(SWIPE_CLAIM_DP)) {
                                claimed = true
                                return true   // 边界上没有可翻的页，接管不影响翻页
                            }
                        }
                    }
                }
                return claimed
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                when (e.actionMasked) {
                    MotionEvent.ACTION_UP -> {
                        val dx = e.x - swipeDownX
                        if (claimed && abs(dx) > mBinding.vnvp.width * 0.15f) {
                            stepChapter(if (dx < 0) 1 else -1)
                        }
                        claimed = false
                    }
                    MotionEvent.ACTION_CANCEL -> claimed = false
                }
            }
        })
    }

    private fun updateModeButton() {
        mBinding.vnmode.setText(if (scrollMode) R.string.reader_mode_scroll else R.string.reader_mode_paged)
    }

    private fun loadScrollText() {
        val v = vol ?: return
        val ch = v.chapters.getOrNull(chapterIndex) ?: return
        mBinding.vnscrolltext.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize)
        mBinding.vnscrolltext.setLineSpacing(dpf(LINE_SPACING_DP), 1f)
        mBinding.vnscrolltext.setTextColor(if (night) NightTint.FG else 0xFF333333.toInt())
        mBinding.vnscrolltext.text = NovelStore.chapterText(fullText, ch)
    }

    /** 按当前视图推算位置（不改写粘性锚点） */
    private fun currentAnchorOffset(): Int {
        if (scrollMode) scrollTopOffsetOrNull()?.let { return it }
        return pageOffsets.getOrElse(mBinding.vnvp.currentItem) { 0 }
    }

    /** 滚动位置 -> 章内字符偏移；页表/布局还没跟上时返回 null */
    private fun scrollTopOffsetOrNull(): Int? {
        if (!scrollLayoutReady()) return null
        return pageOffsets.getOrElse(scrollTopPage()) { 0 }
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

    /** 滚动到底时最大可滚位置（末页页顶可能超出它，需要单独判定） */
    private fun scrollMaxY(): Int {
        val tv = mBinding.vnscrolltext
        return (tv.height + mBinding.vnscroll.paddingTop + mBinding.vnscroll.paddingBottom -
            mBinding.vnscroll.height).coerceAtLeast(0)
    }

    /** 正文区顶端算第几页：按页顶滚动偏移取最近的一页，比「行 -> 字符」反查精确 */
    private fun scrollTopPage(): Int {
        if (pageTops.isEmpty()) return 0
        val y = mBinding.vnscroll.scrollY
        // 滚到底就是最后一页（末页页顶滚不到视口顶端，按最近页会差一页）
        if (y >= scrollMaxY() - 2) return pageTops.size - 1
        var idx = 0
        for (i in pageTops.indices) {
            if (pageTops[i] <= y + 2) idx = i else break
        }
        // 越过一半就算下一页，页号显示与切回翻页时的落点因此始终一致
        val next = idx + 1
        if (next < pageTops.size && (pageTops[next] - y) < (y - pageTops[idx])) return next
        return idx
    }

    /**
     * 滚动到某页页首（页首行落在正文区顶部，与翻页模式同高）。
     * 布局未就绪时每帧重试（最多约 0.6s），滚动完成或放弃后回调 onReady。
     */
    private fun scrollToPage(index: Int, onReady: (() -> Unit)? = null) {
        var attempts = 0
        val apply = object : Runnable {
            override fun run() {
                if (!scrollLayoutReady() && attempts++ < 40) {
                    if (mBinding.vnscroll.isAttachedToWindow) {
                        mBinding.vnscroll.postDelayed(this, 16)
                        return
                    }
                    onReady?.invoke()
                    return
                }
                if (scrollLayoutReady()) {
                    mBinding.vnscroll.scrollTo(0, pageTops.getOrElse(index) { 0 })
                }
                onReady?.invoke()
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
        if (sliding) return
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
            toChapter(target, delta)
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

    /**
     * 换到相邻章节：翻页模式下先截下当前画面，换章后让新内容从翻页方向滑入、
     * 旧画面同步滑出，视觉上与章节内翻页一致；滚动模式或跨卷（要联网取卷）不做动画。
     */
    private fun toChapter(index: Int, delta: Int) {
        if (sliding) return
        val snap = if (!scrollMode) capturePager() else null
        selectChapter(index, if (delta > 0) 0 else Int.MAX_VALUE)
        if (snap == null) return
        val w = mBinding.vnvp.width.toFloat()
        val dir = if (delta > 0) 1f else -1f
        sliding = true
        mBinding.vnslide.setImageBitmap(snap)
        mBinding.vnslide.visibility = View.VISIBLE
        mBinding.vnslide.translationX = 0f
        // 新内容先挪出屏幕，等这一帧的布局完成后才开始动画，避免首帧闪出终态
        mBinding.vnvp.translationX = dir * w
        // 两层必须由同一个进度驱动：分别用各自的动画会因启动时刻不同而错位，
        // 接缝处就会露出底色
        val anim = ValueAnimator.ofFloat(0f, 1f).setDuration(SLIDE_MS)
        anim.interpolator = DecelerateInterpolator()
        anim.addUpdateListener { a ->
            val p = a.animatedFraction
            mBinding.vnslide.translationX = -dir * w * p
            mBinding.vnvp.translationX = dir * w * (1f - p)
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                mBinding.vnslide.visibility = View.GONE
                mBinding.vnslide.setImageDrawable(null)
                mBinding.vnvp.translationX = 0f
                slideAnim = null
                sliding = false
            }
        })
        slideAnim = anim
        // 等这一帧布局完成再起步：新章内容先摆好，动画第一帧就不会是空页
        mBinding.vnvp.post { anim.start() }
    }

    /** 把当前可见页画进一张位图（换章过渡用，缓冲复用） */
    private fun capturePager(): Bitmap? {
        val w = mBinding.vnvp.width
        val h = mBinding.vnvp.height
        if (w <= 0 || h <= 0) return null
        val bmp = slideBitmap?.takeIf { it.width == w && it.height == h }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { slideBitmap = it }
        bmp.eraseColor(if (night) NightTint.BG else 0xFFFCFCFF.toInt())
        mBinding.vnvp.draw(Canvas(bmp))
        return bmp
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
        // 优先按字符偏移恢复位置（页码会随字号变化），旧数据没有偏移才回退到页码
        val startOffset = if (sameVolume && saved!!.offset > 0) saved!!.offset else -1
        val startPage = if (sameVolume && startOffset < 0) saved!!.page else 0
        loadVolume(v, startChapter, startPage, startOffset)
    }

    private fun fetchVolume(m: NovelBookMeta, volumeId: String): NovelVolumeMeta? =
        NovelApi.volumeDetail(m.apiBase, m.pathWord, volumeId)?.also {
            m.details[it.id] = it
            NovelStore.save(this, m)
        }

    private fun loadVolume(
        v: NovelVolumeMeta,
        startChapter: Int,
        startPage: Int,
        startOffset: Int = -1
    ) {
        val m = meta ?: return
        vol = v
        chapterIndex = startChapter.coerceIn(0, (v.chapters.size - 1).coerceAtLeast(0))
        pendingRestorePage = startPage
        pendingRestoreOffset = startOffset
        lastAnchor = if (startOffset >= 0) startOffset else -1
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
        pendingRestoreOffset = -1
        lastAnchor = -1
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
        // 字号或安全区变化会重排分页，重排后回到同一处文字；优先用粘性锚点，
        // 这样连续换字号不会因为「取所在页页首」而一步步往回漂
        val anchor = if (lastAnchor >= 0) lastAnchor else currentAnchorOffset()
        val restore = pendingRestorePage
        val restoreOffset = pendingRestoreOffset
        pendingRestorePage = RESTORE_ANCHOR
        pendingRestoreOffset = -1

        val built = ArrayList<Page>()
        val offsets = ArrayList<Int>()
        val tops = ArrayList<Int>()
        if (ch.isImage) {
            val local = NovelStore.imageFile(this, meta?.name.orEmpty(), v.name, ch)
            built.add(Page().also { it.image = if (local.exists()) local else ch.imageUrl })
            offsets.add(0)
            tops.add(0)
        } else {
            val text = NovelStore.chapterText(fullText, ch)
            val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
                textSize = sp(fontSize)
                color = if (night) NightTint.FG else 0xFF333333.toInt()
            }
            // 断行策略与滚动模式的 TextView 保持一致，两种模式的换行位置才相同
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, w)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(dpf(LINE_SPACING_DP), 1f)
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
                tops.add(top)
                line = last + 1
            }
            if (built.isEmpty()) built.add(Page().also { it.layout = layout })
        }
        Log.d("NovelReader", "rebuild chapter=$chapterIndex name=${ch.name} isImage=${ch.isImage} textLen=${if (ch.isImage) -1 else NovelStore.chapterText(fullText, ch).length} box=$w x $h pages=${built.size}")
        pages = built
        pageOffsets = offsets
        pageTops = tops
        pagesKey = key
        suppressProgressSave = true
        mBinding.vnvp.adapter?.notifyDataSetChanged()
        val last = (pages.size - 1).coerceAtLeast(0)
        val target = when {
            restoreOffset >= 0 -> pageIndexForOffset(restoreOffset)
            restore == RESTORE_LAST -> last
            restore >= 0 -> restore.coerceIn(0, last)
            else -> pageIndexForOffset(anchor)
        }
        mBinding.vnvp.setCurrentItem(target, false)
        if (scrollMode) {
            loadScrollText()
            // 先按新分页把滚动位置摆好，避免旧位置先渲染一帧
            mBinding.vnscroll.scrollTo(0, pageTops.getOrElse(target) { 0 })
            scrollToPage(target)
        }
        suppressProgressSave = false
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
        // 进度以「章内字符偏移」为准：页码会随字号/分页尺寸变化，偏移不会
        val page: Int
        val offset: Int
        if (scrollMode) {
            // 布局还没跟上文本时读不到真实位置，宁可不写也不要覆盖成第 0 页
            if (!scrollLayoutReady()) return
            page = scrollTopPage()
            offset = pageOffsets.getOrElse(page) { lastAnchor }
        } else {
            page = mBinding.vnvp.currentItem
            offset = pageOffsets.getOrElse(page) { lastAnchor }
        }
        val keep = if (lastAnchor >= 0) lastAnchor else offset
        ReadingProgress.saveNovel(
            this, meta?.name.orEmpty(), v.id, v.name, chapterIndex, ch.name, page, keep
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
            mBinding.vntoc, mBinding.vnfontdec,
            mBinding.vnfontinc, mBinding.vnprev, mBinding.vnnext, mBinding.vnmode
        )) {
            b.setTextColor(NightTint.FG)
            b.setBackgroundResource(R.drawable.reader_btn_dark)
        }
        // 返回键是 ImageButton（矢量箭头），底色与上栏一致，只换图标颜色
        mBinding.vnback.setBackgroundResource(R.drawable.reader_back_btn_dark)
        mBinding.vnback.imageTintList = ColorStateList.valueOf(NightTint.FG)
    }

    private fun dp(v: Int): Int = (resources.displayMetrics.density * v).toInt()

    /** 与 dp 同样的换算但保留小数：行距等排版参数必须两种模式完全一致，
     *  否则每行差 1px，到几百页就会累积成一页以上的偏差。 */
    private fun dpf(v: Float): Float = resources.displayMetrics.density * v

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
        /** 换章滑动过渡时长（ms） */
        private const val SLIDE_MS = 250L
        /** 边界滑动接管手势的最小横向位移（dp） */
        private const val SWIPE_CLAIM_DP = 24
        /** 正文行距（dp）；分页与滚动必须用同一个值 */
        private const val LINE_SPACING_DP = 6f
        private const val MIN_FONT = 12f
        private const val MAX_FONT = 32f
        /** 重排后保持当前阅读位置 */
        private const val RESTORE_ANCHOR = Int.MIN_VALUE
        /** 跳到本章末页 */
        private const val RESTORE_LAST = -1
    }
}
