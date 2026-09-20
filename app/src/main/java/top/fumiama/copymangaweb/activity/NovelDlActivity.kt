package top.fumiama.copymangaweb.activity

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import top.fumiama.copymangaweb.R
import top.fumiama.copymangaweb.databinding.ActivityNoveldlBinding
import top.fumiama.copymangaweb.tool.InsetsTools
import top.fumiama.copymangaweb.tool.NightTint
import top.fumiama.copymangaweb.tool.NovelDownloader
import top.fumiama.copymangaweb.tool.NovelStore
import top.fumiama.copymangaweb.view.ChapterToggleButton
import java.util.concurrent.Executors

/**
 * 小说下载页：套用漫画下载页的形态（标题栏 + 每卷一个可勾选条目 + 底部操作条），
 * 逻辑换成小说 ——
 * - 条目来自本地 novel.json 的卷清单（由详情页的下载按钮带进来并 merge 落盘）；
 * - 已下载的卷在名字前打勾「✓ 」，可以勾选后删除（正文 txt + 该卷插图一起删）；
 * - 「下載選中」按顺序取卷详情并下载正文与插图，边下边更新进度。
 *
 * 入口：小说详情页侧边的下载按钮（i.js → GM.setNovelFab → MainActivity 的 FAB）。
 */
class NovelDlActivity : Activity() {
    private lateinit var mBinding: ActivityNoveldlBinding
    private val io = Executors.newSingleThreadExecutor()
    private var buttons: List<ChapterToggleButton> = emptyList()
    private var running = false

    private val bookName get() = bookNameArg
    private val night get() = NightTint.on(this)
    private val barOrigin by lazy { NightTint.captureBars(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mBinding = ActivityNoveldlBinding.inflate(layoutInflater)
        setContentView(mBinding.root)
        InsetsTools.applySafeContentInsets(this, mBinding.root)
        mBinding.ntitle.ttitle.text = bookName
        mBinding.ntitle.isearch.visibility = android.view.View.GONE
        mBinding.nall.setOnClickListener { toggleAll() }
        mBinding.nseldl.setOnClickListener { downloadSelected() }
        mBinding.ndel.setOnClickListener { confirmDeleteSelected() }
        applyNight()
        buildRows()
    }

    override fun onResume() {
        super.onResume()
        NightTint.applyBars(this, barOrigin)
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun applyNight() {
        if (!night) return
        mBinding.root.setBackgroundColor(NightTint.BG)
        mBinding.nlazys.setBackgroundColor(NightTint.BG)
        mBinding.nbar.setBackgroundColor(NightTint.SURFACE)
        mBinding.ntitle.titlecard.setCardBackgroundColor(NightTint.SURFACE)
        mBinding.ntitle.ttitle.setTextColor(NightTint.FG)
        mBinding.nstatus.setTextColor(NightTint.FG)
        NightTint.applyBars(this, barOrigin)
    }

    // ---------------- 卷列表 ----------------

    private fun meta() = NovelStore.load(this, bookName)

    private fun buildRows() {
        val meta = meta()
        mBinding.nvols.removeAllViews()
        if (meta == null || meta.volumes.isEmpty()) {
            mBinding.nstatus.text = "没有取到卷清单"
            buttons = emptyList()
            return
        }
        val fg = if (night) NightTint.FG else 0xFF333333.toInt()
        buttons = meta.volumes.mapIndexed { index, info ->
            val downloaded = NovelStore.isVolumeDownloaded(this, meta.name, info.name)
            ChapterToggleButton(this).apply {
                chapterName = if (downloaded) "✓ ${info.name}" else info.name
                url = info.id
                this.index = index
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (44 * resources.displayMetrics.density).toInt()
                ).also { it.setMargins(dp(10), dp(3), dp(10), dp(3)) }
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                textSize = 13f
                setTextColor(fg)
                background = ContextCompat.getDrawable(
                    this@NovelDlActivity,
                    if (night) R.drawable.toggle_button_dark else R.drawable.toggle_button
                )
                setOnLongClickListener {
                    confirmDelete(listOf(info))
                    true
                }
            }.also { mBinding.nvols.addView(it) }
        }
        updateStatus()
    }

    private fun updateStatus() {
        val meta = meta() ?: return
        val done = meta.volumes.count { NovelStore.isVolumeDownloaded(this, meta.name, it.name) }
        mBinding.nstatus.text = if (running) mBinding.nstatus.text
        else "已下载 $done / ${meta.volumes.size} 卷 · 勾选后可下载或删除"
    }

    private fun setStatus(text: String) {
        mBinding.nstatus.text = text
    }

    private fun dp(v: Int): Int = (resources.displayMetrics.density * v).toInt()

    private fun toggleAll() {
        val anyUnchecked = buttons.any { !it.isChecked }
        buttons.forEach { it.isChecked = anyUnchecked }
    }

    private fun selectedViews() = buttons.filter { it.isChecked }

    // ---------------- 下载 ----------------

    private fun downloadSelected() {
        val meta = meta() ?: return
        if (running) {
            Toast.makeText(this, "正在下载，请稍候", Toast.LENGTH_SHORT).show()
            return
        }
        val picked = selectedViews()
        if (picked.isEmpty()) {
            Toast.makeText(this, "请先勾选要下载的卷", Toast.LENGTH_SHORT).show()
            return
        }
        running = true
        val volumes = picked.mapNotNull { btn -> meta.volumes.getOrNull(btn.index) }
        io.execute {
            var ok = 0
            volumes.forEachIndexed { i, info ->
                runOnUiThread { setStatus("正在下载 ${i + 1}/${volumes.size}：${info.name}") }
                val done = NovelDownloader.downloadVolume(this, meta, info)
                if (done) ok++
                NovelStore.save(this, meta)
                runOnUiThread {
                    buttons.getOrNull(meta.volumes.indexOf(info))?.apply {
                        chapterName = "✓ ${info.name}"
                        isChecked = false
                    }
                    setStatus("已下载 ${i + 1}/${volumes.size}：${info.name}${if (done) "" else "（失败）"}")
                }
            }
            runOnUiThread {
                running = false
                Toast.makeText(this, "下载完成：$ok/${volumes.size} 卷", Toast.LENGTH_SHORT).show()
                buildRows()
            }
        }
    }

    // ---------------- 删除 ----------------

    private fun confirmDeleteSelected() {
        val meta = meta() ?: return
        val picked = selectedViews().mapNotNull { meta.volumes.getOrNull(it.index) }
        if (picked.isEmpty()) {
            Toast.makeText(this, "请先勾选要删除的卷", Toast.LENGTH_SHORT).show()
            return
        }
        confirmDelete(picked)
    }

    private fun confirmDelete(volumes: List<top.fumiama.copymangaweb.tool.NovelVolumeInfo>) {
        val meta = meta() ?: return
        val names = volumes.joinToString("、") { it.name }
        AlertDialog.Builder(this)
            .setTitle("删除本地内容？")
            .setMessage(names)
            .setPositiveButton("删除") { _, _ ->
                io.execute {
                    volumes.forEach { NovelStore.deleteVolume(this, meta.name, it.name) }
                    Log.d("NovelDl", "deleted ${volumes.size} volumes of ${meta.name}")
                    runOnUiThread {
                        Toast.makeText(this, "已删除 ${volumes.size} 卷", Toast.LENGTH_SHORT).show()
                        buildRows()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        /** 详情页注入的下载按钮点进来时设置 */
        var bookNameArg: String = ""
    }
}
