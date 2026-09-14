package top.fumiama.copymangaweb.activity

import android.app.Activity
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.Toast
import top.fumiama.copymangaweb.R
import top.fumiama.copymangaweb.databinding.ActivityViewnovelBinding
import top.fumiama.copymangaweb.tool.InsetsTools
import top.fumiama.copymangaweb.tool.NightTint
import top.fumiama.copymangaweb.tool.NovelApi
import top.fumiama.copymangaweb.tool.NovelBookMeta
import top.fumiama.copymangaweb.tool.NovelStore
import top.fumiama.copymangaweb.tool.NovelVolumeMeta
import top.fumiama.copymangaweb.tool.ReadingProgress
import java.util.concurrent.Executors

/**
 * 原生小说阅读器。版式参照站点在线阅读页：顶部标题、正文滚动、底部翻章。
 * 正文优先读本地（已下载/已缓存），缺失时按 txt_addr 下载并缓存，因此在线与离线都能用。
 */
class ViewNovelActivity : Activity() {
    private lateinit var mBinding: ActivityViewnovelBinding
    private val io = Executors.newSingleThreadExecutor()
    private var meta: NovelBookMeta? = null
    private var vol: NovelVolumeMeta? = null
    private var fullText: String = ""
    private var chapterIndex = 0
    private var fontSize = 18f
    private val barOrigin by lazy { NightTint.captureBars(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mBinding = ActivityViewnovelBinding.inflate(layoutInflater)
        setContentView(mBinding.root)
        // 与其他页面一致：把状态栏/挖孔/手势条的安全区作为内边距，避免顶栏被系统栏压住
        InsetsTools.applySafeContentInsets(this, mBinding.root)
        fontSize = getSharedPreferences(PREF, MODE_PRIVATE).getFloat(KEY_FONT, 18f)
        mBinding.vntitle.isearch.visibility = View.GONE
        applyNight()
        applyFont()
        mBinding.vnprev.setOnClickListener { stepChapter(-1) }
        mBinding.vnnext.setOnClickListener { stepChapter(1) }
        mBinding.vnfontdec.setOnClickListener { changeFont(-1f) }
        mBinding.vnfontinc.setOnClickListener { changeFont(1f) }
        mBinding.vntext.text = getString(R.string.loading)
        NightTint.applyBars(this, barOrigin)
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
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    // ---------------- 加载 ----------------

    private fun openBook(book: String, volumeId: String?) {
        val m = NovelStore.load(this, book)
        if (m == null) {
            toastOnUi("未找到本地小说数据")
            finishOnUi()
            return
        }
        meta = m
        val saved = ReadingProgress.novel(this, book)
        // 未指定卷时（如从“我的下载”进入）回到上次读到的卷与章
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
        val startChapter = if (saved != null && saved.volumeId == v.id) saved.chapterIndex else 0
        loadVolume(v, startChapter)
    }

    private fun fetchVolume(m: NovelBookMeta, volumeId: String): NovelVolumeMeta? =
        NovelApi.volumeDetail(m.apiBase, m.pathWord, volumeId)?.also {
            m.details[it.id] = it
            NovelStore.save(this, m)
        }

    private fun loadVolume(v: NovelVolumeMeta, startChapter: Int) {
        val m = meta ?: return
        vol = v
        chapterIndex = startChapter.coerceIn(0, (v.chapters.size - 1).coerceAtLeast(0))
        var text = NovelStore.volumeText(this, m, v)
        if (text == null) {
            if (v.txtAddr.isBlank()) {
                toastOnUi("该卷缺少正文地址")
                return
            }
            toastOnUi("正在获取正文…")
            val dest = NovelStore.txtFile(this, m.name, v.name)
            if (NovelApi.downloadBinary(v.txtAddr, dest)) {
                text = NovelStore.volumeText(this, m, v)
            }
        }
        if (text == null) {
            toastOnUi("正文获取失败")
            return
        }
        fullText = text
        runOnUiThread { render() }
    }

    // ---------------- 渲染 ----------------

    private fun render() {
        val v = vol ?: return
        val ch = v.chapters.getOrNull(chapterIndex)
        mBinding.vntitle.ttitle.text = buildString {
            append(meta?.name.orEmpty())
            if (v.name.isNotBlank()) append(' ').append(v.name)
            ch?.name?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
        }
        mBinding.vntext.text = ch?.let { NovelStore.chapterText(fullText, it) }.orEmpty()
        mBinding.vnpos.text = "${chapterIndex + 1}/${v.chapters.size}"
        mBinding.vnscroll.post { mBinding.vnscroll.scrollTo(0, 0) }
        mBinding.vnprev.isEnabled = chapterIndex > 0 || !v.prev.isNullOrBlank()
        mBinding.vnnext.isEnabled = chapterIndex < v.chapters.size - 1 || !v.next.isNullOrBlank()
        if (ch != null) {
            ReadingProgress.saveNovel(this, meta?.name.orEmpty(), v.id, v.name, chapterIndex, ch.name)
        }
    }

    private fun stepChapter(delta: Int) {
        val v = vol ?: return
        val target = chapterIndex + delta
        if (target in v.chapters.indices) {
            chapterIndex = target
            render()
            return
        }
        val adjacentId = if (delta > 0) v.next else v.prev
        if (adjacentId.isNullOrBlank()) {
            Toast.makeText(
                this,
                if (delta > 0) "已经是最后一卷" else "已经是第一卷",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        io.execute {
            val m = meta ?: return@execute
            val adjacent = m.details[adjacentId]
                ?: NovelApi.volumeDetail(m.apiBase, m.pathWord, adjacentId)?.also {
                    m.details[it.id] = it
                    NovelStore.save(this, m)
                }
            if (adjacent == null) {
                toastOnUi("切换卷失败")
                return@execute
            }
            loadVolume(adjacent, if (delta > 0) 0 else (adjacent.chapters.size - 1).coerceAtLeast(0))
        }
    }

    private fun changeFont(delta: Float) {
        fontSize = (fontSize + delta).coerceIn(MIN_FONT, MAX_FONT)
        getSharedPreferences(PREF, MODE_PRIVATE).edit().putFloat(KEY_FONT, fontSize).apply()
        applyFont()
    }

    private fun applyFont() {
        mBinding.vntext.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSize)
    }

    private fun applyNight() {
        if (!NightTint.on(this)) return
        mBinding.vnroot.setBackgroundColor(NightTint.BG)
        mBinding.vnscroll.setBackgroundColor(NightTint.BG)
        mBinding.vnbar.setBackgroundColor(NightTint.SURFACE)
        mBinding.vntext.setTextColor(NightTint.FG)
        mBinding.vnpos.setTextColor(NightTint.FG)
        mBinding.vntitle.titlecard.setCardBackgroundColor(NightTint.SURFACE)
        mBinding.vntitle.ttitle.setTextColor(NightTint.FG)
        for (b in listOf(mBinding.vnprev, mBinding.vnnext, mBinding.vnfontdec, mBinding.vnfontinc)) {
            b.setTextColor(NightTint.FG)
            b.setBackgroundResource(R.drawable.rndbg_white_dark)
        }
    }

    // ---------------- 小工具 ----------------

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
