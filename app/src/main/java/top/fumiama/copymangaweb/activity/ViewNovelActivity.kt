package top.fumiama.copymangaweb.activity

import android.app.Activity
import android.content.Context
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
    private var pendingRestorePage = 0

    private val night get() = NightTint.on(this)
    private val barOrigin by lazy { NightTint.captureBars(this) }

    private class Page {
        var text: String? = null
        var image: Any? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mBinding = ActivityViewnovelBinding.inflate(layoutInflater)
        setContentView(mBinding.root)
        InsetsTools.applySafeContentInsets(this, mBinding.root)
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
        mBinding.vnvp.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            if (r - l != orr - ol || b - t != ob - ot) rebuildPages()
        }
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
        mBinding.vnvp.post { hideSystemBars() }
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
        pendingRestorePage = if (page == Int.MAX_VALUE) -1 else page
        pagesKey = ""
        rebuildPages()
        updateTitles()
    }

    // ---------------- 分页 ----------------

    private fun rebuildPages() {
        val v = vol ?: return
        val ch = v.chapters.getOrNull(chapterIndex) ?: return
        val w = mBinding.vnvp.width - dp(40)
        val h = mBinding.vnvp.height - dp(84)
        if (w <= 0 || h <= 0) {
            Log.d("NovelReader", "skip rebuild, size=$w x $h")
            return
        }
        val key = "${v.id}|${chapterIndex}|${fontSize}|${w}x${h}"
        if (key == pagesKey && pages.isNotEmpty()) {
            Log.d("NovelReader", "rebuild skipped, same key=$key")
            return
        }

        val built = ArrayList<Page>()
        if (ch.isImage) {
            val local = NovelStore.imageFile(this, meta?.name.orEmpty(), v.name, ch)
            built.add(Page().also { it.image = if (local.exists()) local else ch.imageUrl })
        } else {
            val text = NovelStore.chapterText(fullText, ch)
            val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
                textSize = sp(fontSize)
                color = if (night) NightTint.FG else 0xFF333333.toInt()
            }
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, w)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(dp(6).toFloat(), 1f)
                .setIncludePad(false)
                .build()
            var line = 0
            while (line < layout.lineCount) {
                val top = layout.getLineTop(line)
                var last = layout.getLineForVertical(top + h)
                if (last < line) last = line
                if (last >= layout.lineCount) last = layout.lineCount - 1
                val start = layout.getLineStart(line)
                val end = layout.getLineEnd(last)
                built.add(Page().also { it.text = text.substring(start, end).trim('\n') })
                line = last + 1
            }
            if (built.isEmpty()) built.add(Page().also { it.text = text })
        }
        Log.d("NovelReader", "rebuild chapter=$chapterIndex name=${ch.name} isImage=${ch.isImage} textLen=${if (ch.isImage) -1 else NovelStore.chapterText(fullText, ch).length} box=$w x $h pages=${built.size}")
        pages = built
        pagesKey = key
        mBinding.vnvp.adapter?.notifyDataSetChanged()
        val target = (if (pendingRestorePage < 0) pages.size - 1 else pendingRestorePage)
            .coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        pendingRestorePage = 0
        mBinding.vnvp.setCurrentItem(target, false)
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
        mBinding.vnposright.text =
            "${mBinding.vnvp.currentItem + 1} / ${pages.size.coerceAtLeast(1)}"
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
        ReadingProgress.saveNovel(
            this, meta?.name.orEmpty(), v.id, v.name, chapterIndex, ch.name,
            mBinding.vnvp.currentItem
        )
    }

    // ---------------- 页面适配 ----------------

    private inner class PageAdapter : RecyclerView.Adapter<PageAdapter.Holder>() {
        private val detector = GestureDetector(
            this@ViewNovelActivity,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    val w = mBinding.vnvp.width.coerceAtLeast(1)
                    when {
                        e.x < w * 0.33f -> turnPage(-1)
                        e.x > w * 0.67f -> turnPage(1)
                        else -> toggleBars()
                    }
                    return true
                }
            }
        )

        inner class Holder(root: FrameLayout, val text: TextView, val image: ImageView) :
            RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val ctx = this@ViewNovelActivity
            val frame = FrameLayout(ctx).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setPadding(dp(20), dp(42), dp(20), dp(38))
            }
            val tv = TextView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setLineSpacing(dp(6).toFloat(), 1f)
            }
            val iv = ImageView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
                scaleType = ImageView.ScaleType.FIT_CENTER
                visibility = View.GONE
            }
            frame.addView(tv)
            frame.addView(iv)
            frame.setOnTouchListener { _, ev -> detector.onTouchEvent(ev) }
            return Holder(frame, tv, iv)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val page = pages.getOrNull(position) ?: return
            if (page.text != null) {
                holder.text.visibility = View.VISIBLE
                holder.image.visibility = View.GONE
                holder.text.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize)
                holder.text.setTextColor(if (night) NightTint.FG else 0xFF333333.toInt())
                holder.text.text = page.text
            } else {
                holder.text.visibility = View.GONE
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
        for (b in listOf(
            mBinding.vnback, mBinding.vntoc, mBinding.vnfontdec,
            mBinding.vnfontinc, mBinding.vnprev, mBinding.vnnext
        )) {
            b.setTextColor(NightTint.FG)
            b.setBackgroundResource(R.drawable.rndbg_white_dark)
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
    }
}
